package io.github.atengk.boot.excel.util;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Excel 防拍照暗水印生成工具类
 * <p>
 * 基于纯 Java 2D 绘图引擎动态生成 45° 倾斜旋转、高清晰度且半透明平铺的防伪切片水印图片。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class ExcelWatermarkUtil {

    private static final int DEFAULT_WIDTH = 420;
    private static final int DEFAULT_HEIGHT = 280;
    private static final float DEFAULT_ALPHA = 0.18f;
    private static final double DEFAULT_ANGLE = -30.0;
    private static final String DEFAULT_FONT_NAME = "微软雅黑";
    private static final int DEFAULT_FONT_SIZE = 20;

    private ExcelWatermarkUtil() {
    }

    /**
     * 生成默认尺寸与样式的半透明水印图片字节数组 (PNG 格式)
     *
     * @param text 水印文本（例如 {@code "机密 · 张三 (1001) · 2026-10-17"}）
     * @return PNG 图片无损字节数组
     */
    public static byte[] createWatermarkImage(String text) {
        return createWatermarkImage(text, DEFAULT_WIDTH, DEFAULT_HEIGHT, DEFAULT_ALPHA, DEFAULT_ANGLE);
    }

    /**
     * 生成自定义参数的半透明水印图片字节数组 (PNG 格式)
     *
     * @param text   水印文本（不可为空）
     * @param width  单切片画布宽度（像素）
     * @param height 单切片画布高度（像素）
     * @param alpha  透明度（0.0f ~ 1.0f，推荐 0.15f ~ 0.25f）
     * @param angle  旋转角度（度数，例如 -30 或 -45）
     * @return PNG 图片无损字节数组
     */
    public static byte[] createWatermarkImage(String text, int width, int height, float alpha, double angle) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        try {
            // 1. 设置高质量渲染与文字抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 2. 旋转画布
            g2d.rotate(Math.toRadians(angle), width / 2.0, height / 2.0);

            // 3. 设置半透明颜色与字体
            Font font = new Font(DEFAULT_FONT_NAME, Font.BOLD, DEFAULT_FONT_SIZE);
            g2d.setFont(font);
            g2d.setColor(new Color(128, 128, 128, (int) (Math.min(1.0f, Math.max(0.01f, alpha)) * 255)));

            // 4. 居中绘制文字
            FontMetrics fontMetrics = g2d.getFontMetrics(font);
            int textWidth = fontMetrics.stringWidth(text);
            int textHeight = fontMetrics.getAscent();

            int x = (width - textWidth) / 2;
            int y = (height + textHeight) / 2;

            g2d.drawString(text, x, y);
        } finally {
            g2d.dispose();
        }

        // 5. 编码为 PNG 格式
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成水印图片失败: " + e.getMessage(), e);
        }
    }
}
