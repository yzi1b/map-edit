package cn.lyricraft.mapedit;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class MapEditConfig {

    private final JavaPlugin plugin;

    public MapEditConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
    }

    public FileConfiguration raw() {
        return plugin.getConfig();
    }

    public int webPort() {
        return raw().getInt("web.port", 35565);
    }

    public String webUri() {
        return raw().getString("web.uri", "http://localhost:35565/");
    }

    public String webBind() {
        return raw().getString("web.bind", "127.0.0.1");
    }

    /**
     * 访问路径前缀（如 /mapedit，默认空=根路径）。用于把 Web 服务挂到子路径：
     * 既支持直连 http://host:port/mapedit/，也支持反向代理原样透传。
     * 归一化：空/"/" → 空串；去首尾空白与尾部斜杠；缺前导斜杠补上。
     */
    public String webPath() {
        String p = raw().getString("web.path", "");
        p = p == null ? "" : p.trim();
        if (p.equals("/")) {
            return "";
        }
        if (!p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public int webTokenTtlSeconds() {
        return raw().getInt("web.token-ttl-seconds", 600);
    }
}
