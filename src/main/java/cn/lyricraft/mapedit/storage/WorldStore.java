package cn.lyricraft.mapedit.storage;

import cn.lyricraft.mapedit.map.MapDataFile;
import cn.lyricraft.mapedit.storage.TreeData.TreeNode;
import cn.lyricraft.mapedit.storage.TreeStore.TreeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * 按世界存档独立的地图画存储。
 * 每世界一个目录：{@code <world存档>/mapedit/}，内含 tree.json 与 pixels/&lt;pid&gt;.bin（gzip）。
 * 目录树与像素均与地图文件强关联，故随世界存档走。
 */
public final class WorldStore {

    public static final int TILE_BYTES = 128 * 128;

    private static final class Entry {
        final TreeStore tree;
        final Path pixelsDir;
        final Path mapsDir;
        int nextMapId;

        Entry(TreeStore tree, Path pixelsDir, Path mapsDir, int nextMapId) {
            this.tree = tree;
            this.pixelsDir = pixelsDir;
            this.mapsDir = mapsDir;
            this.nextMapId = nextMapId;
        }
    }

    private final JavaPlugin plugin;
    private final Map<Path, Entry> entries = new ConcurrentHashMap<>();

    public WorldStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private Entry entry(World world) {
        Path levelDir = levelFolder(world);
        return entries.computeIfAbsent(levelDir, dir -> {
            Path base = dir.resolve("mapedit");
            Path pixelsDir = base.resolve("pixels");
            Path mapsDir = dir.resolve("data/minecraft/maps");
            try {
                Files.createDirectories(pixelsDir);
            } catch (IOException e) {
                throw new IllegalStateException("创建地图画目录失败: " + pixelsDir, e);
            }
            TreeStore tree = new TreeStore(base.resolve("tree.json"));
            try {
                tree.load();
            } catch (IOException e) {
                throw new IllegalStateException("加载目录树失败 (" + world.getName() + "): " + e.getMessage(), e);
            }
            int next = MapDataFile.BASE_ID;
            Path counter = base.resolve("next-map-id.txt");
            if (Files.isRegularFile(counter)) {
                try {
                    next = Integer.parseInt(Files.readString(counter).trim());
                } catch (IOException | NumberFormatException ignored) {
                }
            }
            if (next < MapDataFile.BASE_ID) {
                next = MapDataFile.BASE_ID;
            }
            return new Entry(tree, pixelsDir, mapsDir, next);
        });
    }

