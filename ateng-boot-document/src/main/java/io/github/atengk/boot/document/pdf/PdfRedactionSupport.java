package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.document.enums.KeywordStampOccurrence;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.pdf.model.RedactArea;
import io.github.atengk.boot.document.pdf.model.RedactOptions;
import io.github.atengk.boot.document.pdf.model.TextPositionMatch;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF 坐标校准、关键字物理定位与光栅涂黑脱敏内部协作者
 *
 * @author Ateng
 * @since 2026-11-18
 */
final class PdfRedactionSupport {

    private PdfRedactionSupport() {
    }

    /**
     * 物理矩形坐标值对象（PDF 左下角物理坐标系，单位：pt）
     */
    record RedactRect(float x, float y, float width, float height) {
    }

    /**
     * 旋转校准后的页面可视宽高值对象（单位：pt）
     */
    record VisualSize(float width, float height) {
    }

    /**
     * 物理涂黑脱敏扫描计划（包含按页分组的物理遮罩区域与关键字命中总数）
     */
    record RedactionPlan(Map<Integer, List<RedactRect>> masksByPage, int keywordMatchCount) {
    }

    static PDRectangle resolveVisibleBox(PDPage page) {
        PDRectangle cropBox = page.getCropBox();
        return cropBox != null ? cropBox : page.getMediaBox();
    }

    static int normalizeRotation(PDPage page) {
        return ((page.getRotation() % 360) + 360) % 360;
    }

    static VisualSize resolveVisualSize(PDRectangle visibleBox, int rotation) {
        float w = visibleBox.getWidth();
        float h = visibleBox.getHeight();
        return (rotation == 90 || rotation == 270) ? new VisualSize(h, w) : new VisualSize(w, h);
    }

    static void applyVisualCoordinateTransform(PDPageContentStream cs, PDRectangle visibleBox, int rotation)
            throws IOException {
        float llx = visibleBox.getLowerLeftX();
        float lly = visibleBox.getLowerLeftY();
        float cw = visibleBox.getWidth();
        float ch = visibleBox.getHeight();
        Matrix matrix = switch (rotation) {
            case 90 -> new Matrix(0, 1, -1, 0, llx + cw, lly);
            case 180 -> new Matrix(-1, 0, 0, -1, llx + cw, lly + ch);
            case 270 -> new Matrix(0, -1, 1, 0, llx, lly + ch);
            default -> Matrix.getTranslateInstance(llx, lly);
        };
        cs.transform(matrix);
    }

    static List<TextPositionMatch> selectKeywordMatches(List<TextPositionMatch> matches,
                                                        KeywordStampOccurrence occurrence) {
        KeywordStampOccurrence mode = occurrence != null ? occurrence : KeywordStampOccurrence.LAST;
        return switch (mode) {
            case FIRST -> List.of(matches.get(0));
            case ALL -> matches;
            case LAST -> List.of(matches.get(matches.size() - 1));
        };
    }

    static List<TextPositionMatch> findTextPositionsInDocument(PDDocument doc, String keyword,
                                                               Set<Integer> targetPages) throws IOException {
        return findTextPositionsInDocument(doc, keyword, targetPages, false);
    }

    static List<TextPositionMatch> findTextPositionsInDocument(PDDocument doc, String keyword,
                                                               Set<Integer> targetPages,
                                                               boolean ignoreCase) throws IOException {
        int totalPages = doc.getNumberOfPages();
        List<TextPositionMatch> matches = new ArrayList<>();
        for (int p = 1; p <= totalPages; p++) {
            if (targetPages != null && !targetPages.isEmpty() && !targetPages.contains(p)) {
                continue;
            }
            PDPage page = doc.getPage(p - 1);
            PDRectangle visibleBox = resolveVisibleBox(page);
            float llx = visibleBox.getLowerLeftX();
            float lly = visibleBox.getLowerLeftY();

            StringBuilder pageText = new StringBuilder();
            List<TextPosition> charPositions = new ArrayList<>();

            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> textPositions) {
                    if (textPositions == null) {
                        return;
                    }
                    for (TextPosition tp : textPositions) {
                        String unicode = tp.getUnicode();
                        if (unicode != null && !unicode.isEmpty()) {
                            for (int i = 0; i < unicode.length(); i++) {
                                pageText.append(unicode.charAt(i));
                                charPositions.add(tp);
                            }
                        }
                    }
                }

                @Override
                protected void writeWordSeparator() {
                    pageText.append(' ');
                    charPositions.add(null);
                }

                @Override
                protected void writeLineSeparator() {
                    pageText.append('\n');
                    charPositions.add(null);
                }
            };
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            stripper.setSortByPosition(true);
            stripper.getText(doc);

