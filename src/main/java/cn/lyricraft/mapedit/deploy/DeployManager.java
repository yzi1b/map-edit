package cn.lyricraft.mapedit.deploy;

import cn.lyricraft.mapedit.MapEditCommand;
import cn.lyricraft.mapedit.storage.PreferenceStore;
import cn.lyricraft.mapedit.storage.TreeData.TreeNode;
import cn.lyricraft.mapedit.storage.TreeStore.TreeException;
import cn.lyricraft.mapedit.storage.WorldStore;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * 部署：手持“地图画道具”（左上角地图 + PDC）可有两种用法——
 * 1) 右键展示框 / 2) 右键方块(以点击面为左上)。
 * 帧类型由 /mapedit prefer 决定(reference/force/glow/invisible):
 * 类型来源——右键展示框且 reference=true 时跟随该帧(荧光+隐形),否则一律用设置值;
 * force=true 强制每格清掉重建为来源类型,force=false 时已有帧格保留其类型(内容仍覆盖)。
 * 道具不消耗可反复使用；失败整体回滚。
 * <p>
 * 几何：墙面帧地图默认北朝上（Rotation.NONE）；地板/天花板按玩家朝向旋转帧。
 */
public final class DeployManager implements Listener {

    private final Plugin plugin;
    private final WorldStore stores;
    private final PreferenceStore prefs;
    private final NamespacedKey keyPath;

    public DeployManager(Plugin plugin, WorldStore stores, PreferenceStore prefs) {
        this.plugin = plugin;
        this.stores = stores;
        this.prefs = prefs;
        this.keyPath = new NamespacedKey(plugin, "deploy_path");
    }

