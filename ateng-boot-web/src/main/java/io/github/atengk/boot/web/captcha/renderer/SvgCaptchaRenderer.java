package io.github.atengk.boot.web.captcha.renderer;

import io.github.atengk.boot.web.captcha.properties.CaptchaProperties;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 零系统字体依赖的纯 JDK 矢量 SVG 图形验证码渲染器
 * 基于内置字符矢量轮廓 {@code <path>} 表叠加随机旋转缩放、贝塞尔干扰线与随机噪点输出图像，
 * 全程不输出明文 {@code <text>} 标签，彻底防御正则直读并杜绝精简容器缺字体异常
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class SvgCaptchaRenderer implements CaptchaRenderer {

    private static final String SVG_DATA_URL_PREFIX = "data:image/svg+xml;base64,";
    private static final String[] PALETTE = {
            "#1e3a8a", "#7c2d12", "#065f46", "#581c87", "#9f1239", "#0f766e", "#334155"
    };
    private static final Map<Character, String> GLYPH_PATHS = buildGlyphPaths();

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

        StringBuilder svg = new StringBuilder(1024);
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(width)
                .append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height).append("\">");
        svg.append("<rect width=\"100%\" height=\"100%\" rx=\"4\" fill=\"#f8fafc\"/>");

        appendNoiseLines(svg, width, height, lineCount);
        appendGlyphs(svg, displayText, width, height);
        appendNoiseDots(svg, width, height, dotCount);

        svg.append("</svg>");
        String encoded = Base64.getEncoder().encodeToString(svg.toString().getBytes(StandardCharsets.UTF_8));
        return SVG_DATA_URL_PREFIX + encoded;
    }

    private void appendNoiseLines(StringBuilder svg, int width, int height, int lineCount) {
        for (int i = 0; i < lineCount; i++) {
            int x1 = random.nextInt(Math.max(1, width / 4));
            int y1 = random.nextInt(height);
            int cx = width / 4 + random.nextInt(Math.max(1, width / 2));
            int cy = random.nextInt(height);
            int x2 = width * 3 / 4 + random.nextInt(Math.max(1, width / 4));
            int y2 = random.nextInt(height);
            String color = PALETTE[random.nextInt(PALETTE.length)];
            svg.append(String.format(Locale.ROOT,
                    "<path d=\"M %d,%d Q %d,%d %d,%d\" stroke=\"%s\" stroke-opacity=\"0.35\" stroke-width=\"1.3\" fill=\"none\"/>",
                    x1, y1, cx, cy, x2, y2, color));
        }
    }

    private void appendGlyphs(StringBuilder svg, String displayText, int width, int height) {
        int count = displayText.length();
        double paddingX = Math.max(6.0, width * 0.08);
        double usableWidth = Math.max(12.0, width - paddingX * 2);
        double cellWidth = usableWidth / count;

        // 每个字符基于 10x16 标准坐标框按容器高度与格子宽度等比缩放
        double scaleBase = Math.min(cellWidth / 12.0, (height * 0.68) / 16.0);
        double safeScale = Math.max(0.6, scaleBase);

        for (int i = 0; i < count; i++) {
            char ch = displayText.charAt(i);
            String path = GLYPH_PATHS.get(ch);
            if (path == null) {
                throw new IllegalArgumentException("Unsupported captcha character: " + ch);
            }
            double scaleX = safeScale * (0.92 + random.nextDouble() * 0.16);
            double scaleY = safeScale * (0.92 + random.nextDouble() * 0.16);
            double glyphW = 10.0 * scaleX;
            double glyphH = 16.0 * scaleY;
            double x = paddingX + i * cellWidth + (cellWidth - glyphW) / 2.0 + (random.nextDouble() - 0.5) * 3.0;
            double y = (height - glyphH) / 2.0 + (random.nextDouble() - 0.5) * 4.0;
            double angle = (random.nextDouble() - 0.5) * 34.0;
            String color = PALETTE[random.nextInt(PALETTE.length)];

            svg.append(String.format(Locale.ROOT,
                    "<g transform=\"translate(%.2f,%.2f) rotate(%.1f,%.2f,%.2f) scale(%.3f,%.3f)\">"
                            + "<path d=\"%s\" stroke=\"%s\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\"/>"
                            + "</g>",
                    x, y, angle, glyphW / 2.0, glyphH / 2.0, scaleX, scaleY, path, color));
        }
    }

    private void appendNoiseDots(StringBuilder svg, int width, int height, int dotCount) {
        for (int i = 0; i < dotCount; i++) {
            int cx = random.nextInt(width);
            int cy = random.nextInt(height);
            double r = 0.8 + random.nextDouble() * 1.1;
            String color = PALETTE[random.nextInt(PALETTE.length)];
            svg.append(String.format(Locale.ROOT,
                    "<circle cx=\"%d\" cy=\"%d\" r=\"%.1f\" fill=\"%s\" fill-opacity=\"0.35\"/>",
                    cx, cy, r, color));
        }
    }

    private static Map<Character, String> buildGlyphPaths() {
        Map<Character, String> map = new HashMap<>(72);
        // 数字 0-9
        map.put('0', "M 2,4 C 2,1 8,1 8,4 L 8,12 C 8,15 2,15 2,12 Z");
        map.put('1', "M 3,3 L 5,1 L 5,15 M 2,15 L 8,15");
        map.put('2', "M 2,4 C 2,1 8,1 8,4 C 8,8 2,10 2,15 L 8,15");
        map.put('3', "M 2,3 C 3,1 8,1 8,4 C 8,7 5,8 4,8 C 6,8 8,9 8,12 C 8,15 3,15 2,13");
        map.put('4', "M 7,15 L 7,1 L 1,10 L 9,10");
        map.put('5', "M 8,1 L 2,1 L 2,7 C 5,6 8,7 8,11 C 8,15 3,15 2,13");
        map.put('6', "M 8,3 C 7,1 2,1 2,6 L 2,11 C 2,15 8,15 8,11 C 8,8 2,8 2,11");
        map.put('7', "M 2,1 L 8,1 L 4,15");
        map.put('8', "M 5,8 C 2,8 2,1 5,1 C 8,1 8,8 5,8 C 2,8 2,15 5,15 C 8,15 8,8 5,8");
        map.put('9', "M 8,5 C 8,1 2,1 2,5 C 2,8 8,8 8,5 L 8,11 C 8,15 3,15 2,13");

        // 大写字母 A-Z
        map.put('A', "M 1,15 L 5,1 L 9,15 M 2.5,10 L 7.5,10");
        map.put('B', "M 2,15 L 2,1 L 6,1 C 8,1 8,7 5,8 L 2,8 M 5,8 C 9,8 9,15 6,15 L 2,15");
        map.put('C', "M 8,3 C 6,1 2,1 2,6 L 2,10 C 2,15 6,15 8,13");
        map.put('D', "M 2,15 L 2,1 L 5,1 C 9,1 9,15 5,15 Z");
        map.put('E', "M 8,1 L 2,1 L 2,15 L 8,15 M 2,8 L 7,8");
        map.put('F', "M 8,1 L 2,1 L 2,15 M 2,8 L 7,8");
        map.put('G', "M 8,3 C 6,1 2,1 2,6 L 2,10 C 2,15 8,15 8,10 L 8,8 L 5,8");
        map.put('H', "M 2,1 L 2,15 M 8,1 L 8,15 M 2,8 L 8,8");
        map.put('I', "M 3,1 L 7,1 M 5,1 L 5,15 M 3,15 L 7,15");
        map.put('J', "M 7,1 L 7,11 C 7,15 2,15 2,12");
        map.put('K', "M 2,1 L 2,15 M 8,1 L 2,8 L 8,15");
        map.put('L', "M 2,1 L 2,15 L 8,15");
        map.put('M', "M 1,15 L 1,1 L 5,9 L 9,1 L 9,15");
        map.put('N', "M 2,15 L 2,1 L 8,15 L 8,1");
        map.put('O', "M 5,1 C 9,1 9,15 5,15 C 1,15 1,1 5,1");
        map.put('P', "M 2,15 L 2,1 L 6,1 C 9,1 9,8 6,8 L 2,8");
        map.put('Q', "M 5,1 C 9,1 9,15 5,15 C 1,15 1,1 5,1 M 6,11 L 9,16");
        map.put('R', "M 2,15 L 2,1 L 6,1 C 9,1 9,8 6,8 L 2,8 M 5,8 L 8,15");
        map.put('S', "M 8,3 C 7,1 2,1 2,4 C 2,7 8,8 8,12 C 8,15 3,15 2,13");
        map.put('T', "M 1,1 L 9,1 M 5,1 L 5,15");
        map.put('U', "M 2,1 L 2,11 C 2,15 8,15 8,11 L 8,1");
        map.put('V', "M 1,1 L 5,15 L 9,1");
        map.put('W', "M 1,1 L 3,15 L 5,7 L 7,15 L 9,1");
        map.put('X', "M 1,1 L 9,15 M 9,1 L 1,15");
        map.put('Y', "M 1,1 L 5,8 L 9,1 M 5,8 L 5,15");
        map.put('Z', "M 1,1 L 9,1 L 1,15 L 9,15");

        // 小写字母 a-z
        map.put('a', "M 2,8 C 3,6 8,6 8,9 L 8,15 M 8,11 C 5,10 2,11 2,13 C 2,15 6,15 8,13");
        map.put('b', "M 2,1 L 2,15 M 2,9 C 4,6 8,7 8,11 C 8,15 4,16 2,13");
        map.put('c', "M 8,8 C 6,6 2,6 2,11 C 2,15 6,15 8,13");
        map.put('d', "M 8,1 L 8,15 M 8,9 C 6,6 2,7 2,11 C 2,15 6,16 8,13");
        map.put('e', "M 2,11 L 8,11 C 8,6 2,6 2,11 C 2,15 6,15 8,13");
        map.put('f', "M 7,2 C 5,1 4,2 4,5 L 4,15 M 2,7 L 7,7");
        map.put('g', "M 8,7 L 8,15 C 8,17 3,17 2,16 M 8,9 C 6,6 2,7 2,10 C 2,14 6,14 8,12");
        map.put('h', "M 2,1 L 2,15 M 2,9 C 4,6 8,7 8,10 L 8,15");
        map.put('i', "M 5,3 L 5,4 M 5,7 L 5,15");
        map.put('j', "M 6,3 L 6,4 M 6,7 L 6,15 C 6,17 3,17 2,16");
        map.put('k', "M 2,1 L 2,15 M 8,7 L 2,11 L 8,15");
        map.put('l', "M 5,1 L 5,14 C 5,15 6,15 7,15");
        map.put('m', "M 1,15 L 1,7 M 1,9 C 2,6 5,6 5,9 L 5,15 M 5,9 C 6,6 9,6 9,9 L 9,15");
        map.put('n', "M 2,15 L 2,7 M 2,9 C 4,6 8,6 8,10 L 8,15");
        map.put('o', "M 5,7 C 9,7 9,15 5,15 C 1,15 1,7 5,7");
        map.put('p', "M 2,7 L 2,17 M 2,9 C 4,6 8,7 8,11 C 8,15 4,15 2,13");
        map.put('q', "M 8,7 L 8,17 M 8,9 C 6,6 2,7 2,11 C 2,15 6,15 8,13");
        map.put('r', "M 2,15 L 2,7 M 2,10 C 3,7 6,6 8,8");
        map.put('s', "M 8,8 C 6,6 2,6 2,9 C 2,11 8,11 8,13 C 8,15 3,15 2,14");
        map.put('t', "M 5,3 L 5,14 C 5,15 7,15 8,14 M 2,7 L 7,7");
        map.put('u', "M 2,7 L 2,12 C 2,15 6,15 8,13 M 8,7 L 8,15");
        map.put('v', "M 2,7 L 5,15 L 8,7");
        map.put('w', "M 1,7 L 3,15 L 5,9 L 7,15 L 9,7");
        map.put('x', "M 2,7 L 8,15 M 8,7 L 2,15");
        map.put('y', "M 2,7 L 5,14 M 8,7 L 4,17 L 2,17");
        map.put('z', "M 2,7 L 8,7 L 2,15 L 8,15");

        // 算术运算符与辅助符号
        map.put('+', "M 5,4 L 5,12 M 1,8 L 9,8");
        map.put('-', "M 2,8 L 8,8");
        map.put('*', "M 5,4 L 5,12 M 1.5,6 L 8.5,10 M 8.5,6 L 1.5,10");
        map.put('=', "M 2,6 L 8,6 M 2,10 L 8,10");
        map.put('?', "M 2,4 C 2,1 8,1 8,4 C 8,7 5,8 5,11 M 5,14 L 5,15");
        return Map.copyOf(map);
    }
}
