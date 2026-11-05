package io.github.atengk.boot.ai.rag.parser.multimodal;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import io.github.atengk.boot.ai.rag.parser.ParsedDocument;
import io.github.atengk.boot.ai.rag.parser.PdfDocumentParser;
import io.github.atengk.boot.ai.rag.parser.WordDocumentParser;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多模态视觉图文解析、模型智能探测、四级级联解析与视觉 OCR 全链路单元测试
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("多模态图文解析流水线与视觉增强测试")
class MultimodalVisionPipelineTest {

    @Nested
    @DisplayName("MultimodalModelDetector 多模态视觉模型指纹探测测试")
    class DetectorTests {

        @Test
        @DisplayName("主流多模态模型家族名称指纹能够准确命中")
        void shouldDetectVisionModelNames() {
            assertThat(MultimodalModelDetector.isMultimodal("qwen-vl-max")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("qwen2.5-vl-72b-instruct")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("gpt-4o")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("gpt-4o-mini")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("gpt-4-vision-preview")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("gemini-1.5-pro")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("gemini-2.0-flash-exp")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("claude-3-5-sonnet-20241022")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("deepseek-vl2")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("glm-4v-plus")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("minicpm-v-2_6")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("llava-1.5-7b-hf")).isTrue();
            assertThat(MultimodalModelDetector.isMultimodal("internvl-chat-v1-5")).isTrue();
        }

        @Test
        @DisplayName("主流纯文本大模型与嵌入模型不会被误判为多模态")
        void shouldNotDetectTextModels() {
            assertThat(MultimodalModelDetector.isMultimodal("deepseek-chat")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("deepseek-r1")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("gpt-4")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("gpt-3.5-turbo")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("qwen-plus")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("qwen-max")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("llama-3-8b-instruct")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("text-embedding-3-small")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("bge-m3")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal("")).isFalse();
            assertThat(MultimodalModelDetector.isMultimodal((String) null)).isFalse();
        }

        @Test
        @DisplayName("显式配置属性优先级高于模型名称正则推断")
        void shouldHonorExplicitConfigurationOverride() {
            AiModelConfig config1 = new AiModelConfig();
            config1.setModelName("deepseek-chat");
            config1.setMultimodal(true);
            assertThat(MultimodalModelDetector.isMultimodal(config1)).isTrue();

            AiModelConfig config2 = new AiModelConfig();
            config2.setModelName("gpt-4o");
            config2.setMultimodal(false);
            assertThat(MultimodalModelDetector.isMultimodal(config2)).isFalse();

            AiModelConfig config3 = new AiModelConfig();
            config3.setModelName("qwen-vl-max");
            config3.setMultimodal(null);
            assertThat(MultimodalModelDetector.isMultimodal(config3)).isTrue();
        }
    }

    @Nested
    @DisplayName("VisionModelResolver 四级级联安全寻址链测试")
    class ResolverTests {

        @Test
        @DisplayName("Tier 1: 优先采用显式配置的独立视觉模型配置")
        void shouldResolveTier1ExplicitConfig() {
            AtengAiRagProperties.MultimodalProperties multimodal = new AtengAiRagProperties.MultimodalProperties();
            AiModelConfig visionCfg = new AiModelConfig();
            visionCfg.setBaseUrl("http://localhost:8080/v1");
            visionCfg.setApiKey("test-key");
            visionCfg.setModelName("qwen-vl-max");
            multimodal.setModelConfig(visionCfg);

            VisionModelResolver resolver = new VisionModelResolver(multimodal, null, null);
            assertThat(resolver.hasVisionCapability()).isTrue();
            ChatLanguageModel model = resolver.resolveVisionModel();
            assertThat(model).isNotNull();
        }

        @Test
        @DisplayName("Tier 1: 优先从注册中心解析显式指定的命名模型")
        void shouldResolveTier1ExplicitNamedModel() {
            AtengAiRagProperties.MultimodalProperties multimodal = new AtengAiRagProperties.MultimodalProperties();
            multimodal.setModel("custom-vl");

            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            registry.register("custom-vl", mockModel);

            VisionModelResolver resolver = new VisionModelResolver(multimodal, registry, null);
            assertThat(resolver.resolveVisionModel()).isSameAs(mockModel);
        }

