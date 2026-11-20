package com.trialbyhammer.mpmp;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 厚度场模型的自测：锤一条线，看形状。
 *
 * <p>用和 MPM 版相同的锤击序列，直接对比两种模型的输出。</p>
 */
public final class HeightFieldSelfTest {

    public static void main(String[] args) throws Exception {
        new File("build/mpm").mkdirs();

        HeightFieldBlade blade = new HeightFieldBlade();

        System.out.printf("初始体积: %.4f%n", blade.totalVolume());
        render(blade, "build/mpm/heightfield_before.png");

        // 沿刀身锤一排，横锤（X 长 Y 窄）
        for (int round = 1; round <= 18; round++) {
            float sx = 0.18F + ((round - 1) % 12) * 0.055F;
            blade.press(sx, 0.60F, 0.12F, 0.045F, 0.012F);
        }

        System.out.printf("锤后体积: %.4f%n", blade.totalVolume());
        render(blade, "build/mpm/heightfield_after.png");
    }

    /** 高度图：亮 = 厚，暗 = 薄，黑 = 没材料。 */
    private static void render(HeightFieldBlade blade, String path) throws Exception {
        int scale = 8;
        int w = HeightFieldBlade.NX * scale;
        int h = HeightFieldBlade.NY * scale;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int iy = 0; iy < HeightFieldBlade.NY; iy++) {
            for (int ix = 0; ix < HeightFieldBlade.NX; ix++) {
                float v = blade.height(ix, iy);
                float t = Math.max(0.0F, Math.min(1.0F, v / 0.10F));

                int shade = (int) (255 * t);
                int color = (shade << 16) | (shade << 8) | shade;

                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        img.setRGB(ix * scale + dx, iy * scale + dy, color);
                    }
                }
            }
        }
        ImageIO.write(img, "png", new File(path));
        System.out.println("  image: " + path);
    }
}
