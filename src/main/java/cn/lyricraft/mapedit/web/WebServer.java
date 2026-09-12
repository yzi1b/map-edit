package cn.lyricraft.mapedit.web;

import cn.lyricraft.mapedit.MapEditCommand;
import cn.lyricraft.mapedit.MapEditConfig;
import cn.lyricraft.mapedit.map.Thumbnail;
import cn.lyricraft.mapedit.storage.TreeData.TreeNode;
import cn.lyricraft.mapedit.storage.TreeStore.TreeException;
import cn.lyricraft.mapedit.storage.WorldStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.map.MapPalette;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Web 服务（JDK HttpServer，零第三方运行时依赖）。
 * 像素转换全在前端完成，服务端只收 byte[8192]/格的像素表。
 */
public final class WebServer {

    private static final Gson GSON = new Gson();

    private static final int THUMB_CACHE_MAX = 1024;

    private final JavaPlugin plugin;
    private final MapEditConfig config;
    private final TokenManager tokens;
    private final WorldStore stores;
    private final ExecutorService executor;
    private final Map<String, byte[]> thumbCache = new ConcurrentHashMap<>();
    private HttpServer server;

    public WebServer(JavaPlugin plugin, MapEditConfig config, WorldStore stores) {
        this.plugin = plugin;
        this.config = config;
        this.stores = stores;
        this.tokens = new TokenManager(MapEditCommand.PERM_WEB,
                java.time.Duration.ofSeconds(config.webTokenTtlSeconds()));
        this.executor = Executors.newFixedThreadPool(6);
    }

