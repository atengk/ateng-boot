package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.document.enums.CrossPageSealEdge;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.pdf.model.CrossPageSealOptions;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import io.github.atengk.boot.document.properties.DocumentProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PDF 多页合同跨页骑缝章 (Cross-Page Seal) 单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class PdfTemplateCrossPageSealTest {

    private static byte[] sealPngBytes;
    private final PdfTemplate pdfTemplate = new PdfTemplate(createTestProperties());

    private static DocumentProperties createTestProperties() {
        DocumentProperties props = new DocumentProperties();
        DocumentPdfProperties pdfProps = new DocumentPdfProperties();
        pdfProps.setFontPath("classpath:fonts/test-cjk.ttf");
        props.setPdf(pdfProps);
        return props;
    }

    @BeforeAll
    static void setupAssets() throws IOException {
        try (InputStream is = PdfTemplateCrossPageSealTest.class.getResourceAsStream("/images/seal.png")) {
            if (is != null) {
                sealPngBytes = is.readAllBytes();
            } else {
                sealPngBytes = createMockSealPng();
            }
        }
    }

    private static byte[] createMockSealPng() throws IOException {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(220, 20, 60, 200));
            g.fillOval(10, 10, 180, 180);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    private static byte[] createMultiPagePdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(100, 700);
                    cs.showText("Contract Page " + (i + 1));
                    cs.endText();
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private static boolean pageHasImage(PDPage page) throws IOException {
        PDResources resources = page.getResources();
        if (resources == null) {
            return false;
        }
        for (COSName name : resources.getXObjectNames()) {
            if (resources.isImageXObject(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("测试标准 3 页合同在右边缘施加跨页骑缝章：全页均切片盖章")
    void testStampCrossPageSealRightEdgeThreePages(@TempDir Path tempDir) throws Exception {
        byte[] originalPdf = createMultiPagePdf(3);

        CrossPageSealOptions options = CrossPageSealOptions.builder()
                .sealImage(sealPngBytes)
                .width(120.0f)
                .height(120.0f)
                .edge(CrossPageSealEdge.RIGHT)
                .margin(0.0f)
                .build();

        File targetFile = tempDir.resolve("contract-sealed-3p.pdf").toFile();
        pdfTemplate.stampCrossPageSeal(originalPdf, targetFile, options);

        assertTrue(targetFile.exists() && targetFile.length() > 0);

        try (PDDocument doc = Loader.loadPDF(targetFile)) {
            assertEquals(3, doc.getNumberOfPages());
            for (int i = 0; i < 3; i++) {
                assertTrue(pageHasImage(doc.getPage(i)), "第 " + (i + 1) + " 页必须包含骑缝章切片印章");
            }
        }
    }

    @Test
    @DisplayName("测试 5 页合同跳过封面与封底盖骑缝章：仅中间 3 页压印切片")
    void testStampCrossPageSealSkipCoverAndBackCover() throws Exception {
        byte[] originalPdf = createMultiPagePdf(5);

        CrossPageSealOptions options = CrossPageSealOptions.builder()
                .sealImage(sealPngBytes)
                .skipCover(true)
                .skipBackCover(true)
                .edge(CrossPageSealEdge.RIGHT)
                .opacity(0.85f)
                .layer(WatermarkLayer.OVERLAY)
                .build();

        byte[] resultPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, options);
        assertNotNull(resultPdf);

        try (PDDocument doc = Loader.loadPDF(resultPdf)) {
            assertEquals(5, doc.getNumberOfPages());
            // 封面（第 0 页）跳过，无骑缝章
            assertFalse(pageHasImage(doc.getPage(0)), "封面页（第 1 页）应跳过骑缝章");
            // 中间正文页（第 1, 2, 3 页）包含切片骑缝章
            assertTrue(pageHasImage(doc.getPage(1)), "正文页（第 2 页）必须包含骑缝章切片");
            assertTrue(pageHasImage(doc.getPage(2)), "正文页（第 3 页）必须包含骑缝章切片");
            assertTrue(pageHasImage(doc.getPage(3)), "正文页（第 4 页）必须包含骑缝章切片");
            // 封底页（第 4 页）跳过，无骑缝章
            assertFalse(pageHasImage(doc.getPage(4)), "封底页（第 5 页）应跳过骑缝章");
        }
    }

    @Test
    @DisplayName("测试单页文档施加骑缝章：整印无缝盖印在右边缘")
    void testStampCrossPageSealSinglePage() throws Exception {
        byte[] originalPdf = createMultiPagePdf(1);

        CrossPageSealOptions options = CrossPageSealOptions.of(sealPngBytes);
        byte[] resultPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, options);

        try (PDDocument doc = Loader.loadPDF(resultPdf)) {
            assertEquals(1, doc.getNumberOfPages());
            assertTrue(pageHasImage(doc.getPage(0)), "单页文档必须包含完整骑缝章");
        }
    }

    @Test
    @DisplayName("测试自定义目标页码集合（支持负数索引映射）：仅选定页面盖骑缝章")
    void testStampCrossPageSealCustomTargetPages() throws Exception {
        byte[] originalPdf = createMultiPagePdf(4);

        // 指定第 2 页 (索引 1) 和最后一页 (负数索引 -1 映射为 3)
        CrossPageSealOptions options = CrossPageSealOptions.builder()
                .sealImage(sealPngBytes)
                .targetPages(List.of(1, -1))
                .build();

        byte[] resultPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, options);

        try (PDDocument doc = Loader.loadPDF(resultPdf)) {
            assertEquals(4, doc.getNumberOfPages());
            assertFalse(pageHasImage(doc.getPage(0)), "第 1 页未指定，不应有章");
            assertTrue(pageHasImage(doc.getPage(1)), "第 2 页指定，必须有切片章");
            assertFalse(pageHasImage(doc.getPage(2)), "第 3 页未指定，不应有章");
            assertTrue(pageHasImage(doc.getPage(3)), "第 4 页指定，必须有切片章");
        }
    }

    @Test
    @DisplayName("测试左侧与顶部边缘贴合压印：支持多样化骑缝方向")
    void testStampCrossPageSealLeftAndTopEdges() throws Exception {
        byte[] originalPdf = createMultiPagePdf(2);

        // 左边缘测试
        CrossPageSealOptions leftOptions = CrossPageSealOptions.of(sealPngBytes, CrossPageSealEdge.LEFT);
        byte[] leftPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, leftOptions);
        try (PDDocument doc = Loader.loadPDF(leftPdf)) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(pageHasImage(doc.getPage(0)));
            assertTrue(pageHasImage(doc.getPage(1)));
        }

        // 顶边缘测试
        CrossPageSealOptions topOptions = CrossPageSealOptions.of(sealPngBytes, CrossPageSealEdge.TOP);
        byte[] topPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, topOptions);
        try (PDDocument doc = Loader.loadPDF(topPdf)) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(pageHasImage(doc.getPage(0)));
            assertTrue(pageHasImage(doc.getPage(1)));
        }
    }

    @Test
    @DisplayName("测试输入源支持 BufferedImage 直接作为印章源")
    void testStampCrossPageSealWithBufferedImageSource() throws Exception {
        byte[] originalPdf = createMultiPagePdf(2);

        BufferedImage img = new BufferedImage(150, 150, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillOval(5, 5, 140, 140);
        } finally {
            g.dispose();
        }

        byte[] resultPdf = pdfTemplate.stampCrossPageSealToBytes(originalPdf, img);
        assertNotNull(resultPdf);

        try (PDDocument doc = Loader.loadPDF(resultPdf)) {
            assertEquals(2, doc.getNumberOfPages());
            assertTrue(pageHasImage(doc.getPage(0)));
            assertTrue(pageHasImage(doc.getPage(1)));
        }
    }

    @Test
    @DisplayName("测试对抗性边界防卫：空入参、页数越界、加密文档拒绝与页数不足异常")
    void testFailFastAndDefensiveGuards() throws Exception {
        byte[] originalPdf = createMultiPagePdf(2);

        // 1. 空入参防御
        assertThrows(NullPointerException.class, () ->
                pdfTemplate.stampCrossPageSeal(null, new ByteArrayOutputStream(), CrossPageSealOptions.of(sealPngBytes)));
        assertThrows(NullPointerException.class, () ->
                pdfTemplate.stampCrossPageSeal(originalPdf, null, CrossPageSealOptions.of(sealPngBytes)));
        assertThrows(NullPointerException.class, () ->
                pdfTemplate.stampCrossPageSeal(originalPdf, new ByteArrayOutputStream(), (CrossPageSealOptions) null));

        // 2. 空印章图片防御
        CrossPageSealOptions nullImageOptions = new CrossPageSealOptions();
        assertThrows(NullPointerException.class, () ->
                pdfTemplate.stampCrossPageSeal(originalPdf, new ByteArrayOutputStream(), nullImageOptions));

        // 3. 2 页文档同时跳过首页与末页导致有效页数为 0
        CrossPageSealOptions invalidSkipOptions = CrossPageSealOptions.builder()
                .sealImage(sealPngBytes)
                .skipCover(true)
                .skipBackCover(true)
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                pdfTemplate.stampCrossPageSeal(originalPdf, new ByteArrayOutputStream(), invalidSkipOptions));

        // 4. 自定义目标页码越界
        CrossPageSealOptions outOfBoundsOptions = CrossPageSealOptions.builder()
                .sealImage(sealPngBytes)
                .targetPages(List.of(0, 10))
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                pdfTemplate.stampCrossPageSeal(originalPdf, new ByteArrayOutputStream(), outOfBoundsOptions));

        // 5. 加密文档未解密拒绝盖骑缝章
        SecurityOptions sec = SecurityOptions.builder()
                .userPassword("secret123")
                .ownerPassword("admin123")
                .build();
        byte[] encryptedPdf = pdfTemplate.protectToBytes(originalPdf, sec);
        CrossPageSealOptions sealOpt = CrossPageSealOptions.of(sealPngBytes);
        PdfOperationException ex = assertThrows(PdfOperationException.class, () ->
                pdfTemplate.stampCrossPageSeal(encryptedPdf, new ByteArrayOutputStream(), sealOpt));
        assertEquals(DocumentResultCode.PASSWORD_ERROR.getCode(), ex.getCode());

        // 6. 印章尺寸非正数前置防御
        assertThrows(IllegalArgumentException.class, () ->
                CrossPageSealOptions.of(sealPngBytes, -10.0f, 120.0f));
        assertThrows(IllegalArgumentException.class, () ->
                CrossPageSealOptions.of(sealPngBytes, 120.0f, 0.0f));

        // 7. 配置对象不可变性防御：避免重载突变外部传入的 options
        CrossPageSealOptions originalOptions = CrossPageSealOptions.builder()
                .width(100.0f)
                .height(100.0f)
                .build();
        assertNull(originalOptions.getSealImage());
        pdfTemplate.stampCrossPageSeal(originalPdf, new ByteArrayOutputStream(), sealPngBytes, originalOptions);
        assertNull(originalOptions.getSealImage(), "重载调用严禁突变外部传入的 options 对象");
    }
}
