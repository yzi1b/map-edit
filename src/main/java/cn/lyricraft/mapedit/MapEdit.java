package cn.lyricraft.mapedit;

import cn.lyricraft.mapedit.deploy.DeployManager;
import cn.lyricraft.mapedit.storage.PreferenceStore;
import cn.lyricraft.mapedit.storage.WorldStore;
import cn.lyricraft.mapedit.web.WebServer;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

public final class MapEdit extends JavaPlugin {

    private WorldStore stores;
    private WebServer web;

    @Override
    public void onEnable() {
        MapEditConfig config = new MapEditConfig(this);
        stores = new WorldStore(this);
        web = new WebServer(this, config, stores);
        PreferenceStore prefs = new PreferenceStore(this);
        DeployManager deployManager = new DeployManager(this, stores, prefs);
        getServer().getPluginManager().registerEvents(deployManager, this);

        MapEditCommand.registerPermissions();
        MapEditCommand command = new MapEditCommand(stores, web, deployManager, prefs);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(command.rootNode()));
        if (!web.start()) {
            getLogger().warning("Web 服务启动失败（/mapedit web 将不可用）");
        }

        getLogger().info("MapEdit enabled.");
    }

    @Override
    public void onDisable() {
        if (stores != null) {
            stores.saveAll();
        }
        if (web != null) {
            web.stop();
        }
        getLogger().info("MapEdit disabled.");
    }
}
