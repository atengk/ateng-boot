package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PdfTemplate 操作门面全场景单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PdfTemplateTest {

    private static byte[] sealPngBytes;
    private final PdfTemplate pdfTemplate = new PdfTemplate();

    @BeforeAll
    static void setupAssets() throws IOException {
        try (InputStream is = PdfTemplateTest.class.getResourceAsStream("/images/seal.png")) {
            if (is != null) {
                sealPngBytes = is.readAllBytes();
            } else {
                sealPngBytes = new byte[]{1, 2, 3, 4};
            }
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.set(null);
        MDC.clear();
    }

    @Test
    @DisplayName("测试添加防截屏平铺暗水印：验证文本网格与全文提取")
    void testAddWatermark(@TempDir Path tempDir) throws Exception {
        byte[] originalPdf = createSamplePdf("机密报告：Q3 季度核心业务财务指标分析");

        WatermarkOptions options = WatermarkOptions.builder()
                .text("内部绝密 禁止外传")
                .opacity(0.2f)
                .rotation(-30.0f)
                .fontSize(20.0f)
                .color("#FF0000")
                .layer(WatermarkLayer.OVERLAY)
                .build();

        Path targetPdfPath = tempDir.resolve("watermarked.pdf");
        pdfTemplate.addWatermark(originalPdf, targetPdfPath, options);

        assertThat(Files.exists(targetPdfPath)).isTrue();
        assertThat(Files.size(targetPdfPath)).isGreaterThan(0);

        // 提取文本验证
        String extracted = pdfTemplate.extractText(targetPdfPath);
        assertThat(extracted).contains("机密报告");
        assertThat(extracted).contains("内部绝密 禁止外传");
    }

    @Test
    @DisplayName("测试水印动态宏变量解析与手机号脱敏掩码集成渲染")
    void testWatermarkDynamicMacros() throws Exception {
        byte[] originalPdf = createSamplePdf("劳动用工合同正文");

        MDC.put("traceId", "tr-8888");
        WatermarkOptions options = WatermarkOptions.builder()
                .text("操作人：#{user.nickname} 电话：#{user.phone} Trace: #{traceId}")
                .build();
        options.setUser("王五", "wangwu", "13987654321");

        byte[] resultBytes = pdfTemplate.addWatermarkToBytes(originalPdf, options);
        assertThat(resultBytes).isNotEmpty();

        String extracted = pdfTemplate.extractText(resultBytes);
        assertThat(extracted).contains("操作人：王五");
        assertThat(extracted).contains("电话：139****4321");
        assertThat(extracted).contains("Trace: tr-8888");
    }

    @Test
    @DisplayName("测试在指定页绝对物理坐标压印电子公章（Image Stamp）")
    void testAddImageStamp(@TempDir Path tempDir) throws Exception {
        // 创建 2 页 PDF 文档
        byte[] multiPagePdf = createMultiPagePdf("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        // 在第 2 页（最后一页，使用 -1 索引），坐标 (300, 100) 处盖章，尺寸 80x80
        ImageStampOptions stampOptions = ImageStampOptions.of(sealPngBytes, -1, 300.0f, 100.0f, 80.0f, 80.0f);
        stampOptions.setOpacity(0.9f);

        File outputFile = tempDir.resolve("stamped.pdf").toFile();
        pdfTemplate.addImageStamp(multiPagePdf, outputFile, stampOptions);

        assertThat(outputFile).exists().isNotEmpty();

        // 验证目标 PDF 第二页存在嵌入的图片对象
        try (PDDocument doc = Loader.loadPDF(outputFile)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(2);
            PDPage page2 = doc.getPage(1);

            boolean hasImage = false;
            Iterator<org.apache.pdfbox.cos.COSName> xObjectNames = page2.getResources().getXObjectNames().iterator();
            while (xObjectNames.hasNext()) {
                PDXObject xObject = page2.getResources().getXObject(xObjectNames.next());
                if (xObject instanceof PDImageXObject) {
                    hasImage = true;
                    break;
                }
            }
            assertThat(hasImage).isTrue();
        }
    }

    @Test
    @DisplayName("测试超限小内存阈值防 OOM：自动使用临时文件模式并在完成时物理销毁")
    void testInMemoryThresholdOverflowAndCleanup(@TempDir Path tempDir) throws Exception {
        // 创建仅为 100 字节阈值的配置实例
        DocumentProperties properties = new DocumentProperties();
        properties.setInMemoryThreshold(DataSize.ofBytes(100));
        properties.setTempDir(tempDir.toString());

        DocumentFontResolver fontResolver = new DocumentFontResolver(properties.getPdf());
        PdfTemplate customTemplate = new PdfTemplate(properties, fontResolver);

        byte[] originalPdf = createSamplePdf("测试大文件内存溢出保护机制");
        assertThat(originalPdf.length).isGreaterThan(100);

        byte[] outBytes = customTemplate.addWatermarkToBytes(originalPdf, "溢出防OOM测试");
        assertThat(outBytes).isNotEmpty();

        // 验证临时目录中无泄漏残留文件（已被确定性物理销毁）
        try (var stream = Files.list(tempDir)) {
            long remainingFiles = stream.count();
            assertThat(remainingFiles).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("对抗性防卫性测试：空入参、越界页码与非法源路径快速失败")
    void testAdversarialEdgeCases() {
        // 1. 空入参防卫
        assertThatThrownBy(() -> pdfTemplate.addWatermark(null, new ByteArrayOutputStream()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PDF 输入源 source 不能为空");

        assertThatThrownBy(() -> pdfTemplate.addWatermark(new byte[10], null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("目标输出 target 不能为空");

        assertThatThrownBy(() -> pdfTemplate.addImageStamp(new byte[10], new ByteArrayOutputStream(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("盖章选项 options 不能为空");

        // 2. 盖章页码越界
        byte[] simplePdf = createSamplePdf("单页文档");
        ImageStampOptions outOfBoundsOptions = ImageStampOptions.of(sealPngBytes, 5, 100, 100, 50, 50);
        assertThatThrownBy(() -> pdfTemplate.addImageStamp(simplePdf, new ByteArrayOutputStream(), outOfBoundsOptions))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("目标页面索引超出范围");

        // 3. 不存在的物理文件路径
        assertThatThrownBy(() -> pdfTemplate.extractText("not/exist/path.pdf"))
                .isInstanceOf(PdfOperationException.class);
    }

    private static byte[] createSamplePdf(String content) {
        return createSamplePdfWithCJK(content);
    }

    private static byte[] createSamplePdfWithCJK(String content) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            DocumentProperties props = new DocumentProperties();
            DocumentFontResolver resolver = new DocumentFontResolver(props.getPdf());
            var font = resolver.loadFont(doc);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(font, 14);
                cs.newLineAtOffset(50, 750);
                cs.showText(content);
                cs.endText();
            }

            doc.save(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("生成测试 PDF 失败: " + e.getMessage(), e);
        }
    }

    private static byte[] createMultiPagePdf(String page1Text, String page2Text) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            DocumentProperties props = new DocumentProperties();
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
