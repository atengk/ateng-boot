package io.github.atengk.boot.document.font;

import io.github.atengk.boot.document.exception.DocumentFontException;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.properties.DocumentPdfProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CJK 中文字体三级弹性探测器单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("CJK 中文字体三级弹性探测器单元测试")
class DocumentFontResolverTest {

    @Test
    @DisplayName("一级探测：测试从 classpath 显式配置路径成功解析中文字体")
    void testResolveFromExplicitClasspath() throws Exception {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("classpath:fonts/test-cjk.ttf");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);
        assertThat(resolver.isFontAvailable()).isTrue();

        ResolvedFont resolved = resolver.resolveFont();
        assertThat(resolved).isNotNull();
        assertThat(resolved.tier()).isEqualTo(FontResolutionTier.CONFIG);
        assertThat(resolved.fontName()).isEqualTo("test-cjk");
        assertThat(resolved.fontBytes()).isNotEmpty();
        assertThat(resolved.isCollection()).isFalse();
        assertThat(resolver.getCachedFont()).isSameAs(resolved);

        try (PDDocument document = new PDDocument()) {
            PDType0Font font = resolver.loadFont(document);
            assertThat(font).isNotNull();
            assertThat(font.getName()).isEqualTo("SimSun");
        }
    }

    @Test
    @DisplayName("一级探测：测试从物理绝对文件路径成功解析中文字体")
    void testResolveFromExplicitPhysicalFile(@TempDir Path tempDir) throws Exception {
        Path targetFontFile = tempDir.resolve("my-custom-font.ttf");
        try (InputStream is = getClass().getResourceAsStream("/fonts/test-cjk.ttf")) {
            assertThat(is).isNotNull();
            Files.copy(is, targetFontFile);
        }

        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath(targetFontFile.toAbsolutePath().toString());

        DocumentFontResolver resolver = new DocumentFontResolver(properties);
        ResolvedFont resolved = resolver.resolveFont();

        assertThat(resolved).isNotNull();
        assertThat(resolved.tier()).isEqualTo(FontResolutionTier.CONFIG);
        assertThat(resolved.fontName()).isEqualTo("my-custom-font");
        assertThat(resolved.file()).isEqualTo(targetFontFile.toFile());
        assertThat(resolved.fontBytes()).isNotEmpty();

        try (PDDocument document = new PDDocument()) {
            PDType0Font font = resolver.loadFont(document);
            assertThat(font).isNotNull();
            assertThat(font.getName()).isEqualTo("SimSun");
        }
    }

    @Test
    @DisplayName("一级探测失败防卫：配置不存在的 classpath 字体时快速失败抛出 DocumentFontException")
    void testResolveExplicitClasspathNotFoundThrowsException() {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("classpath:fonts/non-existent-font.ttf");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);

        assertThat(resolver.isFontAvailable()).isFalse();
        assertThatThrownBy(resolver::resolveFont)
                .isInstanceOf(DocumentFontException.class)
                .hasMessageContaining("未找到指定的类路径中文字体文件")
                .satisfies(ex -> {
                    DocumentFontException dfe = (DocumentFontException) ex;
                    assertThat(dfe.getResultCode()).isEqualTo(DocumentResultCode.FONT_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("一级探测失败防卫：配置不存在的物理字体路径时快速失败抛出 DocumentFontException")
    void testResolveExplicitPhysicalFileNotFoundThrowsException() {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("/non/existent/path/custom-font.ttf");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);

        assertThat(resolver.isFontAvailable()).isFalse();
        assertThatThrownBy(resolver::resolveFont)
                .isInstanceOf(DocumentFontException.class)
                .hasMessageContaining("未找到指定的中文字体物理文件")
                .satisfies(ex -> {
                    DocumentFontException dfe = (DocumentFontException) ex;
                    assertThat(dfe.getResultCode()).isEqualTo(DocumentResultCode.FONT_NOT_FOUND);
                });
    }

    @Test
    @DisplayName("二级探测：测试在模拟操作系统字体目录中自动扫描并匹配候选偏好字体")
    void testResolveFromSystemDirectoryScanWithCandidateMatching(@TempDir Path tempDir) throws Exception {
        Path wqyDir = tempDir.resolve("truetype/wqy");
        Files.createDirectories(wqyDir);
        Path wqyFontFile = wqyDir.resolve("wqy-microhei.ttf");
        try (InputStream is = getClass().getResourceAsStream("/fonts/test-cjk.ttf")) {
            assertThat(is).isNotNull();
            Files.copy(is, wqyFontFile);
        }

        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("");
        properties.setPreferredFontNames(new ArrayList<>(List.of("WenQuanYi Micro Hei", "SimSun")));

        DocumentFontResolver resolver = new DocumentFontResolver(properties, List.of(tempDir.toString()));
        ResolvedFont resolved = resolver.resolveFont();

        assertThat(resolved).isNotNull();
        assertThat(resolved.tier()).isEqualTo(FontResolutionTier.SYSTEM);
        assertThat(resolved.fontName()).isEqualTo("WenQuanYi Micro Hei");
        assertThat(resolved.location()).contains("wqy-microhei.ttf");
        assertThat(resolved.file()).isNotNull();
        assertThat(resolved.isCollection()).isFalse();

        try (PDDocument document = new PDDocument()) {
            PDType0Font font = resolver.loadFont(document);
            assertThat(font).isNotNull();
        }
    }

    @Test
    @DisplayName("二级探测（TTC集合）：若宿主机存在 Windows simsun.ttc 验证 TrueTypeCollection 正确解析与加载")
    void testResolveWindowsTtcCollectionIfPresent() throws Exception {
        File simsun = new File("C:/Windows/Fonts/simsun.ttc");
        if (!simsun.exists()) {
            return;
        }

        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath(simsun.getAbsolutePath());

        DocumentFontResolver resolver = new DocumentFontResolver(properties);
        ResolvedFont resolved = resolver.resolveFont();

        assertThat(resolved).isNotNull();
        assertThat(resolved.isCollection()).isTrue();

        try (PDDocument document = new PDDocument()) {
            PDType0Font font = resolver.loadFont(document);
            assertThat(font).isNotNull();
            assertThat(font.getName()).isNotBlank();
        }
    }

    @Test
    @DisplayName("二级探测：偏好未命中时自动触发通用中文字体关键词兜底探测")
    void testResolveFromSystemFallbackKeyword(@TempDir Path tempDir) throws Exception {
        Path fontFile = tempDir.resolve("simhei.ttf");
        try (InputStream is = getClass().getResourceAsStream("/fonts/test-cjk.ttf")) {
            assertThat(is).isNotNull();
            Files.copy(is, fontFile);
        }

        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("");
        properties.setPreferredFontNames(new ArrayList<>(List.of("NonExistentFontFamily")));

        DocumentFontResolver resolver = new DocumentFontResolver(properties, List.of(tempDir.toString()));
        ResolvedFont resolved = resolver.resolveFont();

        assertThat(resolved).isNotNull();
        assertThat(resolved.tier()).isEqualTo(FontResolutionTier.SYSTEM);
        assertThat(resolved.fontName()).isEqualTo("simhei");
        assertThat(resolved.location()).contains("simhei.ttf");
    }

    @Test
    @DisplayName("三级快速失败防卫：各级均缺失中文字体时抛出包含 Docker 镜像安装指引的详细异常")
    void testResolveMissingFontFailsFastWithDockerGuidance(@TempDir Path emptyTempDir) {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("");
        properties.setPreferredFontNames(new ArrayList<>(List.of("SimSun")));

        DocumentFontResolver resolver = new DocumentFontResolver(properties, List.of(emptyTempDir.toString()));

        assertThat(resolver.isFontAvailable()).isFalse();
        assertThatThrownBy(resolver::resolveFont)
                .isInstanceOf(DocumentFontException.class)
                .satisfies(ex -> {
                    DocumentFontException dfe = (DocumentFontException) ex;
                    assertThat(dfe.getResultCode()).isEqualTo(DocumentResultCode.FONT_NOT_FOUND);
                    String msg = dfe.getMessage();
                    assertThat(msg).contains("未检测到可用的 CJK 中文字体");
                    assertThat(msg).contains("RUN apt-get update && apt-get install -y fonts-wqy-microhei");
                    assertThat(msg).contains("RUN apk add --no-cache font-noto-cjk");
                    assertThat(msg).contains("RUN yum install -y wqy-microhei-fonts");
                    assertThat(msg).contains("-v /usr/share/fonts:/usr/share/fonts");
                    assertThat(msg).contains("ateng.boot.document.pdf.font-path");
                });
    }

    @Test
    @DisplayName("缓存与并发纪律：验证高并发下仅扫描一次且 clearCache 能彻底重置状态")
    void testThreadSafeCacheAndClearCache() throws Exception {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("classpath:fonts/test-cjk.ttf");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<CompletableFuture<ResolvedFont>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(CompletableFuture.supplyAsync(resolver::resolveFont, executor));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            ResolvedFont first = futures.get(0).get();
            for (CompletableFuture<ResolvedFont> future : futures) {
                assertThat(future.get()).isSameAs(first);
            }

            assertThat(resolver.getCachedFont()).isSameAs(first);

            resolver.clearCache();
            assertThat(resolver.getCachedFont()).isNull();

            ResolvedFont second = resolver.resolveFont();
            assertThat(second).isNotNull();
            assertThat(resolver.getCachedFont()).isSameAs(second);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("端到端渲染：加载中文字体并成功在 PDF 页面输出真实中文字符")
    void testRenderChineseToPdfDocument() throws Exception {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("classpath:fonts/test-cjk.ttf");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);

        byte[] pdfBytes;
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);

            PDType0Font font = resolver.loadFont(document);

            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(font, 18);
                stream.newLineAtOffset(50, 700);
                stream.showText("测试中文企业级文档治理水印盖章");
                stream.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            pdfBytes = baos.toByteArray();
        }

        assertThat(pdfBytes).isNotEmpty();

        // 重新加载验证文档结构与页面完整性
        try (PDDocument verifiedDoc = Loader.loadPDF(pdfBytes)) {
            assertThat(verifiedDoc.getNumberOfPages()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("自定义路径加载：测试在方法级别显式指定 customPath 进行临时字体加载")
    void testLoadFontWithCustomPathParameter() throws Exception {
        DocumentPdfProperties properties = new DocumentPdfProperties();
        properties.setFontPath("");

        DocumentFontResolver resolver = new DocumentFontResolver(properties);

        try (PDDocument document = new PDDocument()) {
            PDType0Font font = resolver.loadFont(document, "classpath:fonts/test-cjk.ttf");
            assertThat(font).isNotNull();
            assertThat(font.getName()).isEqualTo("SimSun");
        }
    }

    @Test
    @DisplayName("参数防御性断言：传递 null PDDocument 时立即抛出 NPE 拦截")
    void testNullDocumentAssertion() {
        DocumentFontResolver resolver = new DocumentFontResolver();
        assertThatThrownBy(() -> resolver.loadFont(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("document 不能为空");

        assertThatThrownBy(() -> resolver.loadFont(null, "classpath:fonts/test-cjk.ttf"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("document 不能为空");
    }
}
