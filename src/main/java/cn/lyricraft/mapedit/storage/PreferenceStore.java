package cn.lyricraft.mapedit.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 玩家部署偏好(服务器级,plugins/MapEdit/prefs.json)。
 * /mapedit prefer 属性:glow(荧光)/ invisible(隐形)/ reference(右键展示框时类型跟随该帧)/
 * force(强制替换全部帧),默认 glow=false, invisible=false, reference=true, force=true。
 * 独立偏好 check(/mapedit check,默认 true):部署前是否执行有效附着与占位检查。
 * 读写均在主线程(指令与事件),方法加同步以防误用。
 */
public final class PreferenceStore {

    public static final String ATTR_GLOW = "glow";
    public static final String ATTR_INVISIBLE = "invisible";
    public static final String ATTR_REFERENCE = "reference";
    public static final String ATTR_FORCE = "force";
    public static final String[] ATTRS = {ATTR_GLOW, ATTR_INVISIBLE, ATTR_REFERENCE, ATTR_FORCE};
    /** check 为独立命令/偏好,不进 prefer 链 */
    public static final String ATTR_CHECK = "check";

    /** 展示框偏好;reference/force 默认 true,其余 false */
    public static final class Prefs {
        public boolean glow;
        public boolean invisible;
        public boolean reference = true;
        public boolean force = true;
        public boolean check = true;

        Prefs() {
        }

        public static Prefs defaults() {
            return new Prefs();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final JavaPlugin plugin;
    private final Map<UUID, Prefs> prefs = new HashMap<>();

    public PreferenceStore(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public synchronized Prefs of(UUID uuid) {
        Prefs p = prefs.get(uuid);
        if (p == null) {
            p = Prefs.defaults();
            prefs.put(uuid, p);
        }
        return p;
    }

    /** 属性名非法返回 false(调用方提示用法) */
    public synchronized boolean set(UUID uuid, String attr, boolean value) {
        for (String known : ATTRS) {
            if (known.equals(attr)) {
                Prefs p = of(uuid);
                switch (attr) {
                    case ATTR_GLOW -> p.glow = value;
                    case ATTR_INVISIBLE -> p.invisible = value;
                    case ATTR_REFERENCE -> p.reference = value;
                    case ATTR_FORCE -> p.force = value;
                    default -> {
                    }
                }
                save();
                return true;
            }
        }
        return false;
    }

    /** /mapedit check:独立偏好(不进 prefer 链) */
    public synchronized void setCheck(UUID uuid, boolean value) {
        of(uuid).check = value;
        save();
    }

    private void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // 手动解析:旧数据缺字段时补默认(Gson 经 Unsafe 建实例会跳过字段初始值,不能依赖类内默认)
            com.google.gson.JsonObject root = GSON.fromJson(r, com.google.gson.JsonObject.class);
            if (root == null) {
                return;
            }
            for (var e : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    com.google.gson.JsonObject p = e.getValue().getAsJsonObject();
                    Prefs pref = new Prefs();
                    pref.glow = boolOf(p, ATTR_GLOW, false);
                    pref.invisible = boolOf(p, ATTR_INVISIBLE, false);
                    pref.reference = boolOf(p, ATTR_REFERENCE, true);
                    pref.force = boolOf(p, ATTR_FORCE, true);
                    pref.check = boolOf(p, ATTR_CHECK, true);
                    prefs.put(uuid, pref);
                } catch (IllegalArgumentException | IllegalStateException ignore) {
                    // 损坏条目跳过
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("读取偏好文件失败: " + e.getMessage());
        }
    }

    private static boolean boolOf(com.google.gson.JsonObject p, String attr, boolean dflt) {
        return p.has(attr) ? p.get(attr).getAsBoolean() : dflt;
    }

    private void save() {
        Map<String, Prefs> flat = new HashMap<>();
        prefs.forEach((uuid, p) -> flat.put(uuid.toString(), p));
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(flat), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            plugin.getLogger().warning("保存偏好失败: " + e.getMessage());
        }
    }

    private Path file() {
        return plugin.getDataFolder().toPath().resolve("prefs.json");
    }
}
