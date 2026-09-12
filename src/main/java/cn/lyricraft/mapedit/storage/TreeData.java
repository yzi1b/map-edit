package cn.lyricraft.mapedit.storage;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

public final class TreeData {

    public static final String TYPE_FOLDER = "folder";
    public static final String TYPE_PAINTING = "painting";

    public int version = 2;
    public int nextPid = 1;
    public TreeNode root = TreeNode.folder("");

    public static final class TreeNode {

        @SerializedName("type")
        public String type;

        @SerializedName("name")
        public String name;

        @SerializedName("children")
        public List<TreeNode> children;

        /** 地图画像素文件 id（每树唯一，非 map id） */
        @SerializedName("pid")
        public int pid;

        @SerializedName("cols")
        public int cols;

        @SerializedName("rows")
        public int rows;

        /** 行优先：mapIds.get(row).get(col)，左上角 [0][0] */
        @SerializedName("mapIds")
        public List<List<Integer>> mapIds;

        @SerializedName("createdAt")
        public long createdAt;

        public static TreeNode folder(String name) {
            TreeNode n = new TreeNode();
            n.type = TYPE_FOLDER;
            n.name = name;
            n.children = new ArrayList<>();
            return n;
        }

        public boolean isFolder() {
            return TYPE_FOLDER.equals(type);
        }

        public boolean isPainting() {
            return TYPE_PAINTING.equals(type);
        }
    }
}
