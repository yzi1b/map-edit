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

    public int webTokenTtlSeconds() {
        return raw().getInt("web.token-ttl-seconds", 600);
    }
}
