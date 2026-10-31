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
