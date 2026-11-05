package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RAG 多源异构文档解析 SPI 与 DocumentParserManager 单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("RAG 多源文档解析器与管理器单元测试")
class DocumentParserManagerTest {

    private final TextDocumentParser textParser = new TextDocumentParser();
    private final HtmlDocumentParser htmlParser = new HtmlDocumentParser();
    private final DocumentParserManager parserManager = new DocumentParserManager(textParser, htmlParser);

    @Nested
    @DisplayName("DocumentFormat 格式探测与属性测试")
    class DocumentFormatTests {

        @Test
        @DisplayName("文件名扩展名智能探测格式")
        void testFromFilename() {
            assertThat(DocumentFormat.fromFilename("test.txt")).isEqualTo(DocumentFormat.TXT);
            assertThat(DocumentFormat.fromFilename("guide.markdown")).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(DocumentFormat.fromFilename("INDEX.HTML")).isEqualTo(DocumentFormat.HTML);
            assertThat(DocumentFormat.fromFilename("spec.pdf")).isEqualTo(DocumentFormat.PDF);
            assertThat(DocumentFormat.fromFilename("contract.docx")).isEqualTo(DocumentFormat.WORD);
            assertThat(DocumentFormat.fromFilename("sales.xlsx")).isEqualTo(DocumentFormat.EXCEL);
            assertThat(DocumentFormat.fromFilename("data.csv")).isEqualTo(DocumentFormat.CSV);
            assertThat(DocumentFormat.fromFilename("config.json")).isEqualTo(DocumentFormat.JSON);
            assertThat(DocumentFormat.fromFilename("archive.tar.gz")).isEqualTo(DocumentFormat.UNKNOWN);
            assertThat(DocumentFormat.fromFilename("no_extension")).isEqualTo(DocumentFormat.UNKNOWN);
            assertThat(DocumentFormat.fromFilename(null)).isEqualTo(DocumentFormat.UNKNOWN);
            assertThat(DocumentFormat.fromFilename("   ")).isEqualTo(DocumentFormat.UNKNOWN);
        }

        @Test
        @DisplayName("扩展名匹配格式")
        void testFromExtension() {
            assertThat(DocumentFormat.fromExtension(".md")).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(DocumentFormat.fromExtension("MD")).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(DocumentFormat.fromExtension(".txt")).isEqualTo(DocumentFormat.TXT);
            assertThat(DocumentFormat.fromExtension(".html")).isEqualTo(DocumentFormat.HTML);
            assertThat(DocumentFormat.fromExtension("unknown")).isEqualTo(DocumentFormat.UNKNOWN);
            assertThat(DocumentFormat.fromExtension(null)).isEqualTo(DocumentFormat.UNKNOWN);
        }

        @Test
        @DisplayName("MIME 类型匹配格式")
        void testFromMimeType() {
            assertThat(DocumentFormat.fromMimeType("text/plain; charset=UTF-8")).isEqualTo(DocumentFormat.TXT);
            assertThat(DocumentFormat.fromMimeType("text/html")).isEqualTo(DocumentFormat.HTML);
            assertThat(DocumentFormat.fromMimeType("text/markdown")).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(DocumentFormat.fromMimeType("application/pdf")).isEqualTo(DocumentFormat.PDF);
            assertThat(DocumentFormat.fromMimeType("application/json")).isEqualTo(DocumentFormat.JSON);
            assertThat(DocumentFormat.fromMimeType("unknown/mime")).isEqualTo(DocumentFormat.UNKNOWN);
            assertThat(DocumentFormat.fromMimeType(null)).isEqualTo(DocumentFormat.UNKNOWN);
        }

        @Test
        @DisplayName("文本基底格式判定与首选扩展名")
        void testFormatProperties() {
            assertThat(DocumentFormat.TXT.isTextBased()).isTrue();
            assertThat(DocumentFormat.MARKDOWN.isTextBased()).isTrue();
            assertThat(DocumentFormat.HTML.isTextBased()).isTrue();
            assertThat(DocumentFormat.CSV.isTextBased()).isTrue();
            assertThat(DocumentFormat.JSON.isTextBased()).isTrue();
            assertThat(DocumentFormat.PDF.isTextBased()).isFalse();
            assertThat(DocumentFormat.WORD.isTextBased()).isFalse();

            assertThat(DocumentFormat.TXT.getPrimaryExtension()).isEqualTo("txt");
            assertThat(DocumentFormat.MARKDOWN.getPrimaryExtension()).isEqualTo("md");
            assertThat(DocumentFormat.HTML.getPrimaryExtension()).isEqualTo("html");
            assertThat(DocumentFormat.UNKNOWN.getPrimaryExtension()).isEmpty();
        }
    }