    /**
     * 自分配高位 map id 并直接写地图数据文件（“文件先行”，见 MapDataFile 说明）。
     * 批量调用，逐张：id 连续、写文件、推进自持游标。
     */
    public synchronized List<Integer> createMapTiles(World world, List<byte[]> tiles) {
        Entry e = entry(world);
        List<Integer> ids = new ArrayList<>(tiles.size());
        for (byte[] tile : tiles) {
            if (tile.length != TILE_BYTES) {
                throw new IllegalArgumentException("tile 长度错误: " + tile.length);
            }
        }
        try {
            Files.createDirectories(e.mapsDir);
            org.bukkit.Location spawn = world.getSpawnLocation();
            int centerX = spawn.getBlockX() * 8;
            int centerZ = spawn.getBlockZ() * 8;
            String dimension = world.getKey().toString();
            for (byte[] tile : tiles) {
                int id = e.nextMapId++;
                MapDataFile.write(e.mapsDir.resolve(id + ".dat"), dimension, centerX, centerZ, tile);
                ids.add(id);
            }
            Path counter = e.pixelsDir.getParent().resolve("next-map-id.txt");
            Files.writeString(counter, String.valueOf(e.nextMapId));
            return ids;
        } catch (IOException ex) {
            throw new IllegalStateException("写入地图文件失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 覆写既有地图 id 的数据文件(替换图片:不新建 id——已部署/背包中引用同批
     * id 的旧地图即成为新图;服务端内存态刷新见 WebServer 替换流程)。
     */
    public synchronized void overwriteMapTiles(World world, List<Integer> ids, List<byte[]> tiles) {
        if (ids.size() != tiles.size()) {
            throw new IllegalArgumentException("id 与 tile 数量不一致");
        }
        Entry e = entry(world);
        for (byte[] tile : tiles) {
            if (tile.length != TILE_BYTES) {
                throw new IllegalArgumentException("tile 长度错误: " + tile.length);
            }
        }
        try {
            Files.createDirectories(e.mapsDir);
            org.bukkit.Location spawn = world.getSpawnLocation();
            int centerX = spawn.getBlockX() * 8;
            int centerZ = spawn.getBlockZ() * 8;
            String dimension = world.getKey().toString();
            for (int i = 0; i < ids.size(); i++) {
                MapDataFile.write(e.mapsDir.resolve(ids.get(i) + ".dat"), dimension, centerX, centerZ, tiles.get(i));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("覆写地图文件失败: " + ex.getMessage(), ex);
        }
    }

    /** 返回 null 表示世界尚未加载（极少见，调用方按空处理） */
    public @Nullable TreeStore treeOf(@Nullable World world) {
        if (world == null) {
            return null;
        }
        try {
            return entry(world).tree;
        } catch (IllegalStateException e) {
            plugin.getLogger().severe(e.getMessage());
            return null;
        }
    }

    /** 世界目录树；世界缺失/加载失败返回空 Optional */
    public Optional<TreeStore> treeIfLoaded(World world) {
        return Optional.ofNullable(treeOf(world));
    }

    /** 保存像素（gzip），供 Web 预览与重启后重建 map 状态 */
    public void writePixels(World world, int pid, byte[] pixels) {
        Path file = entry(world).pixelsDir.resolve(pid + ".bin");
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(file))) {
                os.write(pixels);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存像素文件失败: " + file, e);
        }
    }

    public @Nullable byte[] readPixels(World world, int pid) {
        Path file = entry(world).pixelsDir.resolve(pid + ".bin");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream is = new GZIPInputStream(Files.newInputStream(file))) {
            return is.readAllBytes();
        } catch (IOException e) {
            plugin.getLogger().warning("读取像素文件失败: " + file);
            return null;
        }
    }

    /** 删除节点并清理其全部像素文件（画或目录递归）；返回被删的 pid 列表 */
    public List<Integer> delete(World world, String path) throws TreeException {
        Entry e = entry(world);
        List<Integer> pids = e.tree.deleteNode(path);
        for (int pid : pids) {
            try {
                Files.deleteIfExists(e.pixelsDir.resolve(pid + ".bin"));
            } catch (IOException ex) {
                plugin.getLogger().warning("删除像素文件失败 pid=" + pid);
            }
        }
        return pids;
    }

    /** 世界已加载与否的目录树缓存清理（世界卸载时调用） */
    public void invalidate(World world) {
        entries.remove(levelFolder(world));
    }

    /**
     * 26.2(Moonrise) 的 World#getWorldFolder 返回维度目录
     * （world/dimensions/minecraft/overworld），而地图状态在 level 根
     * （world/data/minecraft/maps）。逐级向上定位含 level.dat 的 level 目录。
     */
    private static Path levelFolder(World world) {
        Path dir = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        while (dir != null && !Files.isRegularFile(dir.resolve("level.dat"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("无法定位世界存档目录: " + world.getName());
        }
        return dir;
    }

    public void saveAll() {
        for (Entry e : entries.values()) {
            try {
                e.tree.save();
            } catch (IllegalStateException ex) {
                plugin.getLogger().severe(ex.getMessage());
            }
        }
    }

    /** 便捷：遍历 world 内树节点（测试/管理用） */
    public List<TreeNode> paintingList(World world) {
        return entry(world).tree.allPaintings();
    }
}