    public boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress(config.webBind(), config.webPort()), 64);
            server.createContext("/", this::route);
            server.setExecutor(executor);
            server.start();
            plugin.getLogger().info("Web 服务已启动: http://" + config.webBind() + ":" + config.webPort() + "/");
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Web 服务启动失败: " + e.getMessage());
            return false;
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdown();
        try {
            executor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        tokens.clear();
    }

    public TokenManager tokens() {
        return tokens;
    }

    public String urlFor(String token) {
        String uri = config.webUri();
        if (!uri.endsWith("/")) {
            uri += "/";
        }
        return uri + "?token=" + token;
    }

    // ===== 路由 =====

    private void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/") || path.equals("/index.html")) {
                page(ex);
            } else if (path.equals("/api/palette")) {
                palette(ex);
            } else if (path.equals("/api/tree")) {
                api(ex, this::apiTree);
            } else if (path.equals("/dev/tree")) {
                devTree(ex);
            } else if (path.equals("/api/folder") && method.equals("POST")) {
                api(ex, this::apiCreateFolder);
            } else if (path.equals("/api/painting") && method.equals("POST")) {
                api(ex, this::apiCreatePainting);
            } else if (path.equals("/api/painting/replace") && method.equals("POST")) {
                api(ex, this::apiReplacePainting);
            } else if (path.equals("/api/node") && method.equals("DELETE")) {
                api(ex, this::apiDeleteNode);
            } else if (path.equals("/api/rename") && method.equals("POST")) {
                api(ex, this::apiRename);
            } else if (path.equals("/api/painting") && method.equals("GET")) {
                api(ex, this::apiGetPainting);
            } else if (path.equals("/api/thumb")) {
                api(ex, this::apiThumb);
            } else if (path.equals("/api/renew") && method.equals("POST")) {
                renew(ex);
            } else {
                send(ex, 404, "{\"ok\":false,\"error\":\"not found\"}");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Web 请求异常: " + e);
            send(ex, 500, "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private void page(HttpExchange ex) throws IOException {
        // 页面本身不含数据，缺失/无效访问一律返回页面，由前端以统一样式提示获取最新链接；
        // 数据安全由 API 层鉴权保证（authedOwner）。
        try (InputStream in = plugin.getResource("web/index.html")) {
            if (in == null) {
                send(ex, 500, "web/index.html 缺失".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    // ===== API 公共部分 =====

    @FunctionalInterface
    private interface ApiAction {
        void run(HttpExchange ex, Player owner, JsonObject body) throws Exception;
    }

    private void api(HttpExchange ex, ApiAction action) throws Exception {
        Player owner = authedOwner(ex);
        if (owner == null) {
            send(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
            return;
        }
        JsonObject body = null;
        String bodyText = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (!bodyText.isBlank()) {
            try {
                body = JsonParser.parseString(bodyText).getAsJsonObject();
            } catch (RuntimeException e) {
                send(ex, 400, "{\"ok\":false,\"error\":\"bad json\"}");
                return;
            }
        }
        try {
            action.run(ex, owner, body);
        } catch (TreeException e) {
            send(ex, 400, GSON.toJson(JsonError.of(e.getMessage())));
        } catch (IllegalArgumentException e) {
            send(ex, 400, GSON.toJson(JsonError.of(e.getMessage())));
        } catch (IllegalStateException e) {
            send(ex, 500, GSON.toJson(JsonError.of(e.getMessage())));
        }
    }

    /** token → 生成者；生成者必须在线（世界/权限均取其当前状态） */
    private Player authedOwner(HttpExchange ex) {
        String token = query(ex, "token");
        var session = tokens.session(token);
        if (session == null) {
            return null;
        }
        Player owner = Bukkit.getPlayer(session.owner());
        if (owner == null || !owner.isOnline()) {
            tokens.revoke(token);
            return null;
        }
        return owner;
    }

    /** 开发诊断：列出默认世界树（仅用于本机调试，无鉴权） */
    private void devTree(HttpExchange ex) throws IOException {
        try {
            World w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
            if (w == null) {
                send(ex, 500, "{\"ok\":false,\"error\":\"no world\"}");
                return;
            }
            var store = stores.treeIfLoaded(w);
            if (store.isEmpty()) {
                send(ex, 500, "{\"ok\":false,\"error\":\"tree unavailable\"}");
                return;
            }
            JsonObject tree = new JsonObject();
            JsonArray children = new JsonArray();
            for (TreeNode node : store.get().childrenOf("")) {
                children.add(nodeJson(node));
            }
            tree.add("children", children);
            send(ex, 200, GSON.toJson(tree));
        } catch (TreeException e) {
            send(ex, 500, GSON.toJson(JsonError.of(e.getMessage())));
        }
    }

    private void renew(HttpExchange ex) throws IOException {
        String token = query(ex, "token");
        boolean ok = tokens.renew(token);
        send(ex, ok ? 200 : 401, "{\"ok\":" + ok + "}");
    }

    // ===== API 实现 =====

    private void apiTree(HttpExchange ex, Player owner, JsonObject body) throws IOException {
        var store = stores.treeIfLoaded(owner.getWorld());
        if (store.isEmpty()) {
            send(ex, 500, "{\"ok\":false,\"error\":\"tree unavailable\"}");
            return;
        }
        try {
            JsonObject tree = new JsonObject();
            JsonArray children = new JsonArray();
            for (TreeNode node : store.get().childrenOf("")) {
                children.add(nodeJson(node));
            }
            tree.add("children", children);
            send(ex, 200, GSON.toJson(tree));
        } catch (TreeException e) {
            send(ex, 400, GSON.toJson(JsonError.of(e.getMessage())));
        }
    }

    private JsonObject nodeJson(TreeNode n) {
        JsonObject o = new JsonObject();
        o.addProperty("type", n.type);
        o.addProperty("name", n.name);
        if (n.isPainting()) {
            o.addProperty("cols", n.cols);
            o.addProperty("rows", n.rows);
            o.addProperty("pid", n.pid);
        } else {
            JsonArray children = new JsonArray();
            for (TreeNode c : n.children) {
                children.add(nodeJson(c));
            }
            o.add("children", children);
        }
        return o;
    }

    private void apiCreateFolder(HttpExchange ex, Player owner, JsonObject body) throws IOException, TreeException {
        String parent = str(body, "parent", "");
        String name = str(body, "name", null);
        if (name == null) {
            throw new IllegalArgumentException("缺少 name");
        }
        var store = requiredStore(owner);
        store.createFolder(parent, name);
        sendOk(ex);
    }

    private void apiRename(HttpExchange ex, Player owner, JsonObject body) throws IOException, TreeException {
        String path = str(body, "path", null);
        String name = str(body, "name", null);
        if (path == null) {
            throw new IllegalArgumentException("缺少 path");
        }
        if (name == null) {
            throw new IllegalArgumentException("缺少 name");
        }
        var store = requiredStore(owner);
        String newName = name.trim();
        TreeNode n = store.rename(path, newName);
        int i = path.lastIndexOf('/');
        String parent = i <= 0 ? "" : path.substring(0, i);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("path", parent.isEmpty() ? n.name : parent + "/" + n.name);
        send(ex, 200, GSON.toJson(o));
    }

    private void apiDeleteNode(HttpExchange ex, Player owner, JsonObject body) throws IOException, TreeException {
        String path = query(ex, "path");
        var store = requiredStore(owner);
        store.resolve(path); // 不存在 → 404
        World world = owner.getWorld();
        List<Integer> pids = stores.delete(world, path);
        for (int pid : pids) {
            thumbCache.remove(thumbKey(world, pid));
        }
        sendOk(ex);
    }

    private static String thumbKey(World world, int pid) {
        return world.getName() + "|" + pid;
    }

    /** 上传像素表创建地图画：前端已完成切片与调色板转换 */
    private void apiCreatePainting(HttpExchange ex, Player owner, JsonObject body)
            throws IOException, TreeException {
        String parent = str(body, "parent", "");
        String name = str(body, "name", null);
        int cols = intOf(body, "cols");
        int rows = intOf(body, "rows");
        if (name == null) {
            throw new IllegalArgumentException("缺少 name");
        }
        if (cols < 1 || rows < 1 || cols > 32 || rows > 32 || (long) cols * rows > 512) {
            throw new IllegalArgumentException("网格数超出范围（1..512 张）");
        }
        List<String> tilesB64 = new java.util.ArrayList<>();
        JsonArray arr = body.getAsJsonArray("tiles");
        if (arr == null || arr.size() != cols * rows) {
            throw new IllegalArgumentException("tiles 数量与网格不一致");
        }
        for (var e : arr) {
            tilesB64.add(e.getAsString());
        }
        byte[][] tiles = new byte[cols * rows][];
        for (int i = 0; i < tilesB64.size(); i++) {
            tiles[i] = Base64.getDecoder().decode(tilesB64.get(i));
            if (tiles[i].length != WorldStore.TILE_BYTES) {
                throw new IllegalArgumentException("tile " + i + " 长度错误: " + tiles[i].length);
            }
        }
        World world = owner.getWorld();
        var store = requiredStore(owner);
        TreeNode parentNode = store.resolve(parent);
        if (!parentNode.isFolder()) {
            throw new IllegalArgumentException("不是目录: " + parent);
        }
        boolean dup = parentNode.children.stream().anyMatch(c -> c.name.equals(name));
        if (dup) {
            throw new IllegalArgumentException("同级已存在: " + name);
        }
        // 分配与写像素在主线程（createMap 涉及服务端共享状态）
        java.util.concurrent.Future<JsonObject> future = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            List<byte[]> flat = new java.util.ArrayList<>(rows * cols);
            for (byte[] t : tiles) {
                flat.add(t);
            }
            List<Integer> ids = stores.createMapTiles(world, flat);
            List<List<Integer>> mapIds = new java.util.ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                mapIds.add(ids.subList(r * cols, (r + 1) * cols));
            }
            TreeNode p = store.createPainting(parent, name, cols, rows, mapIds);
            byte[] all = new byte[cols * rows * WorldStore.TILE_BYTES];
            for (int i = 0; i < tiles.length; i++) {
                System.arraycopy(tiles[i], 0, all, i * WorldStore.TILE_BYTES, WorldStore.TILE_BYTES);
            }
            stores.writePixels(world, p.pid, all);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("path", parent.isEmpty() ? name : parent + "/" + name);
            o.addProperty("pid", p.pid);
            o.addProperty("created", true);
            return o;
        });
        JsonObject result;
        try {
            result = future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 500, "{\"ok\":false,\"error\":\"interrupted\"}");
            return;
        } catch (ExecutionException e) {
            send(ex, 500, "{\"ok\":false,\"error\":\"server task failed\"}");
            return;
        }
        send(ex, 200, GSON.toJson(result));
    }

    /** 解码 tiles base64 列表 → byte[][]（行优先） */
    private byte[][] decodeTiles(JsonObject body, int cols, int rows) {
        JsonArray arr = body.getAsJsonArray("tiles");
        if (arr == null || arr.size() != cols * rows) {
            throw new IllegalArgumentException("tiles 数量与网格不一致");
        }
        byte[][] tiles = new byte[cols * rows][];
        for (int i = 0; i < arr.size(); i++) {
            tiles[i] = Base64.getDecoder().decode(arr.get(i).getAsString());
            if (tiles[i].length != WorldStore.TILE_BYTES) {
                throw new IllegalArgumentException("tile " + i + " 长度错误: " + tiles[i].length);
            }
        }
        return tiles;
    }

    private byte[] concatTiles(byte[][] tiles) {
        byte[] all = new byte[tiles.length * WorldStore.TILE_BYTES];
        for (int i = 0; i < tiles.length; i++) {
            System.arraycopy(tiles[i], 0, all, i * WorldStore.TILE_BYTES, WorldStore.TILE_BYTES);
        }
        return all;
    }

    /** 替换图片：网格不变、pid 不变，重新分配 map id（旧 id 不回收） */
    private void apiReplacePainting(HttpExchange ex, Player owner, JsonObject body)
            throws IOException, TreeException {
        String path = str(body, "path", null);
        int cols = intOf(body, "cols");
        int rows = intOf(body, "rows");
        if (path == null || cols < 1 || rows < 1 || cols > 32 || rows > 32 || (long) cols * rows > 512) {
            throw new IllegalArgumentException("参数不合法");
        }
        byte[][] tiles = decodeTiles(body, cols, rows);
        World world = owner.getWorld();
        var store = requiredStore(owner);
        TreeNode painting = store.resolve(path);
        if (!painting.isPainting()) {
            throw new IllegalArgumentException("不是地图画: " + path);
        }
        if (painting.cols != cols || painting.rows != rows) {
            throw new IllegalArgumentException("替换必须保持网格 " + painting.cols + "×" + painting.rows);
        }
        java.util.concurrent.Future<JsonObject> replaceFuture = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
            // 替换 = 同 id 覆写(不新建地图):已部署/背包中的旧物品引用同一批 id,内容即更新
            List<Integer> flatIds = new java.util.ArrayList<>(rows * cols);
            for (List<Integer> rowIds : painting.mapIds) {
                flatIds.addAll(rowIds);
            }
            List<byte[]> flat = new java.util.ArrayList<>(rows * cols);
            for (byte[] t : tiles) {
                flat.add(t);
            }
            stores.overwriteMapTiles(world, flatIds, flat);
            stores.writePixels(world, painting.pid, concatTiles(tiles));
            refreshMapStates(flatIds, flat);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("pid", painting.pid);
            return o;
        });
        JsonObject out;
        try {
            out = replaceFuture.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            send(ex, 500, "{\"ok\":false}");
            return;
        } catch (ExecutionException e) {
            send(ex, 500, "{\"ok\":false}");
            return;
        }
        thumbCache.remove(thumbKey(world, painting.pid)); // pid 不变，缩略图缓存失效
        send(ex, 200, GSON.toJson(out));
    }

    /**
     * 替换后同步服务端内存态:覆写磁盘 dat 后,已 getMap 懒加载驻留内存的
     * MapItemSavedData 仍是旧像素,客户端重连拉到的也是旧数据。
     * 实现:CraftMapView.worldMap(MapItemSavedData).colors(byte[16384]) 就地覆写,
     * 并清空渲染/画布缓存;找不到预期结构时记一次诊断日志后跳过(文件已更新,重启仍生效)。
     * 均在主线程(调用方 callSyncMethod)执行,反射失败不中断替换主流程。
     */
    private static boolean mapStateDiagDone = false;

    private void refreshMapStates(List<Integer> ids, List<byte[]> tiles) {
        for (int i = 0; i < ids.size(); i++) {
            refreshMapState(ids.get(i), tiles.get(i));
        }
    }

    private void refreshMapState(int id, byte[] tile) {
        try {
            org.bukkit.map.MapView view = Bukkit.getMap(id);
            if (view == null) {
                return;
            }
            java.lang.reflect.Field worldMapField = view.getClass().getDeclaredField("worldMap");
            worldMapField.setAccessible(true);
            Object worldMap = worldMapField.get(view);
            byte[] mem = null;
            String diag = "MapView=" + view.getClass().getName();
            if (worldMap != null) {
                diag += " worldMap=" + worldMap.getClass().getName();
                try {
                    java.lang.reflect.Field colorsField = worldMap.getClass().getDeclaredField("colors");
                    colorsField.setAccessible(true);
                    Object v = colorsField.get(worldMap);
                    if (v instanceof byte[] colors) {
                        mem = colors;
                    } else {
                        diag += " colors type=" + (v == null ? "null" : v.getClass().getName());
                    }
                } catch (NoSuchFieldException e) {
                    diag += " 无 colors 字段";
                }
            }
            if (mem == null || mem.length != WorldStore.TILE_BYTES) {
                if (!mapStateDiagDone) {
                    mapStateDiagDone = true;
                    plugin.getLogger().warning("[replace] 内存态结构不符,跳过同步(" + diag + ");文件已更新,重启后生效");
                }
                return;
            }
            System.arraycopy(tile, 0, mem, 0, WorldStore.TILE_BYTES);
            for (String fieldName : new String[] {"renderCache", "canvases"}) {
                try {
                    java.lang.reflect.Field cacheField = view.getClass().getDeclaredField(fieldName);
                    cacheField.setAccessible(true);
                    Object cache = cacheField.get(view);
                    if (cache instanceof java.util.Map<?, ?> m) {
                        m.clear();
                    }
                } catch (NoSuchFieldException ignore) {
                    // 该版本无此缓存字段,无碍
                }
            }
        } catch (ReflectiveOperationException e) {
            if (!mapStateDiagDone) {
                mapStateDiagDone = true;
                plugin.getLogger().warning("[replace] 内存态刷新失败(" + e + ");文件已更新,重启后生效");
            }
        }
    }

    /** 返回地图画元数据 + 逐格像素表（前端渲染预览） */
    private void apiGetPainting(HttpExchange ex, Player owner, JsonObject body) throws IOException, TreeException {
        String path = query(ex, "path");
        var store = requiredStore(owner);
        TreeNode p = store.resolve(path);
        if (!p.isPainting()) {
            throw new IllegalArgumentException("不是地图画: " + path);
        }
        byte[] all = stores.readPixels(owner.getWorld(), p.pid);
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("name", p.name);
        o.addProperty("cols", p.cols);
        o.addProperty("rows", p.rows);
        o.addProperty("pid", p.pid);
        JsonArray mapIds = new JsonArray();
        for (var row : p.mapIds) {
            JsonArray rowArr = new JsonArray();
            row.forEach(rowArr::add);
            mapIds.add(rowArr);
        }
        o.add("mapIds", mapIds);
        JsonArray tiles = new JsonArray();
        if (all != null && all.length == p.cols * p.rows * WorldStore.TILE_BYTES) {
            for (int i = 0; i < p.cols * p.rows; i++) {
                tiles.add(Base64.getEncoder().encodeToString(
                        java.util.Arrays.copyOfRange(all, i * WorldStore.TILE_BYTES,
                                (i + 1) * WorldStore.TILE_BYTES)));
            }
        }
        o.add("tiles", tiles);
        send(ex, 200, GSON.toJson(o));
    }

    /** 地图画缩略图（PNG data URL）；解码+降采样成本低且有内存缓存 */
    private void apiThumb(HttpExchange ex, Player owner, JsonObject body) throws IOException, TreeException {
        String path = query(ex, "path");
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("缺少 path");
        }
        var store = requiredStore(owner);
        TreeNode n = store.resolve(path);
        if (!n.isPainting()) {
            throw new IllegalArgumentException("不是地图画: " + path);
        }
        World world = owner.getWorld();
        String key = thumbKey(world, n.pid);
        byte[] png = thumbCache.get(key);
        if (png == null) {
            byte[] px = stores.readPixels(world, n.pid);
            if (px == null) {
                throw new IllegalArgumentException("像素数据缺失（地图画可能已损坏）");
            }
            png = Thumbnail.encode(px, n.cols, n.rows, Thumbnail.DEFAULT_MAX_SIDE);
            if (png == null) {
                throw new IllegalStateException("缩略图编码失败");
            }
            if (thumbCache.size() >= THUMB_CACHE_MAX) {
                thumbCache.clear();
            }
            thumbCache.put(key, png);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("img", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        send(ex, 200, GSON.toJson(o));
    }

    /** 服务端调色板（byte → 颜色），前端用它做最近色匹配与预览上色 */
    private void palette(HttpExchange ex) throws IOException {
        JsonArray colors = new JsonArray();
        for (int i = 0; i < 256; i++) {
            byte b = (byte) (i - 128);
            try {
                java.awt.Color c = MapPalette.getColor(b);
                JsonObject e = new JsonObject();
                e.addProperty("b", b);
                e.addProperty("rgb", String.format("%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
                // byte 0..3 = 透明槽（游戏中不可见）；最近色匹配必须跳过，仅显式透明使用
                if (b >= 0 && b <= 3) {
                    e.addProperty("transparent", true);
                }
                colors.add(e);
            } catch (RuntimeException ignored) {
                // 无对应颜色的索引跳过
            }
        }
        JsonObject o = new JsonObject();
        o.add("colors", colors);
        send(ex, 200, GSON.toJson(o));
    }

    // ===== 工具 =====

    private record JsonError(boolean ok, String error) {
        static JsonObject of(String msg) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", false);
            o.addProperty("error", msg);
            return o;
        }
    }

    private cn.lyricraft.mapedit.storage.TreeStore requiredStore(Player owner) {
        var s = stores.treeIfLoaded(owner.getWorld());
        if (s.isEmpty()) {
            throw new IllegalStateException("世界目录树不可用");
        }
        return s.get();
    }

    private static void sendOk(HttpExchange ex) throws IOException {
        send(ex, 200, "{\"ok\":true}");
    }

    private static void send(HttpExchange ex, int code, String text) throws IOException {
        send(ex, code, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange ex, int code, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if (code == 200 || code == 401) {
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        }
        ex.sendResponseHeaders(code, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static String query(HttpExchange ex, String key) {
        URI uri = ex.getRequestURI();
        String raw = uri.getQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                return URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static int intOf(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }
}