            String full = pageText.toString();
            int cursor = 0;
            while ((cursor = findNextMatchIndex(full, keyword, cursor, ignoreCase)) >= 0) {
                int endIdx = cursor + keyword.length();
                float minX = Float.MAX_VALUE;
                float minBottomY = Float.MAX_VALUE;
                float maxRightX = -Float.MAX_VALUE;
                float maxTopY = -Float.MAX_VALUE;

                for (int i = cursor; i < endIdx; i++) {
                    TextPosition tp = charPositions.get(i);
                    if (tp == null) {
                        continue;
                    }
                    float gx = llx + tp.getXDirAdj();
                    float gBottomY = lly + (tp.getPageHeight() - tp.getYDirAdj());
                    float gRightX = gx + tp.getWidthDirAdj();
                    float gTopY = gBottomY + tp.getHeightDir();

                    minX = Math.min(minX, gx);
                    minBottomY = Math.min(minBottomY, gBottomY);
                    maxRightX = Math.max(maxRightX, gRightX);
                    maxTopY = Math.max(maxTopY, gTopY);
                }

                if (minX != Float.MAX_VALUE) {
                    matches.add(TextPositionMatch.builder()
                            .pageIndex(p - 1)
                            .text(full.substring(cursor, endIdx))
                            .x(minX)
                            .y(minBottomY)
                            .width(Math.max(1.0f, maxRightX - minX))
                            .height(Math.max(1.0f, maxTopY - minBottomY))
                            .build());
                }
                cursor = endIdx;
            }
        }
        return matches;
    }

    private static int findNextMatchIndex(String haystack, String needle, int fromIndex, boolean ignoreCase) {
        if (!ignoreCase) {
            return haystack.indexOf(needle, fromIndex);
        }
        int needleLen = needle.length();
        int limit = haystack.length() - needleLen;
        for (int i = Math.max(0, fromIndex); i <= limit; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needleLen)) {
                return i;
            }
        }
        return -1;
    }

    static RedactionPlan collectRedactionPlan(PDDocument doc, RedactOptions options,
                                              float padding, int totalPages) throws IOException {
        Map<Integer, List<RedactRect>> masksByPage = new LinkedHashMap<>();
        int keywordMatchCount = 0;
        if (options.getKeywords() != null) {
            for (String keyword : options.getKeywords()) {
                if (keyword == null || keyword.isEmpty()) {
                    continue;
                }
                List<TextPositionMatch> matches = findTextPositionsInDocument(
                        doc, keyword, options.getTargetPages(), options.isIgnoreCase());
                keywordMatchCount += matches.size();
                for (TextPositionMatch match : matches) {
                    float descenderExtra = match.getHeight() * 0.25f;
                    masksByPage.computeIfAbsent(match.getPageIndex(), k -> new ArrayList<>())
                            .add(new RedactRect(
                                    match.getX() - padding,
                                    match.getY() - padding - descenderExtra,
                                    match.getWidth() + 2 * padding,
                                    match.getHeight() + 2 * padding + descenderExtra
                            ));
                }
            }
        }

        if (options.getAreas() != null) {
            Set<Integer> targetPages = options.getTargetPages();
            for (RedactArea area : options.getAreas()) {
                if (area == null || area.getWidth() <= 0 || area.getHeight() <= 0) {
                    continue;
                }
                int pageIdx = PdfTemplate.normalizePageIndex(area.getPageIndex(), totalPages);
                if (targetPages != null && !targetPages.isEmpty() && !targetPages.contains(pageIdx + 1)) {
                    continue;
                }
                masksByPage.computeIfAbsent(pageIdx, k -> new ArrayList<>())
                        .add(new RedactRect(area.getX(), area.getY(), area.getWidth(), area.getHeight()));
            }
        }
        return new RedactionPlan(masksByPage, keywordMatchCount);
    }

    static void applyRasterRedactionToPages(PDDocument doc, Map<Integer, List<RedactRect>> masksByPage,
                                            float dpi, Color maskColor) throws IOException {
        PDFRenderer renderer = new PDFRenderer(doc);
        float scale = dpi / 72.0f;

        for (Map.Entry<Integer, List<RedactRect>> entry : masksByPage.entrySet()) {
            int pageIdx = entry.getKey();
            PDPage page = doc.getPage(pageIdx);
            PDRectangle visibleBox = resolveVisibleBox(page);
            int rotation = normalizeRotation(page);
            VisualSize visSize = resolveVisualSize(visibleBox, rotation);
            float llx = visibleBox.getLowerLeftX();
            float lly = visibleBox.getLowerLeftY();

            BufferedImage rendered = renderer.renderImageWithDPI(pageIdx, dpi, ImageType.RGB);
            byte[] encodedBytes;
            try {
                Graphics2D g2d = rendered.createGraphics();
                try {
                    g2d.setColor(maskColor);
                    int imgW = rendered.getWidth();
                    int imgH = rendered.getHeight();
                    for (RedactRect rect : entry.getValue()) {
                        int px = Math.max(0, Math.round((rect.x() - llx) * scale));
                        int py = Math.max(0, Math.round((visSize.height() - (rect.y() - lly + rect.height())) * scale));
                        int pw = Math.min(imgW - px, Math.max(1, Math.round(rect.width() * scale)));
                        int ph = Math.min(imgH - py, Math.max(1, Math.round(rect.height() * scale)));
                        if (pw > 0 && ph > 0) {
                            g2d.fillRect(px, py, pw, ph);
                        }
                    }
                } finally {
                    g2d.dispose();
                }
                encodedBytes = toImageBytes(rendered, "PNG");
            } finally {
                rendered.flush();
            }

            page.setAnnotations(Collections.emptyList());
            PDImageXObject pdImage = PDImageXObject.createFromByteArray(doc, encodedBytes, "redact-page-" + pageIdx);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true, true)) {
                applyVisualCoordinateTransform(cs, visibleBox, rotation);
                cs.drawImage(pdImage, 0, 0, visSize.width(), visSize.height());
            }
        }
    }

    static byte[] toImageBytes(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String fmt = format != null && !format.trim().isEmpty() ? format.trim() : "PNG";
        boolean written = ImageIO.write(image, fmt, baos);
        if (!written) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "不支持的图像输出格式: " + fmt);
        }
        return baos.toByteArray();
    }
}
