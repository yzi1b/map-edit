package cn.lyricraft.mapedit.map;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Random;

/**
 * 地图数据文件编码/写入（与存档 data/minecraft/maps/&lt;id&gt;.dat 的 NBT 结构一致）。
 * <p>
 * 实测结论（26.2）：地图必须“文件先行”——createMap 产生的内存态地图不会被服务端向
 * 客户端下发颜色；只有从磁盘加载的 map 状态才会显示。因此本插件自分配高位 id
 * （远离原版内存分配器）并直接落文件，不触碰原版游标。
 */
public final class MapDataFile {

    public static final int BASE_ID = 1_000_000_000;
    private static final Random RANDOM = new Random();

    private MapDataFile() {
    }

    /** 生成 gzip 前的 NBT 字节（root compound: DataVersion + data compound） */
    public static byte[] encodeNbt(String dimension, int centerX, int centerZ, byte[] colors) {
        NbtWriter w = new NbtWriter();
        w.tagCompoundStart("");
        w.tagInt("DataVersion", 4903);
        w.tagCompoundStart("data");
        w.tagByte("locked", 1);
        w.tagInt("xCenter", centerX);
        w.tagLong("UUIDMost", RANDOM.nextLong());
        w.tagLong("UUIDLeast", RANDOM.nextLong());
        w.tagString("dimension", dimension);
        w.tagByte("trackingPosition", 0);
        w.tagInt("zCenter", centerZ);
        w.tagByte("scale", 3);
        w.tagByteArray("colors", colors);
        w.tagEnd();
        w.tagEnd();
        return w.toBytes();
    }

    /** 原子写入地图数据文件 */
    public static void write(Path file, String dimension, int centerX, int centerZ, byte[] colors)
            throws IOException {
        byte[] raw = encodeNbt(dimension, centerX, centerZ, colors);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (var os = new java.util.zip.GZIPOutputStream(Files.newOutputStream(tmp))) {
            os.write(raw);
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static final class NbtWriter {

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1024 + 16384);

        byte[] toBytes() {
            return buf.toByteArray();
        }

        void tagByte(String name, int v) {
            buf.write(1);
            putName(name);
            buf.write(v);
        }

        void tagInt(String name, int v) {
            buf.write(3);
            putName(name);
            putInt(v);
        }

        void tagLong(String name, long v) {
            buf.write(4);
            putName(name);
            for (int i = 7; i >= 0; i--) {
                buf.write((int) (v >> (i * 8)));
            }
        }

        void tagString(String name, String s) {
            buf.write(8);
            putName(name);
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            buf.write(b.length >> 8);
            buf.write(b.length);
            buf.writeBytes(b);
        }

        void tagByteArray(String name, byte[] b) {
            buf.write(7);
            putName(name);
            putInt(b.length);
            buf.writeBytes(b);
        }

        void tagCompoundStart(String name) {
            buf.write(10);
            putName(name);
        }

        void tagEnd() {
            buf.write(0);
        }

        private void putName(String name) {
            byte[] b = name.getBytes(StandardCharsets.UTF_8);
            buf.write(b.length >> 8);
            buf.write(b.length);
            buf.writeBytes(b);
        }

        private void putInt(int v) {
            buf.write(v >> 24);
            buf.write(v >> 16);
            buf.write(v >> 8);
            buf.write(v);
        }
    }
}
