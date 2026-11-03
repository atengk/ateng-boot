package io.github.atengk.boot.ai.sanitizer;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化输出防幻觉去噪纯函数工具类 JsonPayloadSanitizer 单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class JsonPayloadSanitizerTest {

    record SampleAuditResult(
            @NotBlank(message = "状态不能为空")
            String status,

            @NotNull(message = "得分不能为空")
            @Min(value = 0, message = "得分不能小于0")
            Integer score
    ) {}

    @Test
    @DisplayName("剥除标准的 ```json 代码块包裹")
    void testSanitizeStandardMarkdownJson() {
        String raw = """
                ```json
                {
                  "status": "APPROVED",
                  "score": 98
                }
                ```
                """;

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo("{\n  \"status\": \"APPROVED\",\n  \"score\": 98\n}");
    }

    @Test
    @DisplayName("剥除无语言标识的 ``` 代码块包裹")
    void testSanitizeGenericMarkdownCodeBlock() {
        String raw = """
                ```
                {"status":"REJECTED","score":45}
                ```
                """;

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo("{\"status\":\"REJECTED\",\"score\":45}");
    }

    @Test
    @DisplayName("剥除首尾携带对话性噪点及 ```json 代码块的内容")
    void testSanitizeConversationalNoiseWithMarkdown() {
        String raw = """
                根据您的审核规则，分析报告如下：
                ```json
                {
                  "status": "APPROVED",
                  "score": 88
                }
                ```
                如有疑问请随时联系风控专员。
                """;

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo("{\n  \"status\": \"APPROVED\",\n  \"score\": 88\n}");
    }

    @Test
    @DisplayName("无代码块标记但前后包含问答闲聊噪点时，安全提取最外层 JSON 结构")
    void testSanitizeRawTextWithBrackets() {
        String raw = "审核结果如下：{\"status\":\"APPROVED\",\"score\":100}。请查收！";

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo("{\"status\":\"APPROVED\",\"score\":100}");
    }

    @Test
    @DisplayName("清洗包含 UTF-8 BOM 及零宽空白的畸变字符")
    void testSanitizeBomAndZeroWidthSpaces() {
        String raw = "\uFEFF\u200B```json\n{\"status\":\"APPROVED\",\"score\":90}\n```\u200C";

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo("{\"status\":\"APPROVED\",\"score\":90}");
    }

    @Test
    @DisplayName("对于本身已纯净的 JSON 字符串保持原样")
    void testSanitizeAlreadyCleanJson() {
        String raw = "{\"status\":\"APPROVED\",\"score\":95}";

        String sanitized = JsonPayloadSanitizer.sanitize(raw);
        assertThat(sanitized).isEqualTo(raw);
    }

    @Test
    @DisplayName("输入为空白或 null 时安全返回空字符串")
    void testSanitizeEmptyOrNull() {
        assertThat(JsonPayloadSanitizer.sanitize(null)).isEmpty();
        assertThat(JsonPayloadSanitizer.sanitize("")).isEmpty();
        assertThat(JsonPayloadSanitizer.sanitize("   \n\t  ")).isEmpty();
    }

    @Test
    @DisplayName("清洗并成功反序列化为 Java Record 且通过 JSR-303 格式校验")
    void testParseAndValidateSuccess() {
        String raw = """
                ```json
                {
                  "status": "APPROVED",
                  "score": 99
                }
                ```
                """;

        SampleAuditResult result = JsonPayloadSanitizer.parseAndValidate(raw, SampleAuditResult.class);
        assertThat(result).isNotNull();
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.score()).isEqualTo(99);
    }

    @Test
    @DisplayName("输入为空时 parseAndValidate 抛出业务异常")
    void testParseAndValidateEmptyThrows() {
        assertThatThrownBy(() -> JsonPayloadSanitizer.parseAndValidate("", SampleAuditResult.class))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_RESPONSE_PARSE_ERROR);
    }

    @Test
    @DisplayName("JSON 格式破损反序列化失败时抛出业务异常")
    void testParseAndValidateMalformedJsonThrows() {
        String malformed = "```json\n{\"status\": \"APPROVED\", \"score\": }\n```";

        assertThatThrownBy(() -> JsonPayloadSanitizer.parseAndValidate(malformed, SampleAuditResult.class))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_RESPONSE_PARSE_ERROR);
    }

    @Test
    @DisplayName("反序列化成功但未通过 JSR-303 格式校验时抛出业务异常")
    void testParseAndValidateConstraintViolationThrows() {
        String invalidScore = """
                ```json
                {
                  "status": "APPROVED",
                  "score": -10
                }
                ```
                """;

        assertThatThrownBy(() -> JsonPayloadSanitizer.parseAndValidate(invalidScore, SampleAuditResult.class))
                .isInstanceOf(AiException.class)
                .hasFieldOrPropertyWithValue("resultCode", AiResultCode.AI_RESPONSE_PARSE_ERROR)
                .hasMessageContaining("得分不能小于0");
    }
}