        @Test
        @DisplayName("Tier 2: 注册中心自动遍历探测命中多模态视觉模型")
        void shouldResolveTier2RegistryAutoDetection() {
            AtengAiRagProperties.MultimodalProperties multimodal = new AtengAiRagProperties.MultimodalProperties();

            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel textModel = mock(ChatLanguageModel.class);
            ChatLanguageModel visionModel = mock(ChatLanguageModel.class);
            registry.register("deepseek-chat", textModel);
            registry.register("qwen-vl-max", visionModel);

            VisionModelResolver resolver = new VisionModelResolver(multimodal, registry, null);
            assertThat(resolver.resolveVisionModel()).isSameAs(visionModel);
        }

        @Test
        @DisplayName("Tier 3: 全局主模型具备多模态能力时安全复用")
        void shouldResolveTier3RootModelIfVisionCapable() {
            AtengAiRagProperties.MultimodalProperties multimodal = new AtengAiRagProperties.MultimodalProperties();

            AtengAiProperties rootProps = new AtengAiProperties();
            AiModelConfig rootConfig = new AiModelConfig();
            rootConfig.setModelName("gpt-4o");
            rootProps.setModel(rootConfig);

            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel rootModel = mock(ChatLanguageModel.class);
            registry.register(AiModelRegistry.DEFAULT_MODEL_NAME, rootModel);

            VisionModelResolver resolver = new VisionModelResolver(multimodal, registry, rootProps);
            assertThat(resolver.resolveVisionModel()).isSameAs(rootModel);
        }

        @Test
        @DisplayName("Tier 4: 未配置任何可用视觉大模型时安全返回 null 降级")
        void shouldGracefullyFallbackToNullWhenNoVisionModelAvailable() {
            AtengAiRagProperties.MultimodalProperties multimodal = new AtengAiRagProperties.MultimodalProperties();

            AtengAiProperties rootProps = new AtengAiProperties();
            AiModelConfig rootConfig = new AiModelConfig();
            rootConfig.setModelName("deepseek-chat");
            rootProps.setModel(rootConfig);

            AiModelRegistry registry = new AiModelRegistry();
            ChatLanguageModel rootModel = mock(ChatLanguageModel.class);
            registry.register(AiModelRegistry.DEFAULT_MODEL_NAME, rootModel);

            VisionModelResolver resolver = new VisionModelResolver(multimodal, registry, rootProps);
            assertThat(resolver.hasVisionCapability()).isFalse();
            assertThat(resolver.resolveVisionModel()).isNull();
        }
    }

    @Nested
    @DisplayName("VisionCaptionEnhancer 图表语义提炼与 400 防御降级测试")
    class CaptionEnhancerTests {

        @Test
        @DisplayName("多模态大模型正常响应时就地生成图表摘要块")
        void shouldGenerateCaptionWhenModelResponds() {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenReturn(Response.from(AiMessage.from("这是一个展示微服务架构间 RPC 调用的系统流向图。")));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            properties.setEnabled(true);

            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
            byte[] dummyImage = createDummyImage(100, 100);

            String result = enhancer.enhanceImage(dummyImage, "arch.png", "image/png");
            assertThat(result).contains("> 💡 [图表/架构图语义描述 - arch.png]: 这是一个展示微服务架构间 RPC 调用的系统流向图。");
        }

        @Test
        @DisplayName("联动 ImageStorageUploader 自动上传并附加 Markdown 图片直达链接")
        void shouldUploadImageAndAttachUrlWhenConfigured() {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenReturn(Response.from(AiMessage.from("业务增长折线图")));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            properties.setEnabled(true);
            properties.setStorageUpload(true);

            VisionCaptionEnhancer.ImageStorageUploader uploader = (bytes, name) -> "https://oss.example.com/" + name;

            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties, uploader);
            byte[] dummyImage = createDummyImage(100, 100);

