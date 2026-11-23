package io.github.atengk.boot.ai.rag.parser.multimodal;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.atengk.boot.ai.properties.AtengAiRagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 视觉多模态图表摘要增强器 (VisionCaptionEnhancer) 专项单元测试
 * <p>
 * 验证内嵌图表语义摘要提取、结构化结果解析、对象存储联动 URL 生成、
 * 扫描件 OCR 识别，以及纯文本模型 / 400 Bad Request 异常时的防御性平滑降级。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
@DisplayName("VisionCaptionEnhancer 专项测试")
class VisionCaptionEnhancerTest {

    private byte[] createDummyImage(int width, int height) {
        try {
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            g.setColor(Color.BLUE);
            g.fillRect(0, 0, width, height);
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("多模态大模型正常响应时生成图表摘要块与结构化结果")
    void shouldGenerateCaptionWhenModelResponds() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("微服务架构图")));

        AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
        properties.setEnabled(true);

        VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
        byte[] dummyImage = createDummyImage(100, 100);

        String result = enhancer.enhanceImage(dummyImage, "arch.png", "image/png");
        assertThat(result).contains("> 💡 [图表/架构图语义描述 - arch.png]: 微服务架构图");

        VisionCaptionEnhancer.ImageEnhanceResult structured = enhancer.enhanceImageResult(dummyImage, "arch.png", "image/png");
        assertThat(structured.text()).isEqualTo(result);
        assertThat(structured.imageUrl()).isNull();
    }

    @Test
    @DisplayName("联动 ImageStorageUploader 上传并返回图片访问 URL")
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

        VisionCaptionEnhancer.ImageEnhanceResult structured = enhancer.enhanceImageResult(dummyImage, "chart.png", "image/png");
        assertThat(structured.imageUrl()).isEqualTo("https://oss.example.com/chart.png");
        assertThat(structured.text()).contains("> 🖼️ ![chart.png](https://oss.example.com/chart.png)");
        assertThat(structured.text()).contains("> 💡 [图表/架构图语义描述 - chart.png]: 业务增长折线图");
    }

    @Test
    @DisplayName("调用视觉模型抛出 400 Bad Request 等异常时自动平滑降级为占位符")
    void shouldGracefullyFallbackToPlaceholderOnModelFailure() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any(UserMessage.class)))
                .thenThrow(new RuntimeException("400 Bad Request: model does not support image input"));

        AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
        properties.setEnabled(true);

        VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
        byte[] dummyImage = createDummyImage(100, 100);

        String result = enhancer.enhanceImage(dummyImage, "fail.png", "image/png");
        assertThat(result).isEqualTo("> 🖼️ [内嵌图表: fail.png]");
    }

    @Test
    @DisplayName("未就绪任何视觉大模型时安全降级为图表占位符")
    void shouldReturnPlaceholderWhenNoVisionModelAvailable() {
        AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
        properties.setEnabled(true);

        VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer((ChatLanguageModel) null, properties);
        byte[] dummyImage = createDummyImage(100, 100);

        String result = enhancer.enhanceImage(dummyImage, "diagram.png", "image/png");
        assertThat(result).isEqualTo("> 🖼️ [内嵌图表: diagram.png]");
    }

    @Test
    @DisplayName("扫描件页面 OCR 识别在模型成功时返回提取文本，异常时优雅降级")
    void shouldEnhanceScannedPageProperly() {
        ChatLanguageModel mockModel = mock(ChatLanguageModel.class);
        when(mockModel.generate(any(UserMessage.class)))
                .thenReturn(Response.from(AiMessage.from("发票编号: 12345678\n金额: ¥999.00")));

        AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
        properties.setEnabled(true);

        VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer(mockModel, properties);
        byte[] dummyImage = createDummyImage(200, 200);

        String ocrResult = enhancer.enhanceScannedPage(dummyImage, 1);
        assertThat(ocrResult).contains("发票编号: 12345678");

        // 异常降级
        when(mockModel.generate(any(UserMessage.class)))
                .thenThrow(new RuntimeException("OCR service timeout"));
        String failResult = enhancer.enhanceScannedPage(dummyImage, 2);
        assertThat(failResult).contains("> 📄 [第 2 页扫描件视觉 OCR 识别异常");
    }

    @Test
    @DisplayName("空图像数据与空参数防御性处理")
    void shouldHandleEmptyOrNullImageSafely() {
        AtengAiRagProperties.MultimodalProperties properties = new AtengAiRagProperties.MultimodalProperties();
        properties.setEnabled(true);

        VisionCaptionEnhancer enhancer = new VisionCaptionEnhancer((ChatLanguageModel) null, properties);
        String nullImg = enhancer.enhanceImage(null, "empty.png", "image/png");
        assertThat(nullImg).isEqualTo("> 🖼️ [内嵌图表: empty.png]");

        String emptyScanned = enhancer.enhanceScannedPage(new byte[0], 1);
        assertThat(emptyScanned).contains("无有效图像数据");
    }
}
