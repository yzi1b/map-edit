package cn.lyricraft.mapedit.map;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.bukkit.map.MapPalette;

/**
 * 地图画缩略图:像素表(每格 128×128 有符号地图色索引)区域平均降采样后编码 PNG。
 * byte 0..3 为透明槽;其余值经 MapPalette 映射 RGB;均值色再 matchColor 回到色板。
 */
public final class Thumbnail {

    public static final int TILE = 128;
    public static final int TILE_BYTES = TILE * TILE;
    public static final int DEFAULT_MAX_SIDE = 320;

    private Thumbnail() {
    }

    /** 返回 PNG 字节;像素长度不符抛 IllegalArgumentException,编码失败返回 null */
    public static byte[] encode(byte[] pixels, int cols, int rows, int maxSide) {
        if (cols < 1 || rows < 1 || (long) cols * rows * TILE_BYTES != pixels.length) {
            throw new IllegalArgumentException("像素长度不符: " + pixels.length);
        }
        int srcW = cols * TILE;
        int srcH = rows * TILE;
        int longSide = Math.max(srcW, srcH);
        int dstW = srcW;
        int dstH = srcH;
        if (longSide > maxSide) {
            dstW = Math.max(1, (int) Math.round(srcW * (double) maxSide / longSide));
            dstH = Math.max(1, (int) Math.round(srcH * (double) maxSide / longSide));
        }

        int[] argb = new int[256];
        for (int i = 0; i < 256; i++) {
            if (i <= 3) {
                argb[i] = 0; // 透明槽
            } else {
                try {
                    Color c = MapPalette.getColor((byte) i);
                    argb[i] = 0xFF000000 | (c.getRGB() & 0xFFFFFF);
                } catch (RuntimeException e) {
                    argb[i] = 0; // 空缺基色槽(62/63):数据中不应出现,按透明忽略
                }
            }
        }

        BufferedImage img = new BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB);
        for (int ty = 0; ty < dstH; ty++) {
            int y0 = ty * srcH / dstH;
            int y1 = Math.max(y0 + 1, (ty + 1) * srcH / dstH);
            for (int tx = 0; tx < dstW; tx++) {
                int x0 = tx * srcW / dstW;
                int x1 = Math.max(x0 + 1, (tx + 1) * srcW / dstW);
                long rs = 0, gs = 0, bs = 0;
                int n = 0;
                for (int y = y0; y < y1; y++) {
                    int tr = y / TILE;
                    int rowBase = tr * cols * TILE_BYTES + (y - tr * TILE) * TILE;
                    for (int x = x0; x < x1; x++) {
                        int u = pixels[rowBase + (x >> 7) * TILE_BYTES + (x & 127)] & 0xFF;
                        if (u <= 3) {
                            continue;
                        }
                        int c = argb[u];
                        rs += (c >> 16) & 0xFF;
                        gs += (c >> 8) & 0xFF;
                        bs += c & 0xFF;
                        n++;
                    }
                }
                int rgb;
                if (n == 0) {
                    rgb = 0; // 全透明
                } else {
                    try {
                        byte mb = MapPalette.matchColor(new Color((int) (rs / n), (int) (gs / n), (int) (bs / n)));
                        Color m = MapPalette.getColor(mb);
                        rgb = 0xFF000000 | (m.getRGB() & 0xFFFFFF);
                    } catch (RuntimeException e) {
                        rgb = 0xFF000000 | (((int) (rs / n) << 16) | ((int) (gs / n) << 8) | (int) (bs / n));
                    }
                }
                img.setRGB(tx, ty, rgb);
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }
}
