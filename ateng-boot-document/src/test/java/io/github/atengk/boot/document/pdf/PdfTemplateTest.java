package io.github.atengk.boot.document.pdf;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.document.enums.PageNumberPosition;
import io.github.atengk.boot.document.enums.WatermarkLayer;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.font.DocumentFontResolver;
import io.github.atengk.boot.document.pdf.model.ImageStampOptions;
import io.github.atengk.boot.document.pdf.model.PageNumberOptions;
import io.github.atengk.boot.document.pdf.model.PdfMetadata;
import io.github.atengk.boot.document.pdf.model.SecurityOptions;
import io.github.atengk.boot.document.pdf.model.WatermarkOptions;
import io.github.atengk.boot.document.properties.DocumentProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
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
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PdfTemplate 操作门面全场景单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class PdfTemplateTest {

    private static DocumentProperties createTestProperties() {
        DocumentProperties props = new DocumentProperties();
        props.getPdf().setFontPath("classpath:fonts/test-cjk.ttf");
        return props;
    }

    private static byte[] sealPngBytes;
    private final PdfTemplate pdfTemplate = new PdfTemplate(createTestProperties());

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
        UserContextHolder.clear();
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
        String normalized = extracted.replace("\r", "").replace("\n", "");
        assertThat(normalized).contains("内部绝密 禁止外传");

        // 验证 UNDERLAY 底层模式水印生成
        WatermarkOptions underlayOptions = WatermarkOptions.builder()
                .text("底图背景水印")
                .layer(WatermarkLayer.UNDERLAY)
                .build();
        byte[] underlayPdf = pdfTemplate.addWatermarkToBytes(originalPdf, underlayOptions);
        assertThat(underlayPdf).isNotEmpty();
        assertThat(pdfTemplate.extractText(underlayPdf).replace("\r", "").replace("\n", "")).contains("底图背景水印");

        // 验证 copySourceToTarget 时不关闭外部 OutputStream
        boolean[] streamClosed = new boolean[1];
        ByteArrayOutputStream customBaos = new ByteArrayOutputStream() {
            @Override
            public void close() throws IOException {
                streamClosed[0] = true;
                super.close();
            }
        };
        pdfTemplate.addWatermark(originalPdf, customBaos, WatermarkOptions.builder().text("   ").build());
        assertThat(streamClosed[0]).isFalse();
        assertThat(customBaos.size()).isGreaterThan(0);
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
        String normalized = extracted.replace("\r", "").replace("\n", "");
        assertThat(normalized).contains("操作人：王五");
        assertThat(normalized).contains("电话：139****4321");
        assertThat(normalized).contains("Trace: tr-8888");
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
        DocumentProperties properties = createTestProperties();
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
    @DisplayName("测试大文件解析异常时临时文件被确定性物理清理")
    void testTempFileCleanedOnCorruptedPdf(@TempDir Path tempDir) {
        DocumentProperties properties = createTestProperties();
        properties.setInMemoryThreshold(DataSize.ofBytes(10));
        properties.setTempDir(tempDir.toString());
        PdfTemplate customTemplate = new PdfTemplate(properties);

        byte[] corruptBytes = "CORRUPT_PDF_STREAM_MORE_THAN_10_BYTES".getBytes();
        assertThatThrownBy(() -> customTemplate.extractText(corruptBytes))
                .isInstanceOf(Exception.class);

        try (var stream = Files.list(tempDir)) {
            assertThat(stream.count()).isEqualTo(0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("测试多个独立 PDF 顺序物理合并为单一文档")
    void testMergeMultiplePdfs(@TempDir Path tempDir) throws Exception {
        byte[] pdf1 = createSamplePdf("第一部分：合同主体信息");
        byte[] pdf2 = createSamplePdf("第二部分：采购设备清单");
        byte[] pdf3 = createSamplePdf("第三部分：售后服务承诺");

        Path mergedPath = tempDir.resolve("merged.pdf");
        pdfTemplate.merge(mergedPath, List.of(pdf1, pdf2, pdf3));

        assertThat(Files.exists(mergedPath)).isTrue();
        assertThat(pdfTemplate.getPageCount(mergedPath)).isEqualTo(3);

        String fullText = pdfTemplate.extractText(mergedPath);
        assertThat(fullText).contains("第一部分：合同主体信息");
        assertThat(fullText).contains("第二部分：采购设备清单");
        assertThat(fullText).contains("第三部分：售后服务承诺");
    }

    @Test
    @DisplayName("测试 PDF 文档逐页物理拆分并导出至目标目录")
    void testSplitPdf(@TempDir Path tempDir) {
        byte[] multiPdf = createMultiPagePdf("第一页概要", "第二页详细条款");
        Path splitDir = tempDir.resolve("splits");

        List<File> files = pdfTemplate.split(multiPdf, splitDir, "doc_page_");

        assertThat(files).hasSize(2);
        assertThat(files.get(0)).exists().hasName("doc_page_1.pdf");
        assertThat(files.get(1)).exists().hasName("doc_page_2.pdf");

        assertThat(pdfTemplate.extractText(files.get(0))).contains("第一页概要");
        assertThat(pdfTemplate.extractText(files.get(1))).contains("第二页详细条款");
    }

    @Test
    @DisplayName("测试提取 PDF 文档指定页码范围与页面索引")
    void testExtractPages() {
        byte[] multiPdf = createMultiPagePdf("Page 1 Summary", "Page 2 Terms");

        // 提取第 2 页 (0-based 索引为 1)
        byte[] extractedBytes = pdfTemplate.extractPagesToBytes(multiPdf, 1);
        assertThat(pdfTemplate.getPageCount(extractedBytes)).isEqualTo(1);
        assertThat(pdfTemplate.extractText(extractedBytes)).contains("Page 2 Terms");

        // 提取 1-based 页码范围 1 到 2 (支持显式 extractPageRange 与兼容重载 extractPages)
        byte[] rangeBytes = pdfTemplate.extractPageRangeToBytes(multiPdf, 1, 2);
        assertThat(pdfTemplate.getPageCount(rangeBytes)).isEqualTo(2);

        // 提取 0-based 索引 0 与 1（验证重载自适应消歧）
        byte[] zeroBasedBytes = pdfTemplate.extractPagesToBytes(multiPdf, 0, 1);
        assertThat(pdfTemplate.getPageCount(zeroBasedBytes)).isEqualTo(2);
    }

    @Test
    @DisplayName("测试页面 90°/180°/270° 旋转校准")
    void testRotatePages() throws Exception {
        byte[] multiPdf = createMultiPagePdf("页面1", "页面2");

        // 将第 1 页 (0-based 索引为 0) 旋转 90 度
        byte[] rotatedBytes = pdfTemplate.rotatePagesToBytes(multiPdf, 90, 0);

        try (PDDocument doc = Loader.loadPDF(rotatedBytes)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
            assertThat(doc.getPage(1).getRotation()).isEqualTo(0);
        }

        // 旋转全部页面 180 度
        byte[] allRotatedBytes = pdfTemplate.rotatePagesToBytes(multiPdf, 180);
        try (PDDocument doc = Loader.loadPDF(allRotatedBytes)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(180);
            assertThat(doc.getPage(1).getRotation()).isEqualTo(180);
        }
    }

    @Test
    @DisplayName("测试页码压印与跳过封面页码重算")
    void testAddPageNumbersWithSkipCover() {
        // 创建 3 页 PDF：第 1 页为封面，第 2、3 页为正文
        byte[] doc3Pages;
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            DocumentProperties props = createTestProperties();
            DocumentFontResolver resolver = new DocumentFontResolver(props.getPdf());
            var font = resolver.loadFont(doc);

            for (int i = 1; i <= 3; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(font, 14);
                    cs.newLineAtOffset(50, 750);
                    cs.showText("内容第 " + i + " 页");
                    cs.endText();
                }
            }
            doc.save(baos);
            doc3Pages = baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 跳过 1 页封面，重新计算总页数为 2 页
        PageNumberOptions options = PageNumberOptions.builder()
                .skipPages(1)
                .recalculateTotal(true)
                .format("第 {page} 页 / 共 {total} 页")
                .position(PageNumberPosition.BOTTOM_CENTER)
                .build();

        byte[] resultBytes = pdfTemplate.addPageNumbersToBytes(doc3Pages, options);
        assertThat(resultBytes).isNotEmpty();

        // 验证文本提取：封面无页码，正文显示 第 1 页 / 共 2 页、第 2 页 / 共 2 页
        String extracted = pdfTemplate.extractText(resultBytes);
        assertThat(extracted).contains("第 1 页 / 共 2 页");
        assertThat(extracted).contains("第 2 页 / 共 2 页");

        // 测试 recalculateTotal = false：显示总页数为原总页数 3 页，且当前页码依然从 1 开始
        PageNumberOptions optionsNoRecalc = PageNumberOptions.builder()
                .skipPages(1)
                .recalculateTotal(false)
                .format("第 {page} 页 / 共 {total} 页")
                .position(PageNumberPosition.BOTTOM_CENTER)
                .build();
        byte[] resultBytesNoRecalc = pdfTemplate.addPageNumbersToBytes(doc3Pages, optionsNoRecalc);
        String extractedNoRecalc = pdfTemplate.extractText(resultBytesNoRecalc);
        assertThat(extractedNoRecalc).contains("第 1 页 / 共 3 页");
        assertThat(extractedNoRecalc).contains("第 2 页 / 共 3 页");
    }

    @Test
    @DisplayName("测试文档密码安全加密与复制/打印权限控制锁")
    void testProtectWithSecurityOptionsAndUnprotect() throws Exception {
        byte[] original = createSamplePdf("高安全等级涉密文档，严禁泄露与打印");

        // 施加密码锁：打开密码 "user123"，权限密码 "owner888"，禁止打印，禁止复制文本
        SecurityOptions options = SecurityOptions.builder()
                .userPassword("user123")
                .ownerPassword("owner888")
                .canPrint(false)
                .canCopy(false)
                .build();

        byte[] protectedBytes = pdfTemplate.protectToBytes(original, options);
        assertThat(pdfTemplate.isEncrypted(protectedBytes)).isTrue();

        // 1. 无密码无法读取
        assertThatThrownBy(() -> Loader.loadPDF(protectedBytes))
                .isInstanceOf(Exception.class);

        // 2. 使用正确密码加载并验证权限
        try (PDDocument doc = Loader.loadPDF(protectedBytes, "user123")) {
            assertThat(doc.isEncrypted()).isTrue();
            assertThat(doc.getCurrentAccessPermission().canPrint()).isFalse();
            assertThat(doc.getCurrentAccessPermission().canExtractContent()).isFalse();
        }

        // 3. 解除密码保护 (Unprotect)
        byte[] unprotectedBytes = pdfTemplate.unprotectToBytes(protectedBytes, "owner888");
        assertThat(pdfTemplate.isEncrypted(unprotectedBytes)).isFalse();

        // 验证解除后无密码直接可读
        try (PDDocument doc = Loader.loadPDF(unprotectedBytes)) {
            assertThat(doc.isEncrypted()).isFalse();
            assertThat(doc.getCurrentAccessPermission().canPrint()).isTrue();
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

        // 4. 旋转角度非法
        assertThatThrownBy(() -> pdfTemplate.rotatePages(simplePdf, new ByteArrayOutputStream(), 45))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("旋转角度 angle 必须为 90 的整数倍");

        // 5. 合并空列表
        assertThatThrownBy(() -> pdfTemplate.merge(new ByteArrayOutputStream(), (List<?>) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("待合并的 PDF 文档输入列表 pdfSources 不能为空");

        // 6. 提取页面范围非法
        assertThatThrownBy(() -> pdfTemplate.extractPages(simplePdf, new ByteArrayOutputStream(), 5, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("页码范围无效");
    }

    @Test
    @DisplayName("测试核心元数据自省 inspectMetadata 与按页码范围纯文本提取 extractText")
    void testInspectMetadataAndExtractTextRange() throws Exception {
        byte[] pdfBytes = createPdfWithMetadata("第一页：合同条款细则", "第二页：甲乙双方盖章确认处");

        // 1. 核心元数据自省全真断言
        PdfMetadata metadata = pdfTemplate.inspectMetadata(pdfBytes);
        assertThat(metadata).isNotNull();
        assertThat(metadata.getPageCount()).isEqualTo(2);
        assertThat(metadata.getVersion()).isGreaterThan(0.0f);
        assertThat(metadata.getTitle()).isEqualTo("合同条款与盖章确认文件");
        assertThat(metadata.getAuthor()).isEqualTo("Ateng");
        assertThat(metadata.getSubject()).isEqualTo("合同管理");
        assertThat(metadata.getKeywords()).isEqualTo("合同, 条款, 盖章");
        assertThat(metadata.getCreator()).isEqualTo("ateng-boot-document");
        assertThat(metadata.getProducer()).isEqualTo("Apache PDFBox");
        assertThat(metadata.getCreationDate()).isNotNull();
        assertThat(metadata.getModificationDate()).isNotNull();
        assertThat(metadata.isEncrypted()).isFalse();
        assertThat(metadata.isCanPrint()).isTrue();
        assertThat(metadata.isCanModify()).isTrue();
        assertThat(metadata.isCanExtractContent()).isTrue();
        assertThat(metadata.isCanCopy()).isTrue();
        assertThat(metadata.isHasAcroForm()).isFalse();
        assertThat(metadata.isHasXfa()).isFalse();

        // 2. 全文文本提取
        String fullText = pdfTemplate.extractText(pdfBytes);
        assertThat(fullText).contains("第一页：合同条款细则");
        assertThat(fullText).contains("第二页：甲乙双方盖章确认处");

        // 3. 按页提取文本：仅第 1 页
        String page1Only = pdfTemplate.extractText(pdfBytes, 1, 1);
        assertThat(page1Only).contains("第一页：合同条款细则");
        assertThat(page1Only).doesNotContain("第二页：甲乙双方盖章确认处");

        // 4. 按页提取文本：仅第 2 页
        String page2Only = pdfTemplate.extractText(pdfBytes, 2, 2);
        assertThat(page2Only).contains("第二页：甲乙双方盖章确认处");
        assertThat(page2Only).doesNotContain("第一页：合同条款细则");
    }

    @Test
    @DisplayName("测试加密 PDF 的元数据自省与密码解密文本提取")
    void testInspectMetadataEncryptedWithPassword() throws Exception {
        byte[] originalPdf = createSamplePdf("绝密核心商业机密正文内容");
        String userPass = "userPass123";
        String ownerPass = "ownerPass456";

        byte[] protectedPdf = pdfTemplate.protectToBytes(originalPdf, userPass, ownerPass);

        // 1. 凭正确密码自省元数据
        PdfMetadata metadata = pdfTemplate.inspectMetadata(protectedPdf, userPass);
        assertThat(metadata).isNotNull();
        assertThat(metadata.isEncrypted()).isTrue();
        assertThat(metadata.getPageCount()).isEqualTo(1);

        // 2. 凭正确密码提取纯文本
        String extracted = pdfTemplate.extractText(protectedPdf, userPass);
        assertThat(extracted).contains("绝密核心商业机密正文内容");

        // 3. 错误密码拦截防卫
        assertThatThrownBy(() -> pdfTemplate.extractText(protectedPdf, "wrongPass999"))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PASSWORD_ERROR);
                });

        assertThatThrownBy(() -> pdfTemplate.inspectMetadata(protectedPdf, "wrongPass999"))
                .isInstanceOf(PdfOperationException.class)
                .satisfies(e -> {
                    PdfOperationException poe = (PdfOperationException) e;
                    assertThat(poe.getResultCode()).isEqualTo(DocumentResultCode.PASSWORD_ERROR);
                });
    }

    @Test
    @DisplayName("测试 inspectMetadata 与 extractText 前置参数校验与卫语句拦截")
    void testMetadataAndExtractTextDefensiveValidation() {
        byte[] simplePdf = createSamplePdf("参数防卫测试");

        // 1. source 为空拦截
        assertThatThrownBy(() -> pdfTemplate.inspectMetadata(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        assertThatThrownBy(() -> pdfTemplate.extractText(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source 不能为空");

        // 2. 起始页小于 1
        assertThatThrownBy(() -> pdfTemplate.extractText(simplePdf, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("startPage 必须大于等于 1");

        // 3. 结束页小于起始页
        assertThatThrownBy(() -> pdfTemplate.extractText(simplePdf, 2, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能小于起始页码");

        // 4. 起始页超出总页数拦截
        assertThatThrownBy(() -> pdfTemplate.extractText(simplePdf, 5, 5))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.PDF_PAGE_INDEX_OUT_OF_BOUNDS)
                .hasMessageContaining("超出文档总页数");
    }

    private static byte[] createPdfWithMetadata(String page1Text, String page2Text) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            DocumentProperties props = createTestProperties();
            DocumentFontResolver resolver = new DocumentFontResolver(props.getPdf());
            var font = resolver.loadFont(doc);

            // 设置元数据
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("合同条款与盖章确认文件");
            info.setAuthor("Ateng");
            info.setSubject("合同管理");
            info.setKeywords("合同, 条款, 盖章");
            info.setCreator("ateng-boot-document");
            info.setProducer("Apache PDFBox");
            Calendar now = Calendar.getInstance();
            info.setCreationDate(now);
            info.setModificationDate(now);
            doc.setDocumentInformation(info);

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
            throw new RuntimeException("生成带元数据测试 PDF 失败: " + e.getMessage(), e);
        }
    }

    private static byte[] createSamplePdf(String content) {
        return createSamplePdfWithCJK(content);
    }

    @Test
    @DisplayName("安全防线：PdfTemplate 加载体积超限拦截与 split 前缀路径穿越清洗")
    void testMaxDocumentSizeAndSplitPrefixSanitization(@TempDir Path tempDir) throws Exception {
        byte[] multiPagePdf = createMultiPagePdf("第一页安全测试", "第二页安全测试");

        DocumentProperties tinyProps = createTestProperties();
        tinyProps.setMaxDocumentSize(DataSize.ofBytes(128));
        PdfTemplate restrictedTemplate = new PdfTemplate(tinyProps, new DocumentFontResolver(tinyProps.getPdf()));

        assertThatThrownBy(() -> restrictedTemplate.getPageCount(multiPagePdf))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);

        assertThatThrownBy(() -> restrictedTemplate.getPageCount(new ByteArrayInputStream(multiPagePdf)))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);

        File pdfFile = tempDir.resolve("large.pdf").toFile();
        Files.write(pdfFile.toPath(), multiPagePdf);
        assertThatThrownBy(() -> restrictedTemplate.getPageCount(pdfFile))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);

        DocumentProperties normalProps = createTestProperties();
        PdfTemplate normalTemplate = new PdfTemplate(normalProps, new DocumentFontResolver(normalProps.getPdf()));
        Path splitDir = tempDir.resolve("split-safe");

        assertThatThrownBy(() -> normalTemplate.split(multiPagePdf, splitDir.toFile(), "../../.."))
                .isInstanceOf(IllegalArgumentException.class);

        List<File> splitFiles = normalTemplate.split(multiPagePdf, splitDir.toFile(), "../../evil_");
        assertThat(splitFiles).hasSize(2);
        for (File f : splitFiles) {
            assertThat(f.toPath().toAbsolutePath().normalize()).startsWith(splitDir.toAbsolutePath().normalize());
            assertThat(f.getName()).doesNotContain("..");
        }

        // merge 与 copySourceToTarget(resolveRawInputStream) 同等实施路径穿越与体积熔断
        assertThatThrownBy(() -> normalTemplate.merge(new ByteArrayOutputStream(), List.of("../../etc/passwd")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("路径穿越");

        assertThatThrownBy(() -> restrictedTemplate.merge(new ByteArrayOutputStream(), List.of(multiPagePdf, multiPagePdf)))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.DOCUMENT_SIZE_EXCEEDED);
    }

    @Test
    @DisplayName("安全防线：isEncrypted/hasAcroForm 不吞咽异常、protect 动态随机管理口令、CJK 缺字体前置快速失败与 0 页加页码")
    void testSecurityGuardsFailFastFontAndZeroPageStream(@TempDir Path tempDir) throws Exception {
        byte[] samplePdf = createSamplePdf("安全基线校验");

        // 1. isEncrypted & hasAcroForm 不再吞咽不存在或损坏文件异常
        assertThatThrownBy(() -> pdfTemplate.isEncrypted(tempDir.resolve("missing.pdf")))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.TEMPLATE_NOT_FOUND);

        assertThatThrownBy(() -> pdfTemplate.hasAcroForm(new byte[]{0x01, 0x02, 0x03}))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.PDF_OPERATION_FAILED);

        // 2. protect 不指定 ownerPassword 时不再使用历史硬编码口令
        byte[] lockedWithoutOwner = pdfTemplate.protectToBytes(samplePdf,
                SecurityOptions.builder().userPassword("").canPrint(false).build());
        assertThat(pdfTemplate.isEncrypted(lockedWithoutOwner)).isTrue();
        assertThatThrownBy(() -> pdfTemplate.unprotectToBytes(lockedWithoutOwner, "AtengDocSecurityOwnerPass#2026"))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.PASSWORD_ERROR);

        // 3. 缺中文字体时仅纯 ASCII 回退 HELVETICA，含中文时前置抛出 DocumentFontException (57003)
        DocumentProperties noFontProps = new DocumentProperties();
        noFontProps.getPdf().setFontPath("");
        DocumentFontResolver emptyDirResolver = new DocumentFontResolver(noFontProps.getPdf(), List.of(tempDir.toString()));
        PdfTemplate strictFontPdf = new PdfTemplate(noFontProps, emptyDirResolver);

        assertThat(strictFontPdf.addWatermarkToBytes(samplePdf, "INTERNAL ASCII ONLY")).isNotEmpty();
        assertThatThrownBy(() -> strictFontPdf.addWatermarkToBytes(samplePdf, "机密中文水印"))
                .isInstanceOf(io.github.atengk.boot.document.exception.DocumentFontException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.FONT_NOT_FOUND);

        assertThatThrownBy(() -> strictFontPdf.addPageNumbersToBytes(samplePdf,
                PageNumberOptions.builder().format("第 {page} 页").build()))
                .isInstanceOf(io.github.atengk.boot.document.exception.DocumentFontException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.FONT_NOT_FOUND);

        // 4. 0 页 PDF 以单次消费 InputStream 传入 addPageNumbers 不触发流耗尽错误
        byte[] zeroPagePdf;
        try (PDDocument emptyDoc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            emptyDoc.save(baos);
            zeroPagePdf = baos.toByteArray();
        }
        byte[] outZeroPage = pdfTemplate.addPageNumbersToBytes(new ByteArrayInputStream(zeroPagePdf));
        assertThat(outZeroPage).isNotEmpty();
        try (PDDocument verifiedEmpty = Loader.loadPDF(outZeroPage)) {
            assertThat(verifiedEmpty.getNumberOfPages()).isZero();
        }
    }

    @Test
    @DisplayName("双模旋转水印与多行校准：图片水印、页面过滤、\\n 换行水印、90°/180°/270° 旋转页与 CropBox 校准")
    void testDualModeMultilineWatermarkAndRotatedCropBoxCalibration() throws Exception {
        byte[] twoPagePdf = createMultiPagePdf("第一页正文", "第二页正文");

        // 1. 多行 \n 水印不报错且两行内容均可提取（含水平 0° 逐行验证与默认斜向 -30° 防崩溃验证）
        byte[] horizontalMultiline = pdfTemplate.addWatermarkToBytes(twoPagePdf,
                WatermarkOptions.builder().text("内部机密\n禁止外传").rotation(0.0f).build());
        String horizontalText = pdfTemplate.extractText(horizontalMultiline);
        assertThat(horizontalText).contains("内部机密").contains("禁止外传");

        byte[] angledMultiline = pdfTemplate.addWatermarkToBytes(twoPagePdf, "内部机密\n禁止外传");
        assertThat(pdfTemplate.extractText(angledMultiline).replaceAll("\\s+", ""))
                .contains("内部机密")
                .contains("禁止外传");

        // 2. 图片矩阵水印 + skipPages/targetPages 页面过滤
        byte[] imgWatermarked = pdfTemplate.addImageWatermarkToBytes(twoPagePdf, "classpath:images/seal.png",
                WatermarkOptions.builder()
                        .imageWidth(60.0f)
                        .imageHeight(60.0f)
                        .skipPages(1)
                        .targetPages(java.util.Set.of(2))
                        .opacity(0.25f)
                        .build());

        try (PDDocument doc = Loader.loadPDF(imgWatermarked)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isEmpty();
            assertThat(doc.getPage(1).getResources().getXObjectNames()).isNotEmpty();
        }

        // 3. 90° / 180° / 270° 旋转页面与非零 origin CropBox 的页码与水印可见区及端正方向校验
        for (int rotation : new int[]{90, 180, 270}) {
            byte[] rotatedPdf;
            try (PDDocument doc = new PDDocument(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                PDPage page = new PDPage(PDRectangle.A4);
                page.setRotation(rotation);
                page.setCropBox(new PDRectangle(20, 30, 500, 700));
                doc.addPage(page);
                doc.save(baos);
                rotatedPdf = baos.toByteArray();
            }

            byte[] stamped = pdfTemplate.addWatermarkToBytes(rotatedPdf, "UPRIGHT-WM");
            byte[] numbered = pdfTemplate.addPageNumbersToBytes(stamped,
                    PageNumberOptions.builder().format("P{page}/{total}").build());

            float visW = (rotation == 90 || rotation == 270) ? 700.0f : 500.0f;
            float visH = (rotation == 90 || rotation == 270) ? 500.0f : 700.0f;

            try (PDDocument verifyDoc = Loader.loadPDF(numbered)) {
                java.util.List<Float> pageNumX = new java.util.ArrayList<>();
                java.util.List<Float> pageNumY = new java.util.ArrayList<>();
                PDFTextStripper stripper = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, java.util.List<org.apache.pdfbox.text.TextPosition> textPositions) throws IOException {
                        if (text.contains("P1/1") && !textPositions.isEmpty()) {
                            pageNumX.add(textPositions.get(0).getXDirAdj());
                            pageNumY.add(textPositions.get(0).getYDirAdj());
                        }
                        super.writeString(text, textPositions);
                    }
                };
                String extracted = stripper.getText(verifyDoc);
                assertThat(extracted).contains("UPRIGHT-WM").contains("P1/1");
                assertThat(pageNumX).isNotEmpty();
                assertThat(pageNumX.get(0)).isBetween(0.0f, visW);
                assertThat(pageNumY.get(0)).isBetween(0.0f, visH);
            }
        }
    }

    @Test
    @DisplayName("关键字定位盖章：findTextPositions 与 stampAtKeyword 支持 FIRST/LAST/ALL 及缺失抛 KEYWORD_NOT_FOUND")
    void testFindTextPositionsAndStampAtKeyword() throws Exception {
        byte[] twoPageWithKeyword = createMultiPagePdf(
                "第一页：甲方盖章处 合同条款细则",
                "第二页：甲乙双方 确认 甲方盖章处"
        );

        var matches = pdfTemplate.findTextPositions(twoPageWithKeyword, "甲方盖章处");
        assertThat(matches).hasSize(2);
        assertThat(matches.get(0).getPageIndex()).isEqualTo(0);
        assertThat(matches.get(1).getPageIndex()).isEqualTo(1);
        assertThat(matches.get(0).getY()).isBetween(700.0f, 780.0f);

        // FIRST 只在第 1 页盖章
        byte[] firstStamped = pdfTemplate.stampAtKeywordToBytes(twoPageWithKeyword,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("甲方盖章处")
                        .stampImage("classpath:images/seal.png")
                        .width(60.0f)
                        .height(60.0f)
                        .occurrence(io.github.atengk.boot.document.enums.KeywordStampOccurrence.FIRST)
                        .build());
        try (PDDocument doc = Loader.loadPDF(firstStamped)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            assertThat(doc.getPage(1).getResources().getXObjectNames()).isEmpty();
        }

        // LAST 只在第 2 页盖章
        byte[] lastStamped = pdfTemplate.stampAtKeywordToBytes(twoPageWithKeyword,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("甲方盖章处")
                        .stampImage("classpath:images/seal.png")
                        .occurrence(io.github.atengk.boot.document.enums.KeywordStampOccurrence.LAST)
                        .build());
        try (PDDocument doc = Loader.loadPDF(lastStamped)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isEmpty();
            assertThat(doc.getPage(1).getResources().getXObjectNames()).isNotEmpty();
        }

        // ALL 在全部命中页盖章
        byte[] allStamped = pdfTemplate.stampAtKeywordToBytes(twoPageWithKeyword,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("甲方盖章处")
                        .stampImage("classpath:images/seal.png")
                        .occurrence(io.github.atengk.boot.document.enums.KeywordStampOccurrence.ALL)
                        .build());
        try (PDDocument doc = Loader.loadPDF(allStamped)) {
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();
            assertThat(doc.getPage(1).getResources().getXObjectNames()).isNotEmpty();
        }

        // 未命中默认抛 KEYWORD_NOT_FOUND，关闭 failIfNotFound 则原样保留
        assertThatThrownBy(() -> pdfTemplate.stampAtKeywordToBytes(twoPageWithKeyword,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("不存在的合同签章锚点")
                        .stampImage("classpath:images/seal.png")
                        .build()))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.KEYWORD_NOT_FOUND);

        byte[] noFailOut = pdfTemplate.stampAtKeywordToBytes(twoPageWithKeyword,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("不存在的合同签章锚点")
                        .stampImage("classpath:images/seal.png")
                        .failIfNotFound(false)
                        .build());
        assertThat(pdfTemplate.extractText(noFailOut)).contains("甲方盖章处");
    }

    @Test
    @DisplayName("物理不可逆涂黑脱敏：redactKeywords/redactAreas 使脱敏页文本不可提取且保留非脱敏页矢量")
    void testPhysicalRasterRedactionKeywordsAndAreas() throws Exception {
        byte[] twoPagePdf = createMultiPagePdf(
                "第一页 安全测试 110101199001011234 合同条款",
                "第二页：合同条款细则"
        );

        // 1. 按敏感关键字仅对第 1 页涂黑光栅化
        byte[] redactedByKeyword = pdfTemplate.redactKeywordsToBytes(twoPagePdf, List.of("110101199001011234"));
        try (PDDocument doc = Loader.loadPDF(redactedByKeyword)) {
            PDFTextStripper page1Stripper = new PDFTextStripper();
            page1Stripper.setStartPage(1);
            page1Stripper.setEndPage(1);
            String page1Text = page1Stripper.getText(doc);
            assertThat(page1Text).doesNotContain("110101199001011234");
            // 物理整页重封光栅图，脱敏页 XObject 为图片
            assertThat(doc.getPage(0).getResources().getXObjectNames()).isNotEmpty();

            PDFTextStripper page2Stripper = new PDFTextStripper();
            page2Stripper.setStartPage(2);
            page2Stripper.setEndPage(2);
            String page2Text = page2Stripper.getText(doc);
            assertThat(page2Text).contains("第二页：合同条款细则");
            assertThat(doc.getPage(1).getResources().getXObjectNames()).isEmpty();
        }

        // 2. 按物理坐标区域 redactAreas 对第 2 页涂黑
        byte[] redactedByArea = pdfTemplate.redactAreasToBytes(twoPagePdf,
                List.of(io.github.atengk.boot.document.pdf.model.RedactArea.builder()
                        .pageIndex(1).x(45).y(740).width(220).height(30).build()));
        try (PDDocument doc = Loader.loadPDF(redactedByArea)) {
            PDFTextStripper page1Stripper = new PDFTextStripper();
            page1Stripper.setStartPage(1);
            page1Stripper.setEndPage(1);
            assertThat(page1Stripper.getText(doc)).contains("110101199001011234");

            PDFTextStripper page2Stripper = new PDFTextStripper();
            page2Stripper.setStartPage(2);
            page2Stripper.setEndPage(2);
            assertThat(page2Stripper.getText(doc).trim()).isEmpty();
        }
    }

    @Test
    @DisplayName("页面编排与删插重排：deletePages/reorderPages/insertPages 语义与防删空守卫")
    void testPageOrchestrationDeleteReorderInsert() {
        byte[] doc3Pages = pdfTemplate.mergeToBytes(
                createSamplePdfWithCJK("Page-1"),
                createSamplePdfWithCJK("Page-2"),
                createSamplePdfWithCJK("Page-3")
        );
        byte[] insertDoc = createSamplePdfWithCJK("Insert-X");

        // 1. 删除最后一页 (-1) -> 剩余 Page-1, Page-2
        byte[] deleted = pdfTemplate.deletePagesToBytes(doc3Pages, -1);
        assertThat(pdfTemplate.getPageCount(deleted)).isEqualTo(2);
        assertThat(pdfTemplate.extractText(deleted)).contains("Page-1").contains("Page-2").doesNotContain("Page-3");

        // 不能删除全部页面
        assertThatThrownBy(() -> pdfTemplate.deletePagesToBytes(doc3Pages, 0, 1, 2))
                .isInstanceOf(IllegalArgumentException.class);

        // 2. 重排页面 [2, 0, 1] -> Page-3, Page-1, Page-2
        byte[] reordered = pdfTemplate.reorderPagesToBytes(doc3Pages, 2, 0, 1);
        assertThat(pdfTemplate.getPageCount(reordered)).isEqualTo(3);
        assertThat(pdfTemplate.extractText(reordered, 1, 1)).contains("Page-3");
        assertThat(pdfTemplate.extractText(reordered, 2, 2)).contains("Page-1");
        assertThat(pdfTemplate.extractText(reordered, 3, 3)).contains("Page-2");

        // 3. 在索引 1 前插入新页 -> Page-1, Insert-X, Page-2, Page-3
        byte[] inserted = pdfTemplate.insertPagesToBytes(doc3Pages, insertDoc, 1);
        assertThat(pdfTemplate.getPageCount(inserted)).isEqualTo(4);
        assertThat(pdfTemplate.extractText(inserted, 1, 1)).contains("Page-1");
        assertThat(pdfTemplate.extractText(inserted, 2, 2)).contains("Insert-X");
        assertThat(pdfTemplate.extractText(inserted, 3, 3)).contains("Page-2");
        assertThat(pdfTemplate.extractText(inserted, 4, 4)).contains("Page-3");
    }

    @Test
    @DisplayName("多图合成编排导出 PDF：imagesToPdf 支持标准 A4 自适应与按原图精确封装")
    void testImagesToPdfStandardAndFitToImage() throws Exception {
        java.awt.image.BufferedImage wideImg = new java.awt.image.BufferedImage(240, 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
        List<Object> sources = List.of("classpath:images/seal.png", wideImg);
        try {
            // 1. 标准 A4 居中自适应模式
            byte[] a4Pdf = pdfTemplate.imagesToPdfToBytes(sources);
            try (PDDocument doc = Loader.loadPDF(a4Pdf)) {
                assertThat(doc.getNumberOfPages()).isEqualTo(2);
                assertThat(doc.getPage(0).getMediaBox().getWidth()).isCloseTo(PDRectangle.A4.getWidth(), org.assertj.core.data.Offset.offset(1.0f));
                assertThat(doc.getPage(1).getResources().getXObjectNames()).isNotEmpty();
            }

            // 2. 按原图原始宽高精确成页模式
            byte[] fitPdf = pdfTemplate.imagesToPdfToBytes(sources,
                    io.github.atengk.boot.document.pdf.model.ImageToPdfOptions.fitToImage());
            try (PDDocument doc = Loader.loadPDF(fitPdf)) {
                assertThat(doc.getNumberOfPages()).isEqualTo(2);
                assertThat(doc.getPage(1).getMediaBox().getWidth()).isCloseTo(240.0f, org.assertj.core.data.Offset.offset(0.5f));
                assertThat(doc.getPage(1).getMediaBox().getHeight()).isCloseTo(120.0f, org.assertj.core.data.Offset.offset(0.5f));
            }
        } finally {
            wideImg.flush();
        }
    }

    @Test
    @DisplayName("元数据治理：updateMetadata 增量更新与 stripMetadata 彻底抹除")
    void testMetadataUpdateAndStrip() {
        byte[] sample = createSamplePdfWithCJK("MetadataTest");
        java.time.Instant ts = java.time.Instant.parse("2026-11-18T08:00:00Z");

        byte[] updated = pdfTemplate.updateMetadataToBytes(sample,
                io.github.atengk.boot.document.pdf.model.PdfMetadataUpdate.builder()
                        .title("工业级审计报告")
                        .author("Ateng")
                        .subject("PDF Governance")
                        .keywords("pdf,audit")
                        .creator("ateng-boot-document")
                        .producer("Apache PDFBox")
                        .creationDate(ts)
                        .build());

        PdfMetadata meta = pdfTemplate.inspectMetadata(updated);
        assertThat(meta.getTitle()).isEqualTo("工业级审计报告");
        assertThat(meta.getAuthor()).isEqualTo("Ateng");
        assertThat(meta.getKeywords()).isEqualTo("pdf,audit");
        assertThat(meta.getCreationDate()).isEqualTo(ts);

        // 增量局部只修改 title，author 保持不变
        byte[] patched = pdfTemplate.updateMetadataToBytes(updated,
                io.github.atengk.boot.document.pdf.model.PdfMetadataUpdate.builder()
                        .title("修订版审计报告")
                        .build());
        PdfMetadata patchedMeta = pdfTemplate.inspectMetadata(patched);
        assertThat(patchedMeta.getTitle()).isEqualTo("修订版审计报告");
        assertThat(patchedMeta.getAuthor()).isEqualTo("Ateng");

        // 彻底脱敏剥离元数据
        byte[] stripped = pdfTemplate.stripMetadataToBytes(patched);
        PdfMetadata cleanMeta = pdfTemplate.inspectMetadata(stripped);
        assertThat(cleanMeta.getTitle()).isNull();
        assertThat(cleanMeta.getAuthor()).isNull();
        assertThat(cleanMeta.getSubject()).isNull();
        assertThat(cleanMeta.getKeywords()).isNull();
        assertThat(cleanMeta.getCreator()).isNull();
    }

    @Test
    @DisplayName("全量口令穿透：加密 PDF 携带 password 贯穿水印、盖章、涂黑、编排、旋转、页码与渲染")
    void testFullPasswordPenetrationAcrossPdfTemplate() throws Exception {
        byte[] raw3Pages = pdfTemplate.mergeToBytes(
                createSamplePdfWithCJK("Alpha Anchor-1"),
                createSamplePdfWithCJK("Beta Anchor-2"),
                createSamplePdfWithCJK("Gamma Secret-999")
        );
        String pass = "DocSecret#2026";
        byte[] encrypted = pdfTemplate.protectToBytes(raw3Pages, pass, "OwnerStrong#2026");

        // 水印
        byte[] wm = pdfTemplate.addWatermarkToBytes(encrypted, WatermarkOptions.builder()
                .text("CONFIDENTIAL").password(pass).build());
        assertThat(pdfTemplate.extractText(wm)).contains("Alpha");

        // 绝对坐标盖章
        byte[] stamped = pdfTemplate.addImageStampToBytes(encrypted, ImageStampOptions.builder()
                .image("classpath:images/seal.png").pageIndex(0).x(50).y(50).width(40).height(40).password(pass).build());
        assertThat(pdfTemplate.getPageCount(stamped)).isEqualTo(3);

        // 跨页骑缝章
        byte[] sealed = pdfTemplate.stampCrossPageSealToBytes(encrypted,
                io.github.atengk.boot.document.pdf.model.CrossPageSealOptions.builder()
                        .sealImage("classpath:images/seal.png").password(pass).build());
        assertThat(pdfTemplate.getPageCount(sealed)).isEqualTo(3);

        // 关键字盖章
        byte[] kwStamped = pdfTemplate.stampAtKeywordToBytes(encrypted,
                io.github.atengk.boot.document.pdf.model.KeywordStampOptions.builder()
                        .keyword("Anchor-1").stampImage("classpath:images/seal.png").password(pass).build());
        assertThat(pdfTemplate.getPageCount(kwStamped)).isEqualTo(3);

        // 物理涂黑
        byte[] redacted = pdfTemplate.redactKeywordsToBytes(encrypted, List.of("Secret-999"), pass);
        assertThat(pdfTemplate.extractText(redacted)).doesNotContain("Secret-999").contains("Alpha");

        // 合并 / 拆分 / 提取 / 删除 / 重排 / 插入 / 旋转 / 页码
        byte[] merged = pdfTemplate.mergeToBytes(List.of(encrypted, encrypted), pass);
        assertThat(pdfTemplate.getPageCount(merged)).isEqualTo(6);
        assertThat(pdfTemplate.splitToBytes(encrypted, 1, pass)).hasSize(3);
        assertThat(pdfTemplate.getPageCount(pdfTemplate.extractPagesToBytes(encrypted, pass, 0, 2))).isEqualTo(2);
        assertThat(pdfTemplate.getPageCount(pdfTemplate.deletePagesToBytes(encrypted, pass, 1))).isEqualTo(2);
        assertThat(pdfTemplate.extractText(pdfTemplate.reorderPagesToBytes(encrypted, pass, 2, 0, 1), 1, 1))
                .contains("Gamma");
        assertThat(pdfTemplate.getPageCount(pdfTemplate.insertPagesToBytes(encrypted, encrypted, 1, pass, pass))).isEqualTo(6);
        byte[] rotated = pdfTemplate.rotatePagesToBytes(encrypted, 90, pass, 0);
        try (PDDocument doc = Loader.loadPDF(rotated)) {
            assertThat(doc.getPage(0).getRotation()).isEqualTo(90);
        }

        byte[] numbered = pdfTemplate.addPageNumbersToBytes(encrypted, PageNumberOptions.builder()
                .format("Page {page}/{total}").password(pass).build());
        assertThat(pdfTemplate.extractText(numbered)).contains("Page 1/3");

        // 渲染
        assertThat(pdfTemplate.renderAllPagesToPng(encrypted, pass)).hasSize(3);
        assertThat(pdfTemplate.renderPageToPng(encrypted, 0, 72.0f, pass)).isNotEmpty();
    }

    @Test
    @DisplayName("DocumentConverter 转档桥接：fromDocx 与 fromHtml 委派 SPI 并在缺失外部引擎时快速失败")
    void testConverterBridgeFromDocxAndFromHtml() {
        io.github.atengk.boot.document.spi.DocumentConverter mockConverter = new io.github.atengk.boot.document.spi.DocumentConverter() {
            @Override
            public byte[] docxToPdf(java.io.InputStream docxInputStream) {
                return "%PDF-DOCX".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }

            @Override
            public byte[] htmlToPdf(java.io.InputStream htmlInputStream) {
                return "%PDF-HTML".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        };

        PdfTemplate bridged = new PdfTemplate(createTestProperties(),
                new DocumentFontResolver(createTestProperties().getPdf()), mockConverter);
        assertThat(new String(bridged.fromDocxToBytes(new byte[]{1, 2}), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("%PDF-DOCX");
        assertThat(new String(bridged.fromHtmlToBytes("<h1>Hello Contract</h1>"), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("%PDF-HTML");

        // 默认无转换引擎时抛出 UnsupportedConversionException
        assertThatThrownBy(() -> pdfTemplate.fromHtmlToBytes("<p>Test</p>"))
                .isInstanceOf(io.github.atengk.boot.document.exception.UnsupportedConversionException.class);

        // 转换引擎返回空字节数组时快速失败抛出 CONVERSION_FAILED
        io.github.atengk.boot.document.spi.DocumentConverter emptyConverter = new io.github.atengk.boot.document.spi.DocumentConverter() {
            @Override
            public byte[] docxToPdf(java.io.InputStream docxInputStream) {
                return new byte[0];
            }

            @Override
            public byte[] htmlToPdf(java.io.InputStream htmlInputStream) {
                return null;
            }
        };
        assertThatThrownBy(() -> pdfTemplate.fromDocxToBytes(new byte[]{1}, emptyConverter))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.CONVERSION_FAILED);
        assertThatThrownBy(() -> pdfTemplate.fromHtmlToBytes("<p>Empty</p>", emptyConverter))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.CONVERSION_FAILED);

        // 非法十六进制色值不再静默吞咽异常
        assertThatThrownBy(() -> pdfTemplate.addWatermarkToBytes(createSamplePdfWithCJK("ColorCheck"),
                WatermarkOptions.builder().text("WM").color("INVALID_COLOR").build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("物理涂黑扩展防卫：RedactOptions 支持 ignoreCase、failIfNotFound 与 color 别名")
    void testRedactIgnoreCaseFailIfNotFoundAndColorAlias() {
        byte[] pdf = createSamplePdfWithCJK("Confidential SecretToken-Alpha End");

        // 1. ignoreCase=false（默认）不命中大小写不一致的关键字；开启 failIfNotFound=true 抛出 KEYWORD_NOT_FOUND
        assertThatThrownBy(() -> pdfTemplate.redactToBytes(pdf,
                io.github.atengk.boot.document.pdf.model.RedactOptions.builder()
                        .keywords(List.of("secrettoken-alpha"))
                        .failIfNotFound(true)
                        .build()))
                .isInstanceOf(PdfOperationException.class)
                .hasFieldOrPropertyWithValue("resultCode", DocumentResultCode.KEYWORD_NOT_FOUND);

        // 2. ignoreCase=true + color 别名可准确物理擦除大小写混合关键字
        byte[] redacted = pdfTemplate.redactToBytes(pdf,
                io.github.atengk.boot.document.pdf.model.RedactOptions.builder()
                        .keywords(List.of("secrettoken-alpha"))
                        .ignoreCase(true)
                        .failIfNotFound(true)
                        .color("#112233")
                        .dpi(120.0f)
                        .build());
        assertThat(pdfTemplate.extractText(redacted)).doesNotContain("SecretToken-Alpha").doesNotContain("Confidential");
    }

    private static byte[] createSamplePdfWithCJK(String content) {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            DocumentProperties props = createTestProperties();
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
