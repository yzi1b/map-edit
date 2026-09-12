package cn.lyricraft.mapedit;

import cn.lyricraft.mapedit.deploy.DeployManager;
import cn.lyricraft.mapedit.storage.PreferenceStore;
import cn.lyricraft.mapedit.storage.TreeData.TreeNode;
import cn.lyricraft.mapedit.storage.TreeStore;
import cn.lyricraft.mapedit.storage.TreeStore.TreeException;
import cn.lyricraft.mapedit.storage.WorldStore;
import cn.lyricraft.mapedit.web.WebServer;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * /mapedit 根命令(Brigadier,Paper 生命周期 COMMANDS 阶段注册)。
 * 路径语法:不带先导 /,目录以尾部 / 表示;补全时目录自动带 /。
 */
public final class MapEditCommand {

    public static final String PERM_WEB = "mapedit.web";
    public static final String PERM_GIVE = "mapedit.give";
    public static final String PERM_DEPLOY = "mapedit.deploy";
    public static final String PERM_PREFER = "mapedit.prefer";

    public static final List<Permission> PERMISSIONS = List.of(
            new Permission(PERM_WEB, "生成 Web 访问 token", PermissionDefault.OP),
            new Permission(PERM_GIVE, "使用 /mapedit give 获取地图画地图", PermissionDefault.OP),
            new Permission(PERM_DEPLOY, "使用 /mapedit deploy 部署地图画", PermissionDefault.OP),
            new Permission(PERM_PREFER, "设置 /mapedit prefer 部署偏好", PermissionDefault.OP));

    private final WorldStore stores;
    private final WebServer web;
    private final DeployManager deployManager;
    private final PreferenceStore prefs;

    public MapEditCommand(WorldStore stores, WebServer web, DeployManager deployManager,
                          PreferenceStore prefs) {
        this.stores = stores;
        this.web = web;
        this.deployManager = deployManager;
        this.prefs = prefs;
    }

    /** 供 MapEdit#onEnable 注册权限节点 */
    public static void registerPermissions() {
        for (Permission permission : PERMISSIONS) {
            if (Bukkit.getPluginManager().getPermission(permission.getName()) == null) {
                Bukkit.getPluginManager().addPermission(permission);
            }
        }
    }