    @Nested
    @DisplayName("TextDocumentParser 纯文本与 Markdown 解析测试")
    class TextDocumentParserTests {

        @Test
        @DisplayName("解析标准 UTF-8 纯文本并提取首行标题")
        void testParsePlainTextUtf8() {
            String text = "用户操作手册\n这是第一行说明。\n这是第二行说明。";
            ByteArrayInputStream in = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = textParser.parse(in, ParseOptions.builder().filename("manual.txt").build());

            assertThat(doc.getText()).isEqualTo(text);
            assertThat(doc.getTitle()).isEqualTo("manual");
            assertThat(doc.getFormat()).isEqualTo(DocumentFormat.TXT);
            assertThat(doc.getCharacterCount()).isEqualTo(text.length());
            assertThat(doc.getPageCount()).isEqualTo(1);
            assertThat(doc.getMetadata()).containsEntry(ParsedDocument.METADATA_FILENAME, "manual.txt");
            assertThat(doc.getMetadata()).containsEntry("charset", "UTF-8");
            assertThat(doc.isEmpty()).isFalse();
            assertThat(doc.hasTitle()).isTrue();
        }

        @Test
        @DisplayName("解析非 UTF-8 编码文本 (GBK)")
        void testParsePlainTextGbk() {
            String text = "你好，世界！这是一段GBK编码的文本。";
            Charset gbk = Charset.forName("GBK");
            byte[] bytes = text.getBytes(gbk);
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);

            ParsedDocument doc = textParser.parse(in, ParseOptions.builder().charset(gbk).build());

            assertThat(doc.getText()).isEqualTo(text);
            assertThat(doc.getTitle()).isEqualTo("你好，世界！这是一段GBK编码的文本。");
            assertThat(doc.getMetadata()).containsEntry("charset", "GBK");
        }

        @Test
        @DisplayName("解析带有 YAML Frontmatter 标题的 Markdown 文档")
        void testParseMarkdownWithFrontmatter() {
            String md = """
                    ---
                    title: 企业级知识库架构设计
                    author: Ateng
                    date: 2026-11-05
                    ---
                    # 知识库概述
                    知识库体系包含数据抓取、清洗与向量化...
                    """;
            ByteArrayInputStream in = new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = textParser.parse(in, ParseOptions.builder().filename("arch.md").build());

            assertThat(doc.getTitle()).isEqualTo("企业级知识库架构设计");
            assertThat(doc.getFormat()).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(doc.getText()).contains("# 知识库概述");
        }

        @Test
        @DisplayName("解析无 Frontmatter 但含一级标题的 Markdown 文档")
        void testParseMarkdownWithH1() {
            String md = """
                    # 快速起步指引
                    
                    ## 1. 安装配置
                    运行以下命令即可：
                    ```bash
                    mvn clean install
                    ```
                    """;
            ByteArrayInputStream in = new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = textParser.parse(in, ParseOptions.builder().filename("guide.md").build());

            assertThat(doc.getTitle()).isEqualTo("快速起步指引");
            assertThat(doc.getText()).contains("mvn clean install");
        }

