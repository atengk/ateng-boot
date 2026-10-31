package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.PdfRenderOptions;
import io.github.atengk.boot.document.properties.DocumentProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PdfTemplate PDF 页面转高清图像/缩略图与防 OOM 流式治理单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class PdfTemplateRenderTest {

    private static DocumentProperties createTestProperties() {
        DocumentProperties props = new DocumentProperties();
        props.getPdf().setFontPath("classpath:fonts/test-cjk.ttf");
        return props;
    }

    private final PdfTemplate pdfTemplate = new PdfTemplate(createTestProperties());

    @AfterEach
    void tearDown() {
        UserContextHolder.set(null);
        MDC.clear();
    }

    @Test
    @DisplayName("测试 renderPageToImage 与 renderPageToPng 单页转高清图像")
    void testRenderSinglePage() throws Exception {
        byte[] pdfBytes = createSampleMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        // 1. 渲染为 BufferedImage (第 0 页，150 DPI)
        BufferedImage image0 = pdfTemplate.renderPageToImage(pdfBytes, 0, 150.0f);
        assertThat(image0).isNotNull();
        assertThat(image0.getWidth()).isGreaterThan(500);
        assertThat(image0.getHeight()).isGreaterThan(700);

        // 2. 渲染最后一页（负数索引 -1 适配）为 PNG 字节数组
        byte[] pngBytes = pdfTemplate.renderPageToPng(pdfBytes, -1);
        assertThat(pngBytes).isNotEmpty();

        // 3. 测试首页面参数缺省重载与 renderPageToBytes 别名对齐
        BufferedImage defaultFirstPage = pdfTemplate.renderPageToImage(pdfBytes);
        assertThat(defaultFirstPage).isNotNull();
        assertThat(defaultFirstPage.getWidth()).isEqualTo(image0.getWidth());

        byte[] firstPageBytes = pdfTemplate.renderPageToBytes(pdfBytes);
        assertThat(firstPageBytes).isNotEmpty();

        // 4. 测试 DPI Fail-Fast 前置拦截
        assertThatThrownBy(() -> PdfRenderOptions.ofDpi(60.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出安全范围");
        assertThatThrownBy(() -> PdfRenderOptions.ofDpi(700.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出安全范围");

        // 校验生成的 PNG 二进制确实能够被 ImageIO 正常解码
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(pngBytes));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(image0.getWidth());
    }

    @Test
    @DisplayName("测试 renderAllPagesToPng 批量渲染与 maxPages 防 OOM 熔断")
    void testRenderAllPagesAndSafetyCeiling() {
        byte[] pdfBytes = createSampleMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        // 1. 正常渲染全部 2 页
        List<byte[]> allPngs = pdfTemplate.renderAllPagesToPng(pdfBytes);
        assertThat(allPngs).hasSize(2);
        assertThat(allPngs.get(0)).isNotEmpty();
        assertThat(allPngs.get(1)).isNotEmpty();

        // 2. 设置安全上限 maxPages = 1，触发防 OOM 熔断
        PdfRenderOptions restrictedOptions = PdfRenderOptions.builder()
                .dpi(72.0f)
                .maxPages(1)
                .build();

        assertThatThrownBy(() -> pdfTemplate.renderAllPagesToBytes(pdfBytes, restrictedOptions))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PDF_RENDER_EXCEEDED_MAX_PAGES);
                    assertThat(poe.getMessage()).contains("超过单次安全上限");
                });
    }

    @Test
    @DisplayName("测试 renderPages 流式逐页消费与即时 flush 内存释放")
    void testRenderPagesStreaming() {
        byte[] pdfBytes = createSampleMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        List<Integer> consumedPages = new ArrayList<>();
        AtomicInteger totalWidth = new AtomicInteger();

        // 流式逐页处理：消费并验证每页编号
        pdfTemplate.renderPages(pdfBytes, PdfRenderOptions.thumbnail(), (pageNumber, image) -> {
            consumedPages.add(pageNumber);
            totalWidth.addAndGet(image.getWidth());
            // 验证每页图像均有效
            assertThat(image.getWidth()).isGreaterThan(0);
        });

        assertThat(consumedPages).containsExactly(1, 2);
        assertThat(totalWidth.get()).isGreaterThan(0);

        // 指定页码范围流式渲染（仅渲染第 2 页）
        List<Integer> rangePages = new ArrayList<>();
        pdfTemplate.renderPages(pdfBytes, 2, 2, 72.0f, (pageNumber, image) -> rangePages.add(pageNumber));
        assertThat(rangePages).containsExactly(2);
    }

    @Test
    @DisplayName("测试页面索引越界拦截与 DPI 边界参数防卫")
    void testDefensiveValidation() {
        byte[] pdfBytes = createSampleMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        // 1. 页面索引越界（总共 2 页，请求第 5 页）
        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(pdfBytes, 5))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS);
                });

        // 负数越界（请求 -5）
        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(pdfBytes, -5))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS);
                });

        // 2. DPI 超出安全范围 [72.0, 600.0]
        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(pdfBytes, 0, 50.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DPI 超出安全范围");

        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(pdfBytes, 0, 800.0f))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DPI 超出安全范围");

        // 3. source 为空防御
        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(null, 0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        // 4. 流式 consumer 为空防御
        assertThatThrownBy(() -> pdfTemplate.renderPages(pdfBytes, PdfRenderOptions.defaults(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("consumer 不能为空");
    }

    @Test
    @DisplayName("测试加密 PDF 未提供密码时的渲染拦截")
    void testEncryptedPdfRejection() {
        byte[] original = createSampleMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");
        byte[] encrypted = pdfTemplate.protectToBytes(original, "userPass123", "ownerPass456");

        assertThatThrownBy(() -> pdfTemplate.renderPageToPng(encrypted, 0))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PASSWORD_ERROR);
                });
    }

    private static byte[] createSampleMultiPagePdf(String page1Text, String page2Text) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            DocumentProperties props = createTestProperties();
            DocumentFontResolver resolver = new DocumentFontResolver(props.getPdf());
            var font = resolver.loadFont(doc);

            // Page 1
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            try (PDPageContentStream cs1 = new PDPageContentStream(doc, page1)) {
                cs1.beginText();
                cs1.setFont(font, 14);
                cs1.newLineAtOffset(50, 750);
                cs1.showText(page1Text);
                cs1.endText();
            }

            // Page 2
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            try (PDPageContentStream cs2 = new PDPageContentStream(doc, page2)) {
                cs2.beginText();
                cs2.setFont(font, 14);
                cs2.newLineAtOffset(50, 750);
                cs2.showText(page2Text);
                cs2.endText();
            }

            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成多页测试 PDF 失败: " + e.getMessage(), e);
        }
    }
}
