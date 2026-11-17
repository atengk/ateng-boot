package io.github.atengk.boot.web.captcha.renderer;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * 基于纯 JDK {@link BufferedImage} 与 {@link Graphics2D} 的 PNG 图形验证码渲染器
 * 使用 JDK 逻辑字体（{@link Font#SANS_SERIF}）绘制旋转字符、干扰线与噪点，输出标准 Base64 PNG Data URL
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class PngCaptchaRenderer implements CaptchaRenderer {

    private static final String PNG_DATA_URL_PREFIX = "data:image/png;base64,";
    private static final Color BACKGROUND_COLOR = new Color(248, 250, 252);
    private static final Color[] PALETTE = {
            new Color(30, 58, 138),
            new Color(124, 45, 18),
            new Color(6, 95, 70),
            new Color(88, 28, 135),
            new Color(159, 18, 57),
            new Color(15, 118, 110)
    };

    private final SecureRandom random = new SecureRandom();

    @Override
    public String render(String displayText, CaptchaProperties properties) {
        if (displayText == null || displayText.isBlank()) {
            throw new IllegalArgumentException("Captcha displayText must not be blank");
        }
        Objects.requireNonNull(properties, "CaptchaProperties must not be null");

        int width = properties.getWidth();
        int height = properties.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Captcha width and height must be positive");
        }

        int lineCount = Math.max(0, properties.getNoiseLineCount());
        int dotCount = Math.max(0, properties.getNoiseDotCount());

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = null;
        try {
            g2d = image.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 1. 填充背景
            g2d.setColor(BACKGROUND_COLOR);
            g2d.fillRect(0, 0, width, height);

            // 2. 绘制干扰线
            g2d.setStroke(new BasicStroke(1.4f));
            for (int i = 0; i < lineCount; i++) {
                g2d.setColor(randomTintColor(140));
                int x1 = random.nextInt(width);
                int y1 = random.nextInt(height);
                int x2 = random.nextInt(width);
                int y2 = random.nextInt(height);
                g2d.drawLine(x1, y1, x2, y2);
            }

            // 3. 绘制带随机旋转角度的字符（兼顾画布高度与单字格宽防止长文本横向挤压）
            int count = displayText.length();
            int paddingX = Math.max(6, width / 14);
            int usableWidth = Math.max(12, width - paddingX * 2);
            double cellWidth = (double) usableWidth / count;

            int fontSize = (int) Math.max(12, Math.min(height * 0.65, cellWidth * 1.1));
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            g2d.setFont(font);
            FontMetrics metrics = g2d.getFontMetrics();

            for (int i = 0; i < count; i++) {
                String ch = String.valueOf(displayText.charAt(i));
                int charWidth = metrics.stringWidth(ch);
                double centerX = paddingX + i * cellWidth + cellWidth / 2.0;
                double centerY = height / 2.0;
                double angle = Math.toRadians((random.nextDouble() - 0.5) * 32.0);

                AffineTransform oldTransform = g2d.getTransform();
                g2d.rotate(angle, centerX, centerY);
                g2d.setColor(PALETTE[random.nextInt(PALETTE.length)]);
                int drawX = (int) Math.round(centerX - charWidth / 2.0);
                int drawY = (int) Math.round(centerY + (metrics.getAscent() - metrics.getDescent()) / 2.0);
                g2d.drawString(ch, drawX, drawY);
                g2d.setTransform(oldTransform);
            }

            // 4. 绘制噪点
            for (int i = 0; i < dotCount; i++) {
                g2d.setColor(randomTintColor(120));
                int x = random.nextInt(width);
                int y = random.nextInt(height);
                g2d.fillOval(x, y, 2, 2);
            }
        } finally {
            if (g2d != null) {
                g2d.dispose();
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return PNG_DATA_URL_PREFIX + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "PNG captcha encoding failed: " + ex.getMessage(), ex);
        }
    }

    private Color randomTintColor(int alpha) {
        Color base = PALETTE[random.nextInt(PALETTE.length)];
        return new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
    }
}