        @Test
        @DisplayName("配置 maxCharacters 截断字符测试")
        void testMaxCharactersLimit() {
            String longText = "1234567890".repeat(50); // 500 chars
            ByteArrayInputStream in = new ByteArrayInputStream(longText.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = textParser.parse(in, ParseOptions.builder().maxCharacters(50).build());

            assertThat(doc.getText()).hasSize(50);
            assertThat(doc.getCharacterCount()).isEqualTo(50);
        }

        @Test
        @DisplayName("支持格式判断 supports")
        void testSupports() {
            assertThat(textParser.supports(DocumentFormat.TXT, "a.txt")).isTrue();
            assertThat(textParser.supports(DocumentFormat.MARKDOWN, "a.md")).isTrue();
            assertThat(textParser.supports(DocumentFormat.UNKNOWN, "test.txt")).isTrue();
            assertThat(textParser.supports(DocumentFormat.HTML, "test.html")).isFalse();
            assertThat(textParser.supports(DocumentFormat.PDF, "test.pdf")).isFalse();
        }

        @Test
        @DisplayName("空流异常防卫")
        void testNullStreamDefense() {
            assertThatThrownBy(() -> textParser.parse(null, ParseOptions.defaults()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("InputStream 不能为 null");
        }
    }

    @Nested
    @DisplayName("HtmlDocumentParser 零臃肿 HTML 解析测试")
    class HtmlDocumentParserTests {

        @Test
        @DisplayName("清洗 script、style、注释并提取 title 标题")
        void testParseHtmlCleaning() {
            String html = """
                    <!DOCTYPE html>
                    <html lang="zh">
                    <head>
                        <meta charset="UTF-8">
                        <title>Spring Boot 3 企业级微服务规范</title>
                        <style>
                            body { font-size: 14px; color: #333; }
                            .nav { display: flex; }
                        </style>
                        <script>
                            function track() { console.log("analytics"); }
                        </script>
                    </head>
                    <body>
                        <!-- 页面主导航 -->
                        <h1>第一章：项目技术基线</h1>
                        <p>本项目基于 <b>Spring Boot 3.3.13</b> 与 JDK 17 构建。</p>
                        <p>支持原生编译与高效运行。&copy; 2026 Ateng&trade;</p>
                        <div>
                            <ul>
                                <li>高性能</li>
                                <li>零臃肿依赖</li>
                            </ul>
                        </div>
                    </body>
                    </html>
                    """;
            ByteArrayInputStream in = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = htmlParser.parse(in, ParseOptions.builder().filename("standard.html").build());

            assertThat(doc.getTitle()).isEqualTo("Spring Boot 3 企业级微服务规范");
            assertThat(doc.getFormat()).isEqualTo(DocumentFormat.HTML);
            assertThat(doc.getText()).doesNotContain("<style>");
            assertThat(doc.getText()).doesNotContain("font-size: 14px");
            assertThat(doc.getText()).doesNotContain("<script>");
            assertThat(doc.getText()).doesNotContain("track()");
            assertThat(doc.getText()).doesNotContain("页面主导航");
            assertThat(doc.getText()).contains("第一章：项目技术基线");
            assertThat(doc.getText()).contains("本项目基于 Spring Boot 3.3.13 与 JDK 17 构建。");
            assertThat(doc.getText()).contains("© 2026 Ateng™");
            assertThat(doc.getText()).contains("高性能");
            assertThat(doc.getText()).contains("零臃肿依赖");
        }

        @Test
        @DisplayName("HTML 命名与数值实体字符完整反转义")
        void testHtmlEntitiesUnescape() {
            String html = "<html><body><p>&lt;div class=&quot;box&quot;&gt;Tom &amp; Jerry&#39;s &copy; &#65;&#x42;&lt;/div&gt;&nbsp;Hello</p></body></html>";
            ByteArrayInputStream in = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = htmlParser.parse(in, ParseOptions.defaults());

            assertThat(doc.getText()).isEqualTo("<div class=\"box\">Tom & Jerry's © AB</div> Hello");
        }

        @Test
        @DisplayName("无 title 标签时自动回退文件名提取标题")
        void testFallbackTitleFromFilename() {
            String html = "<div><p>简易无标题文档内容</p></div>";
            ByteArrayInputStream in = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = htmlParser.parse(in, ParseOptions.builder().filename("product-intro.html").build());

            assertThat(doc.getTitle()).isEqualTo("product-intro");
            assertThat(doc.getText()).isEqualTo("简易无标题文档内容");
        }

        @Test
        @DisplayName("HTML 最大字符数截断")
        void testHtmlMaxCharacters() {
            String html = "<p>" + "A".repeat(200) + "</p>";
            ByteArrayInputStream in = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));

            ParsedDocument doc = htmlParser.parse(in, ParseOptions.builder().maxCharacters(80).build());

            assertThat(doc.getText()).hasSize(80);
        }

        @Test
        @DisplayName("支持格式判断 supports")
        void testSupports() {
            assertThat(htmlParser.supports(DocumentFormat.HTML, "index.html")).isTrue();
            assertThat(htmlParser.supports(DocumentFormat.UNKNOWN, "home.htm")).isTrue();
            assertThat(htmlParser.supports(DocumentFormat.TXT, "test.txt")).isFalse();
        }
    }

    @Nested
    @DisplayName("DocumentParserManager 统一门面与生命周期管理测试")
    class DocumentParserManagerTests {

        @Test
        @DisplayName("自动基于文件名路由至对应解析器")
        void testRouteByFilename() {
            String text = "Plain text sample";
            ByteArrayInputStream textIn = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
            ParsedDocument textDoc = parserManager.parse(textIn, "sample.txt");
            assertThat(textDoc.getFormat()).isEqualTo(DocumentFormat.TXT);
            assertThat(textDoc.getText()).isEqualTo(text);

            String html = "<html><head><title>HTML Doc</title></head><body>Content</body></html>";
            ByteArrayInputStream htmlIn = new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
            ParsedDocument htmlDoc = parserManager.parse(htmlIn, "sample.html");
            assertThat(htmlDoc.getFormat()).isEqualTo(DocumentFormat.HTML);
            assertThat(htmlDoc.getTitle()).isEqualTo("HTML Doc");
            assertThat(htmlDoc.getText()).isEqualTo("Content");
        }