    /** 起始地图 = 左上角地图 + 名称/lore + PDC(路径标记) */
    public ItemStack deployItem(TreeNode painting, String path) {
        MapView view = Bukkit.getMap(painting.mapIds.get(0).get(0));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (view != null) {
            meta.setMapView(view);
        }
        meta.displayName(Component.text("地图画: " + painting.name).color(NamedTextColor.GOLD)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("地图画部署").color(TextColor.color(0xFFA500))
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("尺寸 " + painting.cols + "×" + painting.rows
                        + "，共 " + (painting.cols * painting.rows) + " 张地图").color(NamedTextColor.GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text("把它放进左上角的展示框，其余地图会自动铺好").color(NamedTextColor.GREEN)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false),
                Component.text(path).color(NamedTextColor.DARK_GRAY)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(keyPath, PersistentDataType.STRING, path);
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isDeployItem(ItemStack item, NamespacedKey key) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof ItemFrame frame)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack inHand = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isDeployItem(inHand, keyPath)) {
            return;
        }
        event.setCancelled(true); // 拦截原版放入
        if (!player.hasPermission(MapEditCommand.PERM_DEPLOY)) {
            player.sendMessage(Component.text("你没有部署地图画的权限", NamedTextColor.RED));
            return;
        }
        String path = inHand.getItemMeta().getPersistentDataContainer().get(keyPath, PersistentDataType.STRING);
        if (path == null) {
            return;
        }
        try {
            deploy(player, frame, path);
        } catch (RuntimeException e) {
            player.sendMessage(Component.text("部署失败: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    /** 右键展示框:reference=true 时新建/替换帧跟随该帧类型(荧光+隐形),false 则用设置值;force 决定是否强制全换 */
    private void deploy(Player player, ItemFrame anchor, String path) {
        World world = anchor.getWorld();
        TreeNode painting = resolveOrWarn(player, world, path);
        if (painting == null) {
            return;
        }
        // 地图与目录树按 level(存档)隔离,同存档维度共享 → resolve 即证明同存档,无需维度绑定
        PreferenceStore.Prefs p = prefs.of(player.getUniqueId());
        boolean glow = p.reference ? anchor instanceof GlowItemFrame : p.glow;
        boolean invisible = p.reference ? anchor.isInvisible() : p.invisible;
        Geometry g = Geometry.forSurface(anchor.getFacing(), player);
        Block supportAnchor = anchor.getLocation().getBlock()
                .getRelative(anchor.getFacing().getOppositeFace());
        List<Cell> cells = cellsOf(supportAnchor, g, painting.rows, painting.cols);
        if (p.check && !precheck(player, g, cells, !p.force)) {
            return;
        }
        fillCells(player, painting, g, cells, glow, invisible, p.force, path);
    }

    /** 右键方块:以点击面为左上铺网格;类型一律取设置值(reference 对此入口无效),是否强制替换看 force */
    @EventHandler(ignoreCancelled = true)
    public void onBlockClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack inHand = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (!isDeployItem(inHand, keyPath)) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission(MapEditCommand.PERM_DEPLOY)) {
            player.sendMessage(Component.text("你没有部署地图画的权限", NamedTextColor.RED));
            return;
        }
        String path = inHand.getItemMeta().getPersistentDataContainer().get(keyPath, PersistentDataType.STRING);
        if (path == null) {
            return;
        }
        Block block = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (block == null || face == null) {
            return;
        }
        try {
            deployOnBlock(player, block, face, path);
        } catch (RuntimeException e) {
            player.sendMessage(Component.text("部署失败: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    private void deployOnBlock(Player player, Block supportAnchor, BlockFace face, String path) {
        World world = supportAnchor.getWorld();
        TreeNode painting = resolveOrWarn(player, world, path);
        if (painting == null) {
            return;
        }
        PreferenceStore.Prefs p = prefs.of(player.getUniqueId());
        Geometry g = Geometry.forSurface(face, player);
        List<Cell> cells = cellsOf(supportAnchor, g, painting.rows, painting.cols);
        if (p.check && !precheck(player, g, cells, !p.force)) {
            return;
        }
        fillCells(player, painting, g, cells, p.glow, p.invisible, p.force, path);
    }


    /** 预检:skipFramed=true(force=false)跳过已有帧格(其保留不动,无需可放性);否则每格都须可放 */
    private static boolean precheck(Player player, Geometry g, List<Cell> cells, boolean skipFramed) {
        List<String> invalid = new ArrayList<>();
        for (Cell cell : cells) {
            if (skipFramed && cell.frame != null) {
                continue;
            }
            checkCellPlaceable(g, cell, invalid);
        }
        if (!invalid.isEmpty()) {
            player.sendMessage(Component.text(invalidMessage(invalid), NamedTextColor.RED));
            return false;
        }
        return true;
    }

    /**
     * 铺格核心(两入口共用):
     * force=true:每格清掉重建为 (glow,invisible);已有帧也移除(快照供回滚)。
     * force=false:已有帧格保留(内容仍覆盖为目标地图,类型不变);缺帧格按 (glow,invisible) 生成。
     */
    private void fillCells(Player player, TreeNode painting, Geometry g, List<Cell> cells,
                           boolean glow, boolean invisible, boolean force, String path) {
        int rows = painting.rows;
        int cols = painting.cols;
        List<ItemFrame> created = new ArrayList<>();
        List<Replaced> replaced = new ArrayList<>();
        try {
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    int mapId = painting.mapIds.get(r).get(c);
                    MapView view = Bukkit.getMap(mapId);
                    if (view == null) {
                        throw new IllegalStateException("地图数据缺失 (id=" + mapId + ")");
                    }
                    Cell cell = cells.get(r * cols + c);
                    ItemFrame old = cell.frame;
                    ItemFrame f;
                    if (old != null && !force) {
                        f = old;    // 保留已有帧及其类型
                    } else {
                        if (old != null) {
                            replaced.add(new Replaced(cell.support, cell.attach, old instanceof GlowItemFrame,
                                    old.getItem().clone(), old.getRotation(), old.isInvisible()));
                            old.remove();
                        }
                        f = spawnFrame(cell.support, cell.attach, glow);
                        if (f == null) {
                            throw new IllegalStateException("无法在 " + locText(cell.support) + " 放置展示框");
                        }
                        created.add(f);
                        f.setInvisible(invisible);
                    }
                    f.setItem(mapItem(view), false);
                    f.setRotation(g.rotation());
                }
            }
            player.sendMessage(Component.text("已部署地图画 " + path + "（" + cols + "×" + rows + "）",
                    NamedTextColor.GREEN));
        } catch (RuntimeException e) {
            for (ItemFrame f : created) {
                f.remove();
            }
            for (Replaced rp : replaced) {
                try {
                    ItemFrame f = spawnFrame(rp.support(), rp.attach(), rp.glow());
                    if (f != null) {
                        f.setInvisible(rp.invisible());
                        f.setItem(rp.item().clone(), false);
                        f.setRotation(rp.rot());
                    }
                } catch (RuntimeException ignore) {
                    // 回滚尽力而为
                }
            }
            for (Cell cell : cells) {
                ItemFrame f = cell.frame;
                if (f != null && f.isValid()) {
                    f.setItem(cell.snapshotItem == null ? null : cell.snapshotItem.clone(), false);
                    f.setRotation(cell.snapshotRot);
                }
            }
            player.sendMessage(Component.text("部署失败，已回滚: " + e.getMessage(), NamedTextColor.RED));
        }
    }

    /** 被移除重铺的原帧快照(回滚时按原种类/内容重建) */
    private record Replaced(Block support, BlockFace attach, boolean glow,
                            ItemStack item, Rotation rot, boolean invisible) {
    }

    private TreeNode resolveOrWarn(Player player, World world, String path) {
        var tree = stores.treeOf(world);
        if (tree == null) {
            player.sendMessage(Component.text("世界目录树不可用", NamedTextColor.RED));
            return null;
        }
        try {
            TreeNode painting = tree.resolve(path);
            if (!painting.isPainting()) {
                player.sendMessage(Component.text("不是地图画: " + path, NamedTextColor.RED));
                return null;
            }
            return painting;
        } catch (TreeException e) {
            player.sendMessage(Component.text("地图画不存在: " + path, NamedTextColor.RED));
            return null;
        }
    }

    private static List<Cell> cellsOf(Block supportAnchor, Geometry g, int rows, int cols) {
        List<Cell> cells = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Block support = supportAnchor.getRelative(g.rowDir(), r).getRelative(g.colDir(), c);
                cells.add(new Cell(support, g.attach()));
            }
        }
        return cells;
    }