            String result = enhancer.enhanceImage(dummyImage, "chart.png", "image/png");
            assertThat(result).contains("> 🖼️ ![chart.png](https://oss.example.com/chart.png)");
            assertThat(result).contains("> 💡 [图表/架构图语义描述 - chart.png]: 业务增长折线图");
        }

        @Test
        @DisplayName("大模型调用抛出 400 Bad Request 等异常时自动平滑降级为占位符")
        void shouldGracefullyFallbackToPlaceholderOnModelFailure() {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenThrow(new RuntimeException("400 Bad Request: model does not support image input"));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            properties.setEnabled(true);

            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
            byte[] dummyImage = createDummyImage(100, 100);

            // 严禁向上抛出异常中断解析
            String result = enhancer.enhanceImage(dummyImage, "broken.png", "image/png");
            assertThat(result).isEqualTo("> 🖼️ [内嵌图表: broken.png]");
        }

        @Test
        @DisplayName("扫描件页面 OCR 成功提取文字")
        void shouldExtractTextFromScannedPageOcr() {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenReturn(Response.from(AiMessage.from("这是从扫描件发票中识别出的抬头和金额信息。")));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
            byte[] dummyImage = createDummyImage(300, 300);

            String text = enhancer.enhanceScannedPage(dummyImage, 1);
            assertThat(text).isEqualTo("这是从扫描件发票中识别出的抬头和金额信息。");
        }
    }

    @Nested
    @DisplayName("Word & PDF 多模态图文解析端到端集成测试")
    class ParserIntegrationTests {

        @Test
        @DisplayName("Word 文档解析器自动抽取内嵌图片并调用视觉摘要")
        void shouldExtractAndCaptionImagesFromWordDocument() throws Exception {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenReturn(Response.from(AiMessage.from("Spring Boot 自动装配原理示意图")));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            properties.setEnabled(true);
            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);

            WordDocumentParser parser = new WordDocumentParser(enhancer);

            // 构造包含文本和内嵌图片的 docx 文档
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (XWPFDocument doc = new XWPFDocument()) {
                XWPFParagraph p1 = doc.createParagraph();
                XWPFRun r1 = p1.createRun();
                r1.setText("第一章 框架架构");

                XWPFParagraph p2 = doc.createParagraph();
                XWPFRun r2 = p2.createRun();
                byte[] imgBytes = createDummyImage(120, 80);
                r2.addPicture(new ByteArrayInputStream(imgBytes), XWPFDocument.PICTURE_TYPE_PNG, "spring_arch.png", 200, 100);

                doc.write(out);
            }

            ParsedDocument parsed = parser.parse(new ByteArrayInputStream(out.toByteArray()));
            assertThat(parsed).isNotNull();
            assertThat(parsed.getText()).contains("第一章 框架架构");
            assertThat(parsed.getText()).contains("💡 [图表/架构图语义描述 - image");
            assertThat(parsed.getText()).contains("Spring Boot 自动装配原理示意图");
        }

        @Test
        @DisplayName("PDF 解析器自动提取高分辨率内嵌图片并执行视觉增强")
        void shouldExtractAndCaptionImagesFromPdfDocument() throws Exception {
            ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
            when(mockModel.generate(any(UserMessage.class)))
                    .thenReturn(Response.from(AiMessage.from("分布式拓扑图")));

            AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
            properties.setEnabled(true);
            properties.setMinImageWidth(50);
            properties.setMinImageHeight(50);
            VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);

            PdfDocumentParser parser = new PdfDocumentParser(enhancer);

            // 构造包含文本与内嵌图表的 PDF
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage();
                doc.addPage(page);

                BufferedImage bim = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, bim);

                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("PDF Architecture Documentation");
                    cs.endText();
                    cs.drawImage(pdImage, 50, 50, 100, 100);
                }
                doc.save(out);
            }

            ParsedDocument parsed = parser.parse(new ByteArrayInputStream(out.toByteArray()));
            assertThat(parsed).isNotNull();
            assertThat(parsed.getText()).contains("PDF Architecture Documentation");
            assertThat(parsed.getText()).contains("💡 [图表/架构图语义描述");
        }
    }

    private static byte[] createDummyImage(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, width, height);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return baos.toByteArray();
    }
}
