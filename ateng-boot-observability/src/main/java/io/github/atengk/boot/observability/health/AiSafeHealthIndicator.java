package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.ai.properties.AiModelConfig;
import io.github.atengk.boot.ai.properties.AtengAiProperties;
import io.github.atengk.boot.ai.registry.AiModelRegistry;
import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * AI 大模型基础设施受管安全健康探针
 * <p>
 * 默认仅检查 {@link AiModelRegistry} 静态注册模型与 {@link AtengAiProperties} 配置完整性，
 * 当未配置任何有效 API Key 时安全返回 {@code UNKNOWN}，避免待命态微服务触发虚假健康告警；
 * 显式开启 {@code probeModels=true} 时仅发起零 Token 计费的只读 {@code GET /models} 探测，
 * 默认应用 {@code 60s} 防刷缓存 TTL 并对错误详情中的凭据与密钥实施 {@code ******} 脱敏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class AiSafeHealthIndicator extends AbstractSafeHealthIndicator {

    /**
     * AI 健康探针默认防刷缓存有效期（60 秒）
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    private static final String MASK = "******";
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|secret|token|authorization|password)\\s*([=:])\\s*[^,;\\s]+"
    );
    private static final Pattern SENSITIVE_AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern SK_KEY_PATTERN = Pattern.compile(
            "(?i)\\bsk-[A-Za-z0-9_-]{8,}"
    );

    private final ObjectProvider<AiModelRegistry> registryProvider;
    private final ObjectProvider<AtengAiProperties> aiPropertiesProvider;
    private final boolean probeModels;
    private final HttpClient httpClient;

    public AiSafeHealthIndicator(
            ObjectProvider<AiModelRegistry> registryProvider,
            ObjectProvider<AtengAiProperties> aiPropertiesProvider,
            ObservabilityProperties properties) {
        this(registryProvider, aiPropertiesProvider, properties, null);
    }

    public AiSafeHealthIndicator(
            ObjectProvider<AiModelRegistry> registryProvider,
            ObjectProvider<AtengAiProperties> aiPropertiesProvider,
            ObservabilityProperties properties,
            Executor executor) {
        this(registryProvider, aiPropertiesProvider, properties, executor, SHARED_HTTP_CLIENT);
    }

    AiSafeHealthIndicator(
            ObjectProvider<AiModelRegistry> registryProvider,
            ObjectProvider<AtengAiProperties> aiPropertiesProvider,
            ObservabilityProperties properties,
            Executor executor,
            HttpClient httpClient) {
        super(
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getTimeout() : null,
                executor,
                resolveAiCacheTtl(properties),
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getDegradedEventCooldown() : null
        );
        this.registryProvider = registryProvider;
        this.aiPropertiesProvider = aiPropertiesProvider;
        this.probeModels = properties != null
                && properties.getHealth() != null
                && properties.getHealth().getAi() != null
                && properties.getHealth().getAi().isProbeModels();
        this.httpClient = httpClient != null ? httpClient : SHARED_HTTP_CLIENT;
    }

    @Override
    protected String getComponentName() {
        return "ai";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        AiModelRegistry registry = registryProvider != null ? registryProvider.getIfAvailable() : null;
        AtengAiProperties aiProperties = aiPropertiesProvider != null ? aiPropertiesProvider.getIfAvailable() : null;

        if (registry == null || !registry.hasModels()) {
            builder.unknown()
                    .withDetail("message", "未检测到已注册的可用 AI 模型实例，服务处于待命状态")
                    .withDetail("modelCount", 0);
            return;
        }

        Set<String> registeredNames = registry.getModelNames();
        List<AiModelConfig> activeConfigs = collectActiveConfigs(registeredNames, aiProperties);
        List<AiModelConfig> validKeyConfigs = activeConfigs.stream()
                .filter(cfg -> cfg != null && StringUtils.hasText(cfg.getApiKey()))
                .toList();

        if (validKeyConfigs.isEmpty()) {
            builder.unknown()
                    .withDetail("message", "未配置有效的 AI 模型 API Key，跳过物理探活")
                    .withDetail("modelCount", registeredNames.size())
                    .withDetail("registeredModels", registeredNames);
            return;
        }

        if (!this.probeModels) {
            builder.up()
                    .withDetail("mode", "static-config")
                    .withDetail("modelCount", registeredNames.size())
                    .withDetail("registeredModels", registeredNames);
            return;
        }

        Map<String, String> uniqueEndpoints = deduplicateEndpoints(validKeyConfigs);
        if (uniqueEndpoints.isEmpty()) {
            builder.unknown()
                    .withDetail("mode", "probe-models")
                    .withDetail("message", "未解析出有效的 AI 模型 Base URL 端点，跳过网络探活")
                    .withDetail("registeredModels", registeredNames);
            return;
        }

        List<String> healthyEndpoints = new ArrayList<>();
        Map<String, String> failedEndpoints = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : uniqueEndpoints.entrySet()) {
            String modelsUrl = entry.getKey();
            String apiKey = entry.getValue();
            try {
                probeSingleModelsEndpoint(modelsUrl, apiKey);
                healthyEndpoints.add(modelsUrl);
            } catch (Exception ex) {
                failedEndpoints.put(modelsUrl, sanitizeErrorMessage(ex, apiKey));
            }
        }

        if (!failedEndpoints.isEmpty()) {
            builder.down()
                    .withDetail("mode", "probe-models")
                    .withDetail("modelCount", registeredNames.size())
                    .withDetail("healthyEndpoints", healthyEndpoints)
                    .withDetail("failedEndpoints", failedEndpoints);
            return;
        }

        builder.up()
                .withDetail("mode", "probe-models")
                .withDetail("modelCount", registeredNames.size())
                .withDetail("registeredModels", registeredNames)
                .withDetail("probedEndpoints", healthyEndpoints);
    }

    private void probeSingleModelsEndpoint(String modelsUrl, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(modelsUrl))
                .timeout(getTimeout())
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = response.body() != null ? response.body() : "";
            throw new IllegalStateException("HTTP " + status + (body.isBlank() ? "" : " - " + body));
        }
    }

    private static List<AiModelConfig> collectActiveConfigs(Set<String> registeredNames, AtengAiProperties aiProperties) {
        if (aiProperties == null) {
            return List.of();
        }
        List<AiModelConfig> configs = new ArrayList<>();
        if (registeredNames.contains(AiModelRegistry.DEFAULT_MODEL_NAME) && aiProperties.getModel() != null) {
            configs.add(aiProperties.getModel());
        }
        Map<String, AiModelConfig> namedModels = aiProperties.getModels();
        if (namedModels != null && !namedModels.isEmpty()) {
            for (String name : registeredNames) {
                AiModelConfig named = namedModels.get(name);
                if (named != null) {
                    configs.add(named);
                }
            }
        }
        // 当默认模型注册了别名但仅通过 default properties 配置时补充纳入主配置
        if (configs.isEmpty() && aiProperties.getModel() != null) {
            configs.add(aiProperties.getModel());
        }
        return configs;
    }

    private static Map<String, String> deduplicateEndpoints(List<AiModelConfig> configs) {
        Map<String, String> endpoints = new LinkedHashMap<>();
        for (AiModelConfig config : configs) {
            if (config == null || !StringUtils.hasText(config.getBaseUrl()) || !StringUtils.hasText(config.getApiKey())) {
                continue;
            }
            endpoints.putIfAbsent(normalizeModelsUrl(config.getBaseUrl()), config.getApiKey().trim());
        }
        return endpoints;
    }

    private static String normalizeModelsUrl(String rawBaseUrl) {
        String clean = rawBaseUrl.trim();
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.endsWith("/models")) {
            return clean;
        }
        return clean + "/models";
    }

    /**
     * 对异常与响应详情中的 Bearer Token、API Key 及 sk-* 凭据实施统一掩码脱敏
     */
    static String sanitizeErrorMessage(Throwable ex, String apiKey) {
        String raw = extractReadableErrorMessage(ex);
        if (raw == null || raw.isBlank()) {
            return "AI /models 只读健康探测异常";
        }
        String sanitized = raw;
        if (StringUtils.hasText(apiKey)) {
            sanitized = sanitized.replace(apiKey.trim(), MASK);
        }
        sanitized = SENSITIVE_AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1 " + MASK);
        sanitized = SENSITIVE_KV_PATTERN.matcher(sanitized).replaceAll("$1$2" + MASK);
        sanitized = SK_KEY_PATTERN.matcher(sanitized).replaceAll(MASK);
        return sanitized;
    }

    private static String extractReadableErrorMessage(Throwable ex) {
        if (ex == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < 5) {
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()) {
                if (sb.length() == 0) {
                    sb.append(msg);
                } else if (!sb.toString().contains(msg)) {
                    sb.append(" | cause: ").append(msg);
                }
            }
            current = current.getCause();
            depth++;
        }
        return sb.length() > 0 ? sb.toString() : ex.getClass().getSimpleName();
    }

    private static Duration resolveAiCacheTtl(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null && properties.getHealth().getAi() != null) {
            Duration aiTtl = properties.getHealth().getAi().getCacheTtl();
            if (aiTtl != null) {
                return aiTtl;
            }
        }
        return DEFAULT_CACHE_TTL;
    }
}