    private static void checkCellPlaceable(Geometry g, Cell cell, List<String> invalid) {
        Block support = cell.support;
        if (!support.getType().isSolid()) {
            invalid.add(locText(support) + "(支撑非实心)");
            return;
        }
        Block front = support.getRelative(g.attach());
        if (front.getType().isOccluding() || front.getType() == Material.WATER
                || front.getType() == Material.LAVA) {
            invalid.add(locText(support) + "(前方被 " + front.getType().getKey().getKey() + " 占用)");
        }
    }

    /** 大图不合格格子可能成百上千,消息过长会刷屏 → 只列前若干,超出合并提示总数 */
    private static String invalidMessage(List<String> invalid) {
        final int maxShown = 8;
        StringBuilder msg = new StringBuilder("部署中止，以下位置无法放置展示框: ");
        int shown = Math.min(invalid.size(), maxShown);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                msg.append("; ");
            }
            msg.append(invalid.get(i));
        }
        if (invalid.size() > shown) {
            msg.append(" 等共 ").append(invalid.size()).append(" 处");
        }
        return msg.toString();
    }

    private static ItemStack mapItem(MapView view) {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        meta.setMapView(view);
        item.setItemMeta(meta);
        return item;
    }

    /** 一个网格格位：期望支撑格与朝向；frame=已存在且同朝向的帧（其余异向帧一律忽略） */
    private static final class Cell {
        final Block support;
        final BlockFace attach;
        final ItemFrame frame;
        final ItemStack snapshotItem;
        final Rotation snapshotRot;

        Cell(Block support, BlockFace attach) {
            this.support = support;
            this.attach = attach;
            this.frame = findFrameOn(support, attach);
            this.snapshotItem = frame == null ? null : frame.getItem().clone();
            this.snapshotRot = frame == null ? Rotation.NONE : frame.getRotation();
        }
    }

    /** 查找“附着在 support 的 attach 面”上的展示框（该格六个面只认朝向匹配的那个） */
    private static ItemFrame findFrameOn(Block support, BlockFace attach) {
        Location center = support.getLocation().add(0.5, 0.5, 0.5);
        for (Entity e : support.getWorld().getNearbyEntities(center, 0.6, 0.6, 0.6)) {
            if (e instanceof ItemFrame f && e.isValid() && f.getFacing() == attach) {
                // 确认其支撑格即本格（同朝向但隔格的帧在 0.6 半径外）
                Block theirSupport = e.getLocation().getBlock()
                        .getRelative(f.getFacing().getOppositeFace());
                if (theirSupport.equals(support)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** 在支撑格 attach 面的前方格（帧实际占据的空气格）中心生成，与手放帧同域 */
    private ItemFrame spawnFrame(Block support, BlockFace attach, boolean glow) {
        Class<? extends ItemFrame> type = glow ? GlowItemFrame.class : ItemFrame.class;
        Block front = support.getRelative(attach);
        Location loc = front.getLocation().add(0.5, 0.5, 0.5);
        ItemFrame f = support.getWorld().spawn(loc, type, e -> {
        });
        if (f != null && !f.setFacingDirection(attach, true)) {
            f.remove();
            return null;
        }
        ItemFrame placed = findFrameOn(support, attach);
        return placed != null ? placed : f;
    }

    private static String locText(Location loc) {
        return loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    private static String locText(Block b) {
        return b.getX() + "," + b.getY() + "," + b.getZ();
    }

    /**
     * 网格几何（罗盘显式语义）。attach：帧依附面；rowDir/colDir：格子推进方向；
     * rotation：每格统一帧旋转。
     */
    private record Geometry(BlockFace attach, BlockFace rowDir, BlockFace colDir, Rotation rotation) {

        /** attach = 帧依附面(墙=帧背面贴的墙面,地板=UP,天花板=DOWN);网格方向只看玩家朝向 */
        static Geometry forSurface(BlockFace attach, Player player) {
            switch (attach) {
                case NORTH, EAST, SOUTH, WEST -> {
                    // 墙：attach=依附面（其反向才是观看者视线方向）；行向下、列向观看者右侧
                    BlockFace wallLook = attach.getOppositeFace();
                    return new Geometry(attach, BlockFace.DOWN, rightOf(wallLook), Rotation.NONE);
                }
                case UP -> {
                    BlockFace look = cardinal(player.getFacing());
                    return new Geometry(attach, look.getOppositeFace(), rightOf(look), ROT_FLOOR[cardinalIndex(look)]);
                }
                case DOWN -> {
                    BlockFace look = cardinal(player.getFacing());
                    return new Geometry(attach, look, rightOf(look), ROT_CEILING[cardinalIndex(look)]);
                }
                default -> throw new IllegalStateException("无法识别的展示框方向: " + attach);
            }
        }
    }

    /** 观看者面向 f 时的右侧 */
    private static BlockFace rightOf(BlockFace f) {
        return switch (f) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            case NORTH_EAST -> BlockFace.SOUTH_EAST;
            case SOUTH_EAST -> BlockFace.SOUTH_WEST;
            case SOUTH_WEST -> BlockFace.NORTH_WEST;
            case NORTH_WEST -> BlockFace.NORTH_EAST;
            default -> throw new IllegalStateException("方向: " + f);
        };
    }

    private static final List<BlockFace> RING = List.of(BlockFace.NORTH, BlockFace.NORTH_EAST,
            BlockFace.EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH, BlockFace.SOUTH_WEST,
            BlockFace.WEST, BlockFace.NORTH_WEST);

    private static final List<Rotation> ROT_STEPS = List.of(Rotation.NONE, Rotation.CLOCKWISE_45,
            Rotation.CLOCKWISE, Rotation.CLOCKWISE_135, Rotation.FLIPPED, Rotation.FLIPPED_45,
            Rotation.COUNTER_CLOCKWISE, Rotation.COUNTER_CLOCKWISE_45);

    /** 实测校准表（2026-09-06）：帧内地图在各朝向下正确的旋转档位（北=0 起，每 45° 一档） */
    private static final Rotation[] ROT_FLOOR = {
            ROT_STEPS.get(0), // 北
            ROT_STEPS.get(0),
            ROT_STEPS.get(5), // 东
            ROT_STEPS.get(0),
            ROT_STEPS.get(6), // 南
            ROT_STEPS.get(0),
            ROT_STEPS.get(7), // 西
            ROT_STEPS.get(0),
    };
    private static final Rotation[] ROT_CEILING = {
            ROT_STEPS.get(0), // 北
            ROT_STEPS.get(0),
            ROT_STEPS.get(3), // 东
            ROT_STEPS.get(0),
            ROT_STEPS.get(6), // 南
            ROT_STEPS.get(0),
            ROT_STEPS.get(1), // 西
            ROT_STEPS.get(0),
    };

    /** 斜向吸附到最近的顺时针基本方位（NE→E、SE→S、SW→W、NW→N） */
    private static BlockFace cardinal(BlockFace f) {
        int i = RING.indexOf(f);
        if (i < 0) {
            return f;
        }
        int c = ((i + 1) / 2) * 2;
        return RING.get(c % 8);
    }

    private static int cardinalIndex(BlockFace f) {
        int i = RING.indexOf(f);
        return i < 0 ? 0 : i;
    }
}
