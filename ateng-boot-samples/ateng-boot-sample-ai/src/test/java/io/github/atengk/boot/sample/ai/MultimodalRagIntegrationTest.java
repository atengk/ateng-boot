package io.github.atengk.boot.sample.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.github.atengk.boot.ai.rag.ingest.IngestOptions;
import io.github.atengk.boot.ai.rag.parser.DocumentParserManager;
import io.github.atengk.boot.ai.rag.parser.ParseOptions;
import io.github.atengk.boot.ai.rag.parser.ParsedDocument;
import io.github.atengk.boot.ai.template.RagIngestionTemplate;
import io.github.atengk.boot.sample.ai.dto.AiRagAskRequest;
import io.github.atengk.boot.storage.template.StorageTemplate;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI RAG 多模态图文解析、内嵌图表提取与 Storage 对象存储联动端到端集成测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AI RAG 多模态图文解析与 Storage 联动端到端集成测试")
class MultimodalRagIntegrationTest {

    private static MockWebServer server;

    private static boolean isLocalProfile() {
        String active = System.getProperty("spring.profiles.active");
        if (active == null) {
            active = System.getenv("SPRING_PROFILES_ACTIVE");
        }
        return active != null && active.contains("local");
    }

    @BeforeAll
    static void startServer() throws IOException {
        if (isLocalProfile()) {
            return;
        }
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() != null ? request.getPath() : "";
                String body = request.getBody().readUtf8();

                // 1. Embedding 向量化接口模拟
                if (path.contains("/embeddings")) {
                    String embeddingJson = """
                            {
                              "object": "list",
                              "data": [
                                {
                                  "object": "embedding",
                                  "embedding": [0.1, 0.2, 0.3, 0.4, 0.5],
                                  "index": 0
                                }
                              ],
                              "model": "text-embedding-3-small",
                              "usage": {
                                "prompt_tokens": 10,
                                "total_tokens": 10
                              }
                            }
                            """;
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/json; charset=utf-8")
                            .setBody(embeddingJson);
                }

                // 2. Chat Completions 视觉多模态 / 对话接口模拟
                String contentAnswer;
                if (body.contains("image_url")) {
                    // 视觉大模型图表分析响应
                    contentAnswer = "本图表为 2026 年 Q3 业务营收报表柱状图，展示第 3 季度营收达到 5200 万元，同比增长 35%。";
                } else {
                    // 常规 RAG 知识库融合问答响应
                    contentAnswer = "根据参考资料中的图表语义描述，2026 年第 3 季度营业额为 5200 万元，同比增长 35%。";
                }

                String chatJson = """
                        {
                          "id": "chatcmpl-multimodal-test",
                          "object": "chat.completion",
                          "created": 1700000000,
                          "model": "gpt-4o",
                          "choices": [
                            {
                              "index": 0,
                              "message": {
                                "role": "assistant",
                                "content": "%s"
                              },
                              "finish_reason": "stop"
                            }
                          ],
                          "usage": {
                            "prompt_tokens": 50,
                            "completion_tokens": 30,
                            "total_tokens": 80
                          }
                        }
                        """.formatted(contentAnswer);

                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json; charset=utf-8")
                        .setBody(chatJson);
            }
        });
        server.start();
    }

    @AfterAll
    static void stopServer() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @DynamicPropertySource
    static void registerAiProperties(DynamicPropertyRegistry registry) {
        if (!isLocalProfile()) {
            registry.add("ateng.boot.ai.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.model.api-key", () -> "mock-test-key-multimodal-ci");
            registry.add("ateng.boot.ai.model.model-name", () -> "gpt-4o");
            registry.add("ateng.boot.ai.rag.model.base-url", () -> server.url("/v1").toString());
            registry.add("ateng.boot.ai.rag.model.api-key", () -> "mock-test-key-multimodal-ci");
            registry.add("ateng.boot.ai.rag.model.model-name", () -> "text-embedding-3-small");
        }
        // 确保多模态与 Storage 上传联动开关在测试上下文中强制激活
        registry.add("ateng.boot.ai.rag.store.type", () -> "IN_MEMORY");
        registry.add("ateng.boot.ai.rag.parser.multimodal.enabled", () -> "true");
        registry.add("ateng.boot.ai.rag.parser.multimodal.storage-upload", () -> "true");
        registry.add("ateng.boot.ai.rag.parser.multimodal.min-image-width", () -> "50");
        registry.add("ateng.boot.ai.rag.parser.multimodal.min-image-height", () -> "50");
    }

    @Autowired
    private DocumentParserManager parserManager;

    @Autowired
    private StorageTemplate storageTemplate;

    @Autowired(required = false)
    private RagIngestionTemplate ingestionTemplate;

    @Autowired(required = false)
    private ContentRetriever contentRetriever;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 辅助工具：动态生成包含柱状图与统计文字的高清测试图表 (200x120 像素)
     */
    private byte[] generateChartImage() throws IOException {
        BufferedImage image = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            // 背景
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 200, 120);

            // 边框与标题
            g.setColor(Color.DARK_GRAY);
            g.drawRect(2, 2, 195, 115);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            g.drawString("2026 Q3 Revenue Chart", 15, 20);

            // 柱状图：Q1, Q2, Q3
            g.setColor(new Color(66, 133, 244));
            g.fillRect(30, 60, 30, 45); // Q1: 1000W
            g.setColor(new Color(52, 168, 83));
            g.fillRect(80, 45, 30, 60); // Q2: 2500W
            g.setColor(new Color(234, 67, 53));
            g.fillRect(130, 25, 30, 80); // Q3: 5200W

            // 标注
            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.drawString("Q1", 38, 115);
            g.drawString("Q2", 88, 115);
            g.drawString("Q3", 138, 115);
        } finally {
            g.dispose();
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }

    @Test
    @DisplayName("测试 Word (DOCX) 内嵌图表提取、Storage 自动上传与视觉摘要增强完整闭环")
    void testWordDocumentWithImageExtractionAndStorageUpload() throws Exception {
        byte[] chartBytes = generateChartImage();

        // 1. 动态生成包含图文的 Word (DOCX) 文档
        ByteArrayOutputStream docxOut = new ByteArrayOutputStream();
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFParagraph p1 = doc.createParagraph();
            XWPFRun r1 = p1.createRun();
            r1.setText("第一章 企业 2026 年度营收与经营分析报告");

            XWPFParagraph p2 = doc.createParagraph();
            XWPFRun r2 = p2.createRun();
            r2.setText("以下为 2026 年各季度主营业务收入统计图表：");

            XWPFParagraph p3 = doc.createParagraph();
            XWPFRun r3 = p3.createRun();
            r3.addPicture(new ByteArrayInputStream(chartBytes), XWPFDocument.PICTURE_TYPE_PNG, "q3_revenue_chart.png", 200, 120);

            XWPFParagraph p4 = doc.createParagraph();
            XWPFRun r4 = p4.createRun();
            r4.setText("从上图可见，第三季度营收呈现爆发式增长。");

            doc.write(docxOut);
        }

        // 2. 调度 DocumentParserManager 执行多模态解析
        ParseOptions options = ParseOptions.builder()
                .filename("financial_analysis.docx")
                .extractMetadata(true)
                .build();

        ParsedDocument parsed = parserManager.parse(new ByteArrayInputStream(docxOut.toByteArray()), options);

        // 3. 强类型断言验证
        assertThat(parsed).isNotNull();
        assertThat(parsed.getText()).contains("第一章 企业 2026 年度营收与经营分析报告");
        assertThat(parsed.getText()).contains("从上图可见，第三季度营收呈现爆发式增长。");

        // 验证视觉大模型摘要与 Markdown 图片标记
        assertThat(parsed.getText()).contains("💡 [图表/架构图语义描述 - ");
        assertThat(parsed.getText()).contains("🖼️ ![");

        // 验证元数据中记录了图片 URL 与存储存在性
        assertThat(parsed.getMetadata()).containsKey("image_url");
        String imageUrl = (String) parsed.getMetadata().get("image_url");
        assertThat(imageUrl).isNotBlank();
        assertThat(imageUrl).startsWith("http://localhost:8080/rag/images/");

        // 提取 Storage 中的 objectKey 并强断言对象存储中确实存在该文件
        String objectKey = imageUrl.substring("http://localhost:8080/".length());
        assertThat(storageTemplate.exists(objectKey)).isTrue();

        System.out.println("【DOCX 多模态提取成功】图片 URL: " + imageUrl);
        System.out.println("【DOCX 抽取文本预览】:\n" + parsed.getText());
    }

    @Test
    @DisplayName("测试 PDF 内嵌图表提取、Storage 自动上传与视觉摘要增强完整闭环")
    void testPdfDocumentWithImageExtractionAndStorageUpload() throws Exception {
        byte[] chartBytes = generateChartImage();

        // 1. 动态生成包含图文的 PDF 文档
        ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
                // 写入文字 (Standard 14 Font 无需外部字体文件)
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                stream.newLineAtOffset(50, 750);
                stream.showText("Chapter 1: Cloud Architecture & Financial Growth Report");
                stream.endText();

                // 嵌入高清图表
                BufferedImage bImg = ImageIO.read(new ByteArrayInputStream(chartBytes));
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, bImg);
                stream.drawImage(pdImage, 50, 580, 200, 120);
            }
            doc.save(pdfOut);
        }

        // 2. 调度 DocumentParserManager 执行多模态解析
        ParseOptions options = ParseOptions.builder()
                .filename("cloud_report.pdf")
                .extractMetadata(true)
                .build();

        ParsedDocument parsed = parserManager.parse(new ByteArrayInputStream(pdfOut.toByteArray()), options);

        // 3. 强类型断言验证
        assertThat(parsed).isNotNull();
        assertThat(parsed.getText()).contains("Cloud Architecture & Financial Growth Report");
        assertThat(parsed.getText()).contains("💡 [图表/架构图语义描述 - ");
        assertThat(parsed.getText()).contains("🖼️ ![");

        // 验证元数据中记录了图片 URL 与存储存在性
        assertThat(parsed.getMetadata()).containsKey("image_url");
        String imageUrl = (String) parsed.getMetadata().get("image_url");
        assertThat(imageUrl).isNotBlank();
        assertThat(imageUrl).startsWith("http://localhost:8080/rag/images/");

        // 提取 Storage 中的 objectKey 并强断言对象存储中确实存在该文件
        String objectKey = imageUrl.substring("http://localhost:8080/".length());
        assertThat(storageTemplate.exists(objectKey)).isTrue();

        System.out.println("【PDF 多模态提取成功】图片 URL: " + imageUrl);
        System.out.println("【PDF 抽取文本预览】:\n" + parsed.getText());
    }

    @Test
    @DisplayName("测试端到端多模态 RAG 摄取管道与基于图表内容的增强问答 (/sample/ai/rag/ask)")
    void testMultimodalRagIngestionAndAsk() throws Exception {
        if (ingestionTemplate == null) {
            return;
        }

        // 1. 模拟摄取图文切片 (带有图表摘要与对象存储图片 URL)
        String docId = "doc-multimodal-001";
        String docName = "2026_q3_report.md";
        String multimodalContent = """
                # 2026年度业务财务分析报告
                
                > 🖼️ ![q3_revenue_chart.png](http://localhost:8080/storage/local/download?path=rag/images/chart_sample.png)
                > 💡 [图表/架构图语义描述 - q3_revenue_chart.png]: 本图表展示了 2026 年 Q1 至 Q3 业务营收情况，其中第 3 季度营业额为 5200 万元，同比增长 35%。
                
                根据上述财务图表，公司核心业务在第三季度实现显著提速。
                """;

        ingestionTemplate.ingest(multimodalContent, docName, IngestOptions.builder().docId(docId).build());

        // 2. 验证问答接口 POST /sample/ai/rag/ask 针对图表中的数据提问
        AiRagAskRequest askRequest = AiRagAskRequest.builder()
                .question("请问根据图表分析，2026年第3季度的营业额和同比增长是多少？")
                .build();

        mockMvc.perform(post("/sample/ai/rag/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(askRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.question").value(askRequest.getQuestion()))
                .andExpect(jsonPath("$.data.answer").isNotEmpty())
                .andExpect(jsonPath("$.data.referenceContexts").isArray());
    }
}