        @Test
        @DisplayName("内存纯文本解析 parseText API")
        void testParseTextApi() {
            ParsedDocument mdDoc = parserManager.parseText("# Markdown 标题\n正文描述", DocumentFormat.MARKDOWN);
            assertThat(mdDoc.getFormat()).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(mdDoc.getTitle()).isEqualTo("Markdown 标题");

            ParsedDocument htmlDoc = parserManager.parseText("<h1>Title</h1><p>Body</p>", DocumentFormat.HTML);
            assertThat(htmlDoc.getFormat()).isEqualTo(DocumentFormat.HTML);
            assertThat(htmlDoc.getText()).contains("Title\nBody");
        }

        @Test
        @DisplayName("Spring Resource 资源解析 API")
        void testParseResource() {
            String content = "# Spring Resource Doc\nTesting Resource parsing.";
            Resource resource = new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public String getFilename() {
                    return "test-doc.md";
                }
            };

            ParsedDocument doc = parserManager.parse(resource);

            assertThat(doc.getTitle()).isEqualTo("Spring Resource Doc");
            assertThat(doc.getFormat()).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(doc.getText()).contains("Testing Resource parsing.");
        }

        @Test
        @DisplayName("本地 File 与 Path 解析 API (@TempDir 临时目录测试)")
        void testParseLocalFileAndPath(@TempDir Path tempDir) throws IOException {
            Path testFile = tempDir.resolve("local-spec.md");
            Files.writeString(testFile, "# 本地文件规范\n测试 File 与 Path 的确定性解析与释放。");

            // 1. Path 解析测试
            ParsedDocument pathDoc = parserManager.parse(testFile);
            assertThat(pathDoc.getTitle()).isEqualTo("本地文件规范");
            assertThat(pathDoc.getFormat()).isEqualTo(DocumentFormat.MARKDOWN);
            assertThat(pathDoc.getText()).contains("测试 File 与 Path 的确定性解析与释放。");

            // 2. File 解析测试
            ParsedDocument fileDoc = parserManager.parse(testFile.toFile());
            assertThat(fileDoc.getTitle()).isEqualTo("本地文件规范");
            assertThat(fileDoc.getFormat()).isEqualTo(DocumentFormat.MARKDOWN);
        }

        @Test
        @DisplayName("确定性生命周期：验证输入流流句柄在解析后被可靠关闭")
        void testStreamDeterministicCleanup() throws IOException {
            AtomicBoolean streamClosed = new AtomicBoolean(false);
            byte[] bytes = "Hello Stream Cleanup".getBytes(StandardCharsets.UTF_8);
            InputStream trackingStream = new FilterInputStream(new ByteArrayInputStream(bytes)) {
                @Override
                public void close() throws IOException {
                    super.close();
                    streamClosed.set(true);
                }
            };

            ParsedDocument doc = parserManager.parse(trackingStream, "cleanup.txt");

            assertThat(doc.getText()).isEqualTo("Hello Stream Cleanup");
            assertThat(streamClosed.get()).isTrue();
        }

        @Test
        @DisplayName("未找到支持格式的解析器时抛出 AiException(AI_PARSE_ERROR)")
        void testUnsupportedFormatThrowsAiException() {
            byte[] bytes = new byte[]{1, 2, 3};
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);

            assertThatThrownBy(() -> parserManager.parse(in, "binary.unknown_ext_xyz"))
                    .isInstanceOf(AiException.class)
                    .satisfies(ex -> {
                        AiException aiEx = (AiException) ex;
                        assertThat(aiEx.getResultCode()).isEqualTo(AiResultCode.AI_PARSE_ERROR);
                        assertThat(aiEx.getMessage()).contains("未找到支持该文档格式的解析器");
                    });
        }

        @Test
        @DisplayName("非空卫语句拦截非法入参")
        void testFailFastDefense() {
            assertThatThrownBy(() -> parserManager.parse((InputStream) null, ParseOptions.defaults()))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> parserManager.parse((Resource) null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> parserManager.parse((File) null))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThatThrownBy(() -> parserManager.parse((Path) null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("文件不存在时抛出语义化异常")
        void testNonExistentFileThrowsException(@TempDir Path tempDir) {
            Path nonExistent = tempDir.resolve("not_found.txt");
            assertThatThrownBy(() -> parserManager.parse(nonExistent))
                    .isInstanceOf(AiException.class)
                    .satisfies(ex -> assertThat(((AiException) ex).getResultCode()).isEqualTo(AiResultCode.AI_PARSE_ERROR));

            assertThatThrownBy(() -> parserManager.parse(nonExistent.toFile()))
                    .isInstanceOf(AiException.class)
                    .satisfies(ex -> assertThat(((AiException) ex).getResultCode()).isEqualTo(AiResultCode.AI_PARSE_ERROR));
        }
    }
}