    public LiteralCommandNode<CommandSourceStack> rootNode() {
        var webLiteral = Commands.literal("web")
                .requires(src -> src.getSender().hasPermission(PERM_WEB))
                .executes(ctx -> {
                    web(ctx.getSource().getSender());
                    return 1;
                });
        var giveLiteral = Commands.literal("give")
                .requires(src -> src.getSender().hasPermission(PERM_GIVE))
                .then(pathArgument(ctx -> {
                    give(ctx.getSource().getSender(), ctx.getArgument("path", String.class));
                    return 1;
                }));
        var deployLiteral = Commands.literal("deploy")
                .requires(src -> src.getSender().hasPermission(PERM_DEPLOY))
                .then(pathArgument(ctx -> {
                    deploy(ctx.getSource().getSender(), ctx.getArgument("path", String.class));
                    return 1;
                }));
        // prefer 为「属性 值」成对的参数链(最多 ATTRS.length 对);每个位置是独立参数,
        // 属性位提示属性名、值位提示 true/false(Brigadier 对整段参数的替换问题在 word 参数下不存在)。
        var preferLiteral = Commands.literal("prefer")
                .requires(src -> src.getSender().hasPermission(PERM_PREFER))
                .executes(ctx -> {
                    reportPrefs(ctx.getSource().getSender());
                    return 1;
                })
                .then(preferPair(0));
        // check 为独立偏好(依附玩家,但非 prefer 属性):部署前是否执行附着/占位检查
        var checkLiteral = Commands.literal("check")
                .requires(src -> src.getSender().hasPermission(PERM_PREFER))
                .executes(ctx -> {
                    if (ctx.getSource().getSender() instanceof Player player) {
                        boolean on = prefs.of(player.getUniqueId()).check;
                        player.sendMessage(Component.text("部署附着检查: " + (on ? "开" : "关")
                                + (on ? "" : "（部署时将跳过有效附着与占位检查）"), NamedTextColor.GREEN));
                    }
                    return 1;
                })
                .then(Commands.argument("value", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            for (String cand : new String[] {"true", "false"}) {
                                if (cand.startsWith(builder.getRemaining())) {
                                    builder.suggest(cand);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            CommandSender sender = ctx.getSource().getSender();
                            if (!(sender instanceof Player player)) {
                                sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
                                return 1;
                            }
                            String v = ctx.getArgument("value", String.class);
                            if (v.equalsIgnoreCase("true")) {
                                prefs.setCheck(player.getUniqueId(), true);
                                player.sendMessage(Component.text("部署附着检查已开启", NamedTextColor.GREEN));
                            } else if (v.equalsIgnoreCase("false")) {
                                prefs.setCheck(player.getUniqueId(), false);
                                player.sendMessage(Component.text("部署附着检查已关闭（部署时将跳过有效附着与占位检查）",
                                        NamedTextColor.GREEN));
                            } else {
                                player.sendMessage(Component.text("用法: /mapedit check true|false", NamedTextColor.RED));
                            }
                            return 1;
                        }));

        return Commands.literal("mapedit")
                .then(webLiteral)
                .then(giveLiteral)
                .then(deployLiteral)
                .then(preferLiteral)
                .then(checkLiteral)
                .executes(ctx -> {
                    ctx.getSource().getSender().sendMessage(Component.text(
                            "用法: /mapedit <web|give <路径>|deploy <路径>|prefer [属性 值]...|check true|false>"
                                    + "（路径不带先导 /，目录以 / 结尾）",
                            NamedTextColor.GRAY));
                    return 1;
                })
                .build();
    }

    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> pathArgument(
            com.mojang.brigadier.Command<CommandSourceStack> executor) {
        return Commands.argument("path", MapPathArgument.path()).suggests(this::suggestPaths).executes(executor);
    }

    private void web(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
            return;
        }
        String token = web.tokens().issue(player.getUniqueId());
        String url = web.urlFor(token);
        player.sendMessage(Component.text("点击打开地图画 Web: ", NamedTextColor.GREEN)
                .append(Component.text(url, NamedTextColor.AQUA)
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(url))));
    }

    private void give(CommandSender sender, String path) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
            return;
        }
        var tree = stores.treeOf(player.getWorld());
        if (tree == null) {
            sender.sendMessage(Component.text("世界目录树不可用", NamedTextColor.RED));
            return;
        }
        try {
            TreeNode painting = tree.resolve(path);
            if (!painting.isPainting()) {
                sender.sendMessage(Component.text("不是地图画: " + path, NamedTextColor.RED));
                return;
            }
            int cols = painting.cols;
            int rows = painting.rows;
            int given = 0;
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int mapId = painting.mapIds.get(r).get(c);
                    MapView view = Bukkit.getMap(mapId);
                    if (view == null) {
                        sender.sendMessage(Component.text("地图数据缺失 (id=" + mapId + ")，可能存档不完整",
                                NamedTextColor.RED));
                        continue;
                    }
                    ItemStack item = mapItem(view);
                    if (player.getInventory().firstEmpty() == -1) {
                        player.getWorld().dropItem(player.getLocation(), item);
                        player.sendMessage(Component.text("背包已满，部分地图掉落在地", NamedTextColor.YELLOW));
                    } else {
                        player.getInventory().addItem(item);
                    }
                    given++;
                }
            }
            player.sendMessage(Component.text("已给予地图画 " + path + " (" + cols + "×" + rows + ") 共 " + given
                    + " 张地图，左上角为第一张", NamedTextColor.GREEN));
        } catch (TreeException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    private void deploy(CommandSender sender, String path) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
            return;
        }
        try {
            var tree = stores.treeOf(player.getWorld());
            if (tree == null) {
                sender.sendMessage(Component.text("世界目录树不可用", NamedTextColor.RED));
                return;
            }
            TreeNode painting = tree.resolve(path);
            if (!painting.isPainting()) {
                sender.sendMessage(Component.text("不是地图画: " + path, NamedTextColor.RED));
                return;
            }
            ItemStack item = deployManager.deployItem(painting, path);
            player.getInventory().addItem(item);
            player.sendMessage(Component.text("已给你「" + painting.name + "」(" + painting.cols + "×"
                    + painting.rows + ") 的起始地图。把它放进左上角的展示框，其余地图会自动铺好", NamedTextColor.GREEN));
        } catch (TreeException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
    }

    /** 属性/值参数链:attr_i → value_i → (attr_{i+1} …);每对 value 处可回车执行 */
    private com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> preferPair(int depth) {
        var attr = Commands.argument("attr" + depth, StringArgumentType.word())
                .suggests(this::suggestPrefAttrs)
                .executes(ctx -> {
                    preferUsage(ctx.getSource().getSender());
                    return 1;
                });
        var value = Commands.argument("value" + depth, StringArgumentType.word())
                .suggests((ctx, builder) -> {
                    for (String cand : new String[] {"true", "false"}) {
                        if (cand.startsWith(builder.getRemaining())) {
                            builder.suggest(cand);
                        }
                    }
                    return builder.buildFuture();
                });
        var chain = value;
        if (depth + 1 < PreferenceStore.ATTRS.length) {
            chain = chain.then(preferPair(depth + 1));
        }
        var node = chain.executes(ctx -> applyPrefs(ctx, depth));
        return attr.then(node);
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPrefAttrs(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (String cand : PreferenceStore.ATTRS) {
            if (cand.startsWith(builder.getRemaining())) {
                builder.suggest(cand);
            }
        }
        return builder.buildFuture();
    }

    private int applyPrefs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, int upTo) {
        CommandSender sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
            return 1;
        }
        for (int i = 0; i <= upTo; i++) {
            String attr = ctx.getArgument("attr" + i, String.class);
            String valueText = ctx.getArgument("value" + i, String.class);
            boolean value;
            if (valueText.equalsIgnoreCase("true")) {
                value = true;
            } else if (valueText.equalsIgnoreCase("false")) {
                value = false;
            } else {
                preferUsage(player);
                return 1;
            }
            if (!prefs.set(player.getUniqueId(), attr, value)) {
                player.sendMessage(Component.text(
                        "未知属性: " + attr + "（可用: glow / invisible / reference / force）", NamedTextColor.RED));
                return 1;
            }
        }
        player.sendMessage(Component.text("你的地图部署偏好已更新", NamedTextColor.GREEN));
        return 1;
    }

    private static void preferUsage(CommandSender sender) {
        sender.sendMessage(Component.text(
                "用法: /mapedit prefer <属性> <true|false> ...（属性: glow / invisible / reference / force）",
                NamedTextColor.RED));
    }

    private void reportPrefs(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该指令仅玩家可用", NamedTextColor.RED));
            return;
        }
        PreferenceStore.Prefs p = prefs.of(player.getUniqueId());
        player.sendMessage(Component.text("你的地图部署偏好: "
                + "荧光展示框 " + (p.glow ? "是" : "否") + "，"
                + "隐形展示框 " + (p.invisible ? "是" : "否") + "，"
                + "右键展示框时类型跟随 " + (p.reference ? "是" : "否") + "，"
                + "强制替换已有展示框 " + (p.force ? "是" : "否"), NamedTextColor.GREEN));
    }

    private static ItemStack mapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        return item;
    }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPaths(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int slash = typed.lastIndexOf('/');
        String dir = slash <= 0 ? "" : typed.substring(0, slash + 1);
        String partial = slash < 0 ? typed : typed.substring(slash + 1);
        TreeStore tree = null;
        if (context.getSource().getSender() instanceof Player pl) {
            tree = stores.treeOf(pl.getWorld());
        }
        if (tree == null) {
            return builder.buildFuture();
        }
        try {
            TreeNode dirNode = tree.resolve(dir.isEmpty() ? "" : dir);
            if (dirNode.isFolder()) {
                for (TreeNode child : dirNode.children) {
                    if (child.name.startsWith(partial)) {
                        builder.suggest(dir + child.name + (child.isFolder() ? "/" : ""));
                    }
                }
            }
        } catch (TreeException ignored) {
            // 目录不存在则无候选
        }
        return builder.buildFuture();
    }
}
