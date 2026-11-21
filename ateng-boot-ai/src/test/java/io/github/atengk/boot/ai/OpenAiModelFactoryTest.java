package io.github.atengk.boot.ai;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.factory.OpenAiModelFactory;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAI 模型工厂单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class OpenAiModelFactoryTest {

    @Test
    @DisplayName("构建同步与流式模型成功用例")
    void testCreateModelsSuccess() {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test-api-key");
        config.setModelName("gpt-4o-mini");
        config.setTemperature(0.5);
        config.setTopP(0.9);
        config.setMaxTokens(2048);
        config.setTimeout(Duration.ofSeconds(30));
        config.setMaxRetries(2);
        config.setLogRequests(true);
        config.setLogResponses(true);
        config.setCustomHeaders(Map.of("X-Custom-Header", "value123"));

        OpenAiChatModel chatModel = OpenAiModelFactory.createChatModel(config, Collections.emptyList());
        assertThat(chatModel).isNotNull();
        assertThat(chatModel.modelName()).isEqualTo("gpt-4o-mini");

        OpenAiStreamingChatModel streamingChatModel = OpenAiModelFactory.createStreamingChatModel(config, Collections.emptyList());
        assertThat(streamingChatModel).isNotNull();

        io.github.atengk.boot.ai.streaming.ActiveStreamRegistry registry = new io.github.atengk.boot.ai.streaming.ActiveStreamRegistry();
        OpenAiStreamingChatModel streamingModelWithRegistry = OpenAiModelFactory.createStreamingChatModel(config, Collections.emptyList(), registry);
        assertThat(streamingModelWithRegistry).isNotNull();
    }

    @Test
    @DisplayName("对抗性测试：配置对象为空时抛出 AI_CONFIG_ERROR")
    void testNullConfigThrows() {
        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(null, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));

        assertThatThrownBy(() -> OpenAiModelFactory.createStreamingChatModel(null, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 API Key 时抛出 AI_CONFIG_ERROR")
    void testMissingApiKeyThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("   ");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 Base URL 时抛出 AI_CONFIG_ERROR")
    void testMissingBaseUrlThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("test-key");
        config.setBaseUrl("");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("对抗性测试：缺少 Model Name 时抛出 AI_CONFIG_ERROR")
    void testMissingModelNameThrows() {
        AiModelConfig config = new AiModelConfig();
        config.setApiKey("test-key");
        config.setModelName("  ");

        assertThatThrownBy(() -> OpenAiModelFactory.createChatModel(config, null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }

    @Test
    @DisplayName("开启 strictNativeJsonSchema 时，NativeJsonSchemaInterceptor 将请求体注入原生 json_schema 规范结构体")
    void testStrictNativeJsonSchemaRequestInjection() throws Exception {
        AiModelConfig config = new AiModelConfig();
        config.setBaseUrl("https://api.openai.com/v1");
        config.setApiKey("test-key");
        config.setModelName("gpt-4o");
        config.setResponseFormat(io.github.atengk.boot.ai.properties.AiResponseFormat.JSON_SCHEMA);
        config.setStrictNativeJsonSchema(true);

        OpenAiChatModel chatModel = OpenAiModelFactory.createChatModel(config, Collections.emptyList());
        assertThat(chatModel).isNotNull();

        String strictSchema = io.github.atengk.boot.ai.sanitizer.JsonSchemaGenerator.generateStrictSchema(TestSchemaDto.class);
        io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaInterceptor interceptor = new io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaInterceptor();

        okhttp3.Request rawRequest = new okhttp3.Request.Builder()
                .url("https://api.openai.com/v1/chat/completions")
                .post(okhttp3.RequestBody.create(
                        "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"response_format\":{\"type\":\"json_object\"}}",
                        okhttp3.MediaType.get("application/json; charset=utf-8")))
                .build();

        java.util.concurrent.atomic.AtomicReference<String> capturedBody = new java.util.concurrent.atomic.AtomicReference<>();
        okhttp3.Interceptor.Chain mockChain = new okhttp3.Interceptor.Chain() {
            @Override public okhttp3.Request request() { return rawRequest; }
            @Override public okhttp3.Response proceed(okhttp3.Request request) throws java.io.IOException {
                okio.Buffer buf = new okio.Buffer();
                request.body().writeTo(buf);
                capturedBody.set(buf.readUtf8());
                return new okhttp3.Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(okhttp3.ResponseBody.create("{}", okhttp3.MediaType.get("application/json")))
                        .build();
            }
            @Override public okhttp3.Connection connection() { return null; }
            @Override public okhttp3.Call call() { return null; }
            @Override public int connectTimeoutMillis() { return 1000; }
            @Override public okhttp3.Interceptor.Chain withConnectTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
            @Override public int readTimeoutMillis() { return 1000; }
            @Override public okhttp3.Interceptor.Chain withReadTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
            @Override public int writeTimeoutMillis() { return 1000; }
            @Override public okhttp3.Interceptor.Chain withWriteTimeout(int timeout, java.util.concurrent.TimeUnit unit) { return this; }
        };

        try (var ignored = io.github.atengk.boot.ai.sanitizer.NativeJsonSchemaContext.openScope("TestSchemaDto", strictSchema)) {
            interceptor.intercept(mockChain);
        }

        com.fasterxml.jackson.databind.JsonNode root = io.github.atengk.boot.core.util.JsonUtil.readTree(capturedBody.get());
        assertThat(root.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(root.path("response_format").path("json_schema").path("name").asText()).isEqualTo("TestSchemaDto");
        assertThat(root.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(root.path("response_format").path("json_schema").path("schema").path("additionalProperties").asBoolean()).isFalse();
    }

    static class TestSchemaDto {
        private String title;
        private int count;
    }
}
