package io.github.atengk.boot.observability.health;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.support.StaticApplicationContext;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * AiSafeHealthIndicator 单元测试
 *
 * @author Ateng
 * @since 2026-11-21
 */
@DisplayName("AiSafeHealthIndicator AI 安全健康探针单元测试")
class AiSafeHealthIndicatorTest {

    @Test
    @DisplayName("未配置有效 API Key 或无已注册模型时安全返回 UNKNOWN（保护待命态微服务）")
    void testMissingApiKeyReturnsUnknown() {
        AiModelRegistry emptyRegistry = new AiModelRegistry();
        AtengAiProperties aiProps = new AtengAiProperties();
        aiProps.getModel().setBaseUrl("https://api.openai.com/v1");
        aiProps.getModel().setApiKey("   ");

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(AiModelRegistry.class, () -> emptyRegistry);
        context.registerBean(AtengAiProperties.class, () -> aiProps);
        context.refresh();

        AiSafeHealthIndicator indicator = new AiSafeHealthIndicator(
                context.getBeanProvider(AiModelRegistry.class),
                context.getBeanProvider(AtengAiProperties.class),
                new ObservabilityProperties()
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(indicator.getCacheTtl()).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("默认 probeModels=false 且存在有效静态注册模型与 API Key 时返回 UP 且零网络请求")
    void testValidStaticConfigReturnsUpWithoutHttpProbe() {
        AiModelRegistry registry = new AiModelRegistry();
        registry.registerDefault(mock(ChatLanguageModel.class), null, "gpt-4o");

        AtengAiProperties aiProps = new AtengAiProperties();
        aiProps.getModel().setBaseUrl("https://api.openai.com/v1");
        aiProps.getModel().setApiKey("sk-live-secret-1234567890");
        aiProps.getModel().setModelName("gpt-4o");

        StaticApplicationContext context = new StaticApplicationContext();
        context.registerBean(AiModelRegistry.class, () -> registry);
        context.registerBean(AtengAiProperties.class, () -> aiProps);
        context.refresh();

        AiSafeHealthIndicator indicator = new AiSafeHealthIndicator(
                context.getBeanProvider(AiModelRegistry.class),
                context.getBeanProvider(AtengAiProperties.class),
                new ObservabilityProperties()
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("mode", "static-config");
        assertThat(health.getDetails().toString()).doesNotContain("sk-live-secret-1234567890");
    }

    @Test
    @DisplayName("开启 probeModels=true 时发起只读 GET /models 探活、命中 60s 防刷缓存且不调用 /chat/completions")
    void testProbeModelsUsesReadOnlyGetModelsAndCacheTtl() throws Exception {
        AtomicInteger modelsHitCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> capturedPaths = new CopyOnWriteArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            capturedPaths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            modelsHitCount.incrementAndGet();
            byte[] resp = "{\"data\":[{\"id\":\"gpt-4o\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            AiModelRegistry registry = new AiModelRegistry();
            registry.registerDefault(mock(ChatLanguageModel.class), null, "gpt-4o");

            AtengAiProperties aiProps = new AtengAiProperties();
            aiProps.getModel().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            aiProps.getModel().setApiKey("sk-probe-token-99999");
            aiProps.getModel().setModelName("gpt-4o");

            ObservabilityProperties obsProps = new ObservabilityProperties();
            obsProps.getHealth().getAi().setProbeModels(true);
            obsProps.getHealth().getAi().setCacheTtl(Duration.ofSeconds(60));

            StaticApplicationContext context = new StaticApplicationContext();
            context.registerBean(AiModelRegistry.class, () -> registry);
            context.registerBean(AtengAiProperties.class, () -> aiProps);
            context.refresh();

            AiSafeHealthIndicator indicator = new AiSafeHealthIndicator(
                    context.getBeanProvider(AiModelRegistry.class),
                    context.getBeanProvider(AtengAiProperties.class),
                    obsProps
            );

            Health first = indicator.health();
            Health second = indicator.health();

            assertThat(first.getStatus()).isEqualTo(Status.UP);
            assertThat(second.getStatus()).isEqualTo(Status.UP);
            assertThat(modelsHitCount.get()).isEqualTo(1);
            assertThat(capturedPaths).containsExactly("GET /v1/models");
            assertThat(first.getDetails()).containsEntry("mode", "probe-models");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("probeModels=true 探活失败或响应异常时，错误详情中的 Bearer Token 与 API Key 自动脱敏为 ******")
    void testProbeModelsFailureMasksBearerTokenAndApiKey() throws Exception {
        String leakedKey = "sk-very-secret-key-abcdefg";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            byte[] resp = ("Unauthorized with Bearer " + leakedKey + " and apiKey=" + leakedKey)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            AiModelRegistry registry = new AiModelRegistry();
            registry.register("qwen", mock(ChatLanguageModel.class));

            AtengAiProperties aiProps = new AtengAiProperties();
            AiModelConfig cfg = new AiModelConfig();
            cfg.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            cfg.setApiKey(leakedKey);
            cfg.setModelName("qwen-max");
            aiProps.setModels(java.util.Map.of("qwen", cfg));

            ObservabilityProperties obsProps = new ObservabilityProperties();
            obsProps.getHealth().getAi().setProbeModels(true);
            obsProps.getHealth().getAi().setCacheTtl(Duration.ZERO);

            StaticApplicationContext context = new StaticApplicationContext();
            context.registerBean(AiModelRegistry.class, () -> registry);
            context.registerBean(AtengAiProperties.class, () -> aiProps);
            context.refresh();

            AiSafeHealthIndicator indicator = new AiSafeHealthIndicator(
                    context.getBeanProvider(AiModelRegistry.class),
                    context.getBeanProvider(AtengAiProperties.class),
                    obsProps
            );

            Health health = indicator.health();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            String serializedDetails = health.getDetails().toString();
            assertThat(serializedDetails).doesNotContain(leakedKey);
            assertThat(serializedDetails).contains("******");
        } finally {
            server.stop(0);
        }
    }
}
