package io.github.atengk.boot.ai;

import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.util.ImageContentParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 视觉多模态图片内容解析器单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class ImageContentParserTest {

    @Test
    @DisplayName("解析 HTTP 和 HTTPS 图片 URL")
    void testParseHttpAndHttpsUrls() {
        ImageContent httpContent = ImageContentParser.parseImage("http://example.com/photo.png");
        assertThat(httpContent.type()).isEqualTo(ContentType.IMAGE);
        assertThat(httpContent.image().url()).isEqualTo(URI.create("http://example.com/photo.png"));

        ImageContent httpsContent = ImageContentParser.parseImage("https://example.com/pic.jpeg");
        assertThat(httpsContent.image().url()).isEqualTo(URI.create("https://example.com/pic.jpeg"));
    }

    @Test
    @DisplayName("解析标准 Data URI 图片协议")
    void testParseDataUri() {
        String dataUri = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        ImageContent imageContent = ImageContentParser.parseImage(dataUri);

        assertThat(imageContent.image().base64Data()).isEqualTo("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        assertThat(imageContent.image().mimeType()).isEqualTo("image/png");
    }

    @Test
    @DisplayName("解析带额外参数的 Data URI 格式")
    void testParseDataUriWithParams() {
        String dataUri = "data:image/webp;charset=utf-8;base64,UklGRkAAAABXRUJQVlA4IDQAAADwAQCdASoBAAEAAQAcJaACdLoAAP7/2QAA";
        ImageContent imageContent = ImageContentParser.parseImage(dataUri);

        assertThat(imageContent.image().base64Data()).isEqualTo("UklGRkAAAABXRUJQVlA4IDQAAADwAQCdASoBAAEAAQAcJaACdLoAAP7/2QAA");
        assertThat(imageContent.image().mimeType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("解析裸 Base64 字符串保底使用 image/jpeg")
    void testParseRawBase64() {
        String rawBase64 = "aGVsbG93b3JsZGJhc2U2NA==";
        ImageContent imageContent = ImageContentParser.parseImage(rawBase64);

        assertThat(imageContent.image().base64Data()).isEqualTo(rawBase64);
        assertThat(imageContent.image().mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("组合图文转化为 UserMessage 消息")
    void testToUserMessageMixed() {
        List<String> images = List.of(
                "https://example.com/invoice.png",
                "data:image/png;base64,aGVsbG8="
        );
        UserMessage message = ImageContentParser.toUserMessage("请核算发票金额", images);

        assertThat(message.contents()).hasSize(3);
        assertThat(message.contents().get(0).type()).isEqualTo(ContentType.TEXT);
        assertThat(message.contents().get(1).type()).isEqualTo(ContentType.IMAGE);
        assertThat(message.contents().get(2).type()).isEqualTo(ContentType.IMAGE);
    }

    @Test
    @DisplayName("仅传入图片时正常转换为 UserMessage")
    void testToUserMessageOnlyImages() {
        UserMessage message = ImageContentParser.toUserMessage("", List.of("https://example.com/receipt.jpg"));
        assertThat(message.contents()).hasSize(1);
        assertThat(message.contents().get(0).type()).isEqualTo(ContentType.IMAGE);
    }

    @Test
    @DisplayName("对抗性测试：提示词与图片列表均为空时抛出 AI_INVALID_PAYLOAD")
    void testEmptyPromptAndImagesThrows() {
        assertThatThrownBy(() -> ImageContentParser.toUserMessage(null, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));

        assertThatThrownBy(() -> ImageContentParser.toUserMessage("   ", Collections.emptyList()))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("对抗性测试：非法的 Data URI 缺少逗号分隔符抛出 AI_INVALID_PAYLOAD")
    void testMalformedDataUriThrows() {
        assertThatThrownBy(() -> ImageContentParser.parseImage("data:image/png;base64nodata"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));
    }
}
