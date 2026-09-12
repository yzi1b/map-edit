package cn.lyricraft.mapedit.storage;

import cn.lyricraft.mapedit.storage.TreeData.TreeNode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 地图画目录树存储。单实例，所有操作同步（Web/主线程并发安全由调用方保证单实例即可）。
 */
public final class TreeStore {

    public static class TreeException extends Exception {
        public TreeException(String message) {
            super(message);
        }
    }

    public static final class NotFoundException extends TreeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static final class BadNameException extends TreeException {
        public BadNameException(String message) {
            super(message);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private TreeData data;

    public TreeStore(Path file) {
        this.file = file;
        this.data = new TreeData();
    }

    public synchronized void load() throws IOException {
        if (Files.isRegularFile(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(r, TreeData.class);
            }
            if (data == null || data.root == null || data.root.children == null) {
                throw new IOException("tree.json 内容损坏");
            }
            if (data.nextPid < 1) {
                data.nextPid = 1;
            }
        } else {
            save();
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("保存目录树失败: " + file, e);
        }
    }

    /** 按 /a/b/名字 逐段解析；非法/不存在抛 NotFoundException */
    public synchronized TreeNode resolve(String path) throws NotFoundException {
        List<String> segments = split(path);
        TreeNode node = data.root;
        for (String seg : segments) {
            node = childByName(node, seg);
            if (node == null) {
                throw new NotFoundException("未找到: " + path);
            }
        }
        return node;
    }

    /** 目录 children 快照（含目录与地图画） */
    public synchronized List<TreeNode> childrenOf(String dirPath) throws NotFoundException {
        TreeNode node = resolve(dirPath);
        if (!node.isFolder()) {
            throw new NotFoundException("不是目录: " + dirPath);
        }
        return new ArrayList<>(node.children);
    }

    public synchronized TreeNode createFolder(String parentPath, String name) throws TreeException {
        validateName(name);
        TreeNode parent = resolve(parentPath);
        if (!parent.isFolder()) {
            throw new NotFoundException("不是目录: " + parentPath);
        }
        if (childByName(parent, name) != null) {
            throw new BadNameException("同级已存在: " + name);
        }
        TreeNode folder = TreeNode.folder(name);
        parent.children.add(folder);
        save();
        return folder;
    }

    /** 删除节点（画或目录）；目录连同子树一并删除，返回子树中全部被删地图画的 pid */
    public synchronized List<Integer> deleteNode(String path) throws TreeException {
        int i = path.lastIndexOf('/');
        String parentPath = i <= 0 ? "" : path.substring(0, i);
        String name = i <= 0 ? path : path.substring(i + 1);
        TreeNode parent = resolve(parentPath);
        if (!parent.isFolder()) {
            throw new NotFoundException("不是目录: " + parentPath);
        }
        TreeNode removed = null;
        for (TreeNode c : parent.children) {
            if (c.name.equals(name)) {
                removed = c;
                break;
            }
        }
        if (removed == null) {
            throw new NotFoundException("未找到: " + path);
        }
        parent.children.remove(removed);
        List<Integer> pids = new ArrayList<>();
        collectPids(removed, pids);
        save();
        return pids;
    }

    /** 重命名任意节点(画或目录);名字校验与同级唯一同新建 */
    public synchronized TreeNode rename(String path, String newName) throws TreeException {
        validateName(newName);
        int i = path.lastIndexOf('/');
        String parentPath = i <= 0 ? "" : path.substring(0, i);
        String oldName = i <= 0 ? path : path.substring(i + 1);
        if (newName.equals(oldName)) {
            return resolve(path);
        }
        TreeNode parent = resolve(parentPath);
        if (!parent.isFolder()) {
            throw new NotFoundException("不是目录: " + parentPath);
        }
        TreeNode node = null;
        for (TreeNode c : parent.children) {
            if (c.name.equals(oldName)) {
                node = c;
                break;
            }
        }
        if (node == null) {
            throw new NotFoundException("未找到: " + path);
        }
        if (childByName(parent, newName) != null) {
            throw new BadNameException("同级已存在: " + newName);
        }
        node.name = newName;
        save();
        return node;
    }

    private static void collectPids(TreeNode node, List<Integer> out) {
        if (node.isPainting()) {
            out.add(node.pid);
            return;
        }
        for (TreeNode c : node.children) {
            collectPids(c, out);
        }
    }

    public synchronized TreeNode createPainting(String parentPath, String name,
                                                int cols, int rows, List<List<Integer>> mapIds) throws TreeException {
        validateName(name);
        TreeNode parent = resolve(parentPath);
        if (!parent.isFolder()) {
            throw new NotFoundException("不是目录: " + parentPath);
        }
        if (childByName(parent, name) != null) {
            throw new BadNameException("同级已存在: " + name);
        }
        TreeNode painting = new TreeNode();
        painting.type = TreeData.TYPE_PAINTING;
        painting.name = name;
        painting.pid = data.nextPid++;
        painting.cols = cols;
        painting.rows = rows;
        painting.mapIds = mapIds;
        painting.createdAt = System.currentTimeMillis();
        parent.children.add(painting);
        save();
        return painting;
    }

    private static TreeNode childByName(TreeNode folder, String name) {
        for (TreeNode c : folder.children) {
            if (c.name.equals(name)) {
                return c;
            }
        }
        return null;
    }

    /* 注:替换图片已改为“同 id 覆写”(WorldStore.overwriteMapTiles),不再改写 mapIds */

    /** 全部地图画（含目录下的），行遍历用 */
    public synchronized List<TreeNode> allPaintings() {
        List<TreeNode> out = new ArrayList<>();
        collectPaintings(data.root, out);
        return out;
    }

    private static void collectPaintings(TreeNode node, List<TreeNode> out) {
        if (node.isPainting()) {
            out.add(node);
            return;
        }
        for (TreeNode c : node.children) {
            collectPaintings(c, out);
        }
    }

    /** 校验文件名：非空、≤64 字符、禁控制字符与分隔符（中英及常见符号均可） */
    static void validateName(String name) throws BadNameException {
        if (name == null || name.isEmpty()) {
            throw new BadNameException("名称不能为空");
        }
        if (name.length() > 64) {
            throw new BadNameException("名称过长（≤64 字符）");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == 0 || Character.isISOControl(c)) {
                throw new BadNameException("名称包含非法字符: " + name);
            }
        }
    }

    /** '/a/b' -> [a,b]；空/仅斜杠 -> [] */
    static List<String> split(String path) {
        List<String> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
