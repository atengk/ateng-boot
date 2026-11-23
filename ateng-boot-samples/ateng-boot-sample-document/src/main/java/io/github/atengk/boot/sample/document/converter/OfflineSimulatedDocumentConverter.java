package io.github.atengk.boot.sample.document.converter;

import io.github.atengk.boot.document.exception.DocumentException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.spi.DocumentConverter;
import org.apache.fontbox.ttf.CmapLookup;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDCIDFont;
import org.apache.pdfbox.pdmodel.font.PDCIDFontType2;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 默认离线自洽环境（!local）下的文档跨格式转换模拟桩
 * <p>
 * 仅在未激活 {@code local} Profile 时注册至 Spring 容器，
 * 使用 POI 与 PDFBox 在内存中提取原始文本并合成合法单/多页 PDF，保障无需启动 Gotenberg 外部容器即可跑通全链路测试；
 * 激活 {@code local} Profile 且开启 {@code ateng.boot.document.converter.gotenberg.enabled=true} 时自动由真实 Gotenberg 适配器接管。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Component
@Profile("!local")
public class OfflineSimulatedDocumentConverter implements DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(OfflineSimulatedDocumentConverter.class);
    private static final int MAX_LINES_PER_PAGE = 34;

    private final DocumentFontResolver fontResolver;

    public OfflineSimulatedDocumentConverter(DocumentFontResolver fontResolver) {
        this.fontResolver = fontResolver;
    }

    @Override
    public byte[] docxToPdf(InputStream docxInputStream) {
        Objects.requireNonNull(docxInputStream, "待转换的 DOCX 输入流不能为空");
        try {
            byte[] docxBytes = docxInputStream.readAllBytes();
            String extracted;
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes));
                 XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
                extracted = extractor.getText();
            }
            List<String> lines = splitLines("OFFLINE SIMULATED DOCX -> PDF (Gotenberg Stub)", extracted);
            return renderLinesToPdf(lines);
        } catch (Exception e) {
            throw new DocumentException(DocumentResultCode.CONVERSION_FAILED,
                    "离线模拟 DOCX 转 PDF 失败: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] htmlToPdf(InputStream htmlInputStream) {
        Objects.requireNonNull(htmlInputStream, "待转换的 HTML 输入流不能为空");
        try {
            String rawHtml = new String(htmlInputStream.readAllBytes(), StandardCharsets.UTF_8);
            String plainText = stripHtmlTags(rawHtml);
            List<String> lines = splitLines("OFFLINE SIMULATED HTML -> PDF (Gotenberg Stub)", plainText);
            return renderLinesToPdf(lines);
        } catch (Exception e) {
            throw new DocumentException(DocumentResultCode.CONVERSION_FAILED,
                    "离线模拟 HTML 转 PDF 失败: " + e.getMessage(), e);
        }
    }

    private byte[] renderLinesToPdf(List<String> lines) throws IOException {
        try (PDDocument pdfDoc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDFont asciiFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType0Font cjkFont = loadOptionalCjkFont(pdfDoc);
            CmapLookup cjkCmap = resolveCmapLookup(cjkFont);

            int total = lines.size();
            for (int start = 0; start < total; start += MAX_LINES_PER_PAGE) {
                int end = Math.min(total, start + MAX_LINES_PER_PAGE);
                PDPage page = new PDPage(PDRectangle.A4);
                pdfDoc.addPage(page);

                try (PDPageContentStream cs = new PDPageContentStream(pdfDoc, page)) {
                    float y = 780.0f;
                    for (int i = start; i < end; i++) {
                        drawSafeLine(cs, cjkFont, cjkCmap, asciiFont, lines.get(i), 48.0f, y,
                                i == 0 && start == 0 ? 13.0f : 10.5f);
                        y -= 20.0f;
                    }
                }
            }
            pdfDoc.save(baos);
            return baos.toByteArray();
        }
    }

    private PDType0Font loadOptionalCjkFont(PDDocument pdfDoc) {
        if (fontResolver == null || !fontResolver.isFontAvailable()) {
            return null;
        }
        try {
            return fontResolver.loadFont(pdfDoc);
        } catch (Exception e) {
            log.warn("加载离线模拟中文字体失败，将回退至 ASCII 字体: {}", e.getMessage(), e);
            return null;
        }
    }

    private CmapLookup resolveCmapLookup(PDType0Font cjkFont) throws IOException {
        if (cjkFont == null) {
            return null;
        }
        PDCIDFont descendant = cjkFont.getDescendantFont();
        if (descendant instanceof PDCIDFontType2 cidFontType2) {
            TrueTypeFont ttf = cidFontType2.getTrueTypeFont();
            if (ttf != null) {
                return ttf.getUnicodeCmapLookup(false);
            }
        }
        return null;
    }

    private void drawSafeLine(PDPageContentStream cs, PDType0Font cjkFont, CmapLookup cjkCmap, PDFont asciiFont,
                              String line, float x, float y, float fontSize) throws IOException {
        if (line == null || line.isEmpty()) {
            return;
        }
        cs.beginText();
        cs.newLineAtOffset(x, y);
        StringBuilder segment = new StringBuilder();
        boolean currentUseCjk = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch < 32 || ch == 127) {
                continue;
            }
            boolean isAscii = ch <= 126;
            boolean useCjk = !isAscii && cjkFont != null && cjkCmap != null && cjkCmap.getGlyphId(ch) != 0;
            char safeChar = (isAscii || useCjk) ? ch : '?';
            boolean nextUseCjk = useCjk;

            if (segment.isEmpty()) {
                currentUseCjk = nextUseCjk;
            } else if (currentUseCjk != nextUseCjk) {
                flushSegment(cs, currentUseCjk ? cjkFont : asciiFont, fontSize, segment.toString());
                segment.setLength(0);
                currentUseCjk = nextUseCjk;
            }
            segment.append(safeChar);
        }

        if (!segment.isEmpty()) {
            flushSegment(cs, currentUseCjk ? cjkFont : asciiFont, fontSize, segment.toString());
        }
        cs.endText();
    }

    private void flushSegment(PDPageContentStream cs, PDFont font, float fontSize, String text) throws IOException {
        if (font == null || text.isEmpty()) {
            return;
        }
        cs.setFont(font, fontSize);
        cs.showText(text);
    }

    private static List<String> splitLines(String header, String content) {
        List<String> lines = new ArrayList<>();
        lines.add(header);
        if (content == null || content.isBlank()) {
            lines.add("(empty content)");
            return lines;
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        for (String raw : normalized.split("\n")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                // 每行最多截取 90 字符分段以防超出 A4 边界
                for (int idx = 0; idx < trimmed.length(); idx += 90) {
                    lines.add(trimmed.substring(idx, Math.min(trimmed.length(), idx + 90)));
                }
            }
        }
        if (lines.size() == 1) {
            lines.add("(empty content)");
        }
        return lines;
    }

    private static String stripHtmlTags(String html) {
        if (html == null) {
            return "";
        }
        return html
                .replaceAll("(?is)<style.*?>.*?</style>", " ")
                .replaceAll("(?is)<script.*?>.*?</script>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|h1|h2|h3|h4|li|tr)>", "\n")
                .replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
}
