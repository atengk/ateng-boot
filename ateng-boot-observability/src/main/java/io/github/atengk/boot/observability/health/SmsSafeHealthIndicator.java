package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.model.SmsBalanceResult;
import io.github.atengk.boot.sms.model.SmsChannel;
import io.github.atengk.boot.sms.model.SmsSupplier;
import io.github.atengk.boot.sms.provider.SmsProvider;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.github.atengk.boot.sms.template.SmsTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;

import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

/**
 * 短信通信基础设施受管安全健康探针
 * <p>
 * 仅针对 {@link SmsChannelRegistry} 中静态预热的短信渠道评估配置有效性、{@link SmsChannelCircuitBreaker} 熔断态
 * 及可选只读余额探查（{@code ateng.boot.observability.health.sms.probe-balance=true}），
 * 自动跳过动态按需供给渠道缓存以避免外部租户渠道下线污染集群健康度；静态渠道为空时安全返回 {@code UNKNOWN}，
 * 静态渠道全熔断或均不可用时返回 {@code DOWN}。全程触发 0 条真实扣费短信，默认 {@code 60s} 防刷缓存并脱敏异常中的凭据密钥。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
public class SmsSafeHealthIndicator extends AbstractSafeHealthIndicator {

    /**
     * 短信探针默认防刷缓存 TTL（60 秒）
     */
    public static final Duration DEFAULT_CACHE_TTL = Duration.ofSeconds(60);

    private static final String MASK = "******";
    private static final Pattern SENSITIVE_KV_PATTERN = Pattern.compile(
            "(?i)(access[_-]?key[_-]?(?:id|secret)|secret[_-]?(?:id|key)|app[_-]?(?:id|key|secret)|password|passwd|pwd|api[_-]?key|secret|token|authorization|auth)\\s*([=:])\\s*[^,;\\s]+"
    );
    private static final Pattern SENSITIVE_AUTH_HEADER_PATTERN = Pattern.compile(
            "(?i)(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern URI_CREDENTIALS_PATTERN = Pattern.compile(
            "(://[^:/@\\s]+:)[^@\\s]+(@)"
    );

    private final ObjectProvider<SmsChannelRegistry> registryProvider;
    private final ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider;
    private final ObjectProvider<SmsTemplate> smsTemplateProvider;
    private final boolean probeBalance;

    public SmsSafeHealthIndicator(ObjectProvider<SmsChannelRegistry> registryProvider,
                                  ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider,
                                  ObjectProvider<SmsTemplate> smsTemplateProvider) {
        this(registryProvider, circuitBreakerProvider, smsTemplateProvider, (ObservabilityProperties) null, null);
    }

    public SmsSafeHealthIndicator(ObjectProvider<SmsChannelRegistry> registryProvider,
                                  ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider,
                                  ObjectProvider<SmsTemplate> smsTemplateProvider,
                                  ObservabilityProperties properties) {
        this(registryProvider, circuitBreakerProvider, smsTemplateProvider, properties, null);
    }

    public SmsSafeHealthIndicator(ObjectProvider<SmsChannelRegistry> registryProvider,
                                  ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider,
                                  ObjectProvider<SmsTemplate> smsTemplateProvider,
                                  ObservabilityProperties properties,
                                  Executor executor) {
        super(
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getTimeout() : null,
                executor,
                resolveSmsCacheTtl(properties),
                (properties != null && properties.getHealth() != null) ? properties.getHealth().getDegradedEventCooldown() : null
        );
        this.registryProvider = registryProvider;
        this.circuitBreakerProvider = circuitBreakerProvider;
        this.smsTemplateProvider = smsTemplateProvider;
        this.probeBalance = properties != null
                && properties.getHealth() != null
                && properties.getHealth().getSms() != null
                && properties.getHealth().getSms().isProbeBalance();
    }

    public SmsSafeHealthIndicator(ObjectProvider<SmsChannelRegistry> registryProvider,
                                  ObjectProvider<SmsChannelCircuitBreaker> circuitBreakerProvider,
                                  ObjectProvider<SmsTemplate> smsTemplateProvider,
                                  Duration timeout,
                                  Duration cacheTtl,
                                  boolean probeBalance,
                                  Executor executor) {
        super(timeout, executor, cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL, Duration.ZERO);
        this.registryProvider = registryProvider;
        this.circuitBreakerProvider = circuitBreakerProvider;
        this.smsTemplateProvider = smsTemplateProvider;
        this.probeBalance = probeBalance;
    }

    @Override
    protected String getComponentName() {
        return "sms";
    }

    @Override
    protected void doCheck(Health.Builder builder) throws Exception {
        SmsChannelRegistry registry = registryProvider != null ? registryProvider.getIfAvailable() : null;
        if (registry == null) {
            builder.unknown().withDetail("message", "未检测到已装配的 SmsChannelRegistry 实例");
            return;
        }

        Collection<SmsChannel> rawStaticChannels = registry.getAll();
        int dynamicCount = safeDynamicSize(registry);
        if (rawStaticChannels == null || rawStaticChannels.isEmpty()) {
            builder.unknown()
                    .withDetail("message", "未配置静态短信渠道，跳过健康探测")
                    .withDetail("staticChannelCount", 0)
                    .withDetail("dynamicChannelCount", dynamicCount);
            return;
        }

        List<SmsChannel> staticChannels = rawStaticChannels.stream()
                .filter(ch -> ch != null && ch.getName() != null && !ch.getName().isBlank())
                .sorted(Comparator.comparing(SmsChannel::getName))
                .toList();
        if (staticChannels.isEmpty()) {
            builder.unknown()
                    .withDetail("message", "未配置有效的静态短信渠道，跳过健康探测")
                    .withDetail("staticChannelCount", 0)
                    .withDetail("dynamicChannelCount", dynamicCount);
            return;
        }

        SmsChannelCircuitBreaker circuitBreaker = resolveCircuitBreaker();
        SmsTemplate smsTemplate = smsTemplateProvider != null ? smsTemplateProvider.getIfAvailable() : null;

        Map<String, Object> channelsDetail = new LinkedHashMap<>();
        int healthyCount = 0;
        int openCircuitCount = 0;
        int unhealthyCount = 0;

        for (SmsChannel channel : staticChannels) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("SMS health probe interrupted");
            }
            Map<String, Object> chInfo = probeStaticChannel(channel, circuitBreaker, smsTemplate);
            channelsDetail.put(channel.getName(), chInfo);
            String stateStr = String.valueOf(chInfo.get("status"));
            if ("UP".equals(stateStr)) {
                healthyCount++;
            } else {
                unhealthyCount++;
                if (Boolean.TRUE.equals(chInfo.get("circuitOpen"))) {
                    openCircuitCount++;
                }
            }
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("staticChannelCount", staticChannels.size());
        details.put("dynamicChannelCount", dynamicCount);
        details.put("healthyCount", healthyCount);
        details.put("unhealthyCount", unhealthyCount);
        details.put("openCircuitCount", openCircuitCount);
        details.put("probeBalanceEnabled", probeBalance);
        details.put("channels", channelsDetail);

        if (healthyCount == 0 || openCircuitCount >= staticChannels.size()) {
            builder.down()
                    .withDetail("reason", openCircuitCount >= staticChannels.size()
                            ? "所有静态短信渠道均处于熔断开路状态 (OPEN)"
                            : "所有静态短信渠道均不可用")
                    .withDetails(details);
        } else {
            builder.up().withDetails(details);
        }
    }

    private SmsChannelCircuitBreaker resolveCircuitBreaker() {
        SmsChannelCircuitBreaker cb = circuitBreakerProvider != null ? circuitBreakerProvider.getIfAvailable() : null;
        if (cb != null) {
            return cb;
        }
        SmsTemplate template = smsTemplateProvider != null ? smsTemplateProvider.getIfAvailable() : null;
        return template != null ? template.getCircuitBreaker() : null;
    }

    private Map<String, Object> probeStaticChannel(SmsChannel channel,
                                                   SmsChannelCircuitBreaker circuitBreaker,
                                                   SmsTemplate smsTemplate) throws InterruptedException {
        Map<String, Object> info = new LinkedHashMap<>();
        String providerCode = channel.resolveProviderCode();
        info.put("supplier", channel.getSupplier() != null ? channel.getSupplier().name() : "CUSTOM");
        if (providerCode != null) {
            info.put("providerCode", providerCode);
        }

        // 1. 静态配置有效性校验
        boolean configValid = isChannelConfigValid(channel, smsTemplate);
        info.put("configValid", configValid);
        if (!configValid) {
            info.put("status", "DOWN");
            info.put("error", "渠道关键驱动或凭据配置不完整");
            return info;
        }

        // 2. 熔断器状态检查
        SmsChannelCircuitBreaker.State cbState = circuitBreaker != null
                ? circuitBreaker.getState(channel.getName())
                : SmsChannelCircuitBreaker.State.CLOSED;
        info.put("circuitState", cbState.name());
        if (cbState == SmsChannelCircuitBreaker.State.OPEN) {
            info.put("circuitOpen", true);
            info.put("status", "DOWN");
            info.put("error", "渠道处于短时熔断开路态 (OPEN)");
            return info;
        }
        info.put("circuitOpen", false);

        // 3. 可选只读余额探查（全程不发任何计费短信）
        if (probeBalance && smsTemplate != null) {
            try {
                SmsBalanceResult balanceResult = smsTemplate.queryBalance(channel.getName());
                if (balanceResult != null && balanceResult.isSupported()) {
                    Map<String, Object> balanceInfo = new LinkedHashMap<>();
                    if (balanceResult.getBalance() != null) {
                        balanceInfo.put("balance", balanceResult.getBalance());
                    }
                    if (balanceResult.getRemainingCount() != null) {
                        balanceInfo.put("remainingCount", balanceResult.getRemainingCount());
                    }
                    if (balanceResult.getUnit() != null) {
                        balanceInfo.put("unit", balanceResult.getUnit());
                    }
                    info.put("balance", balanceInfo);
                } else {
                    info.put("balanceSupported", false);
                }
            } catch (Throwable ex) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("SMS balance health probe interrupted");
                }
                String sanitized = sanitizeErrorMessage(ex, channel);
                info.put("status", "DOWN");
                info.put("error", sanitized);
                log.warn("SmsSafeHealthIndicator 静态渠道 [{}] 余额探查异常: {}", channel.getName(), sanitized);
                return info;
            }
        }

        info.put("status", "UP");
        return info;
    }

    private boolean isChannelConfigValid(SmsChannel channel, SmsTemplate smsTemplate) {
        String providerCode = channel.resolveProviderCode();
        if (providerCode == null || providerCode.isBlank()) {
            return false;
        }
        boolean isMock = channel.getSupplier() == SmsSupplier.MOCK || "mock".equalsIgnoreCase(providerCode);
        boolean isCustom = channel.getSupplier() == SmsSupplier.CUSTOM;
        if (!isMock && !isCustom) {
            boolean hasAnyCredential = hasText(channel.getAccessKeyId())
                    || hasText(channel.getAccessKeySecret())
                    || hasText(channel.getAppId());
            if (!hasAnyCredential) {
                return false;
            }
        }
        if (smsTemplate != null) {
            SmsProvider provider = smsTemplate.getProvider(providerCode);
            if (provider != null && !provider.isAvailable(channel)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 脱敏异常信息中的访问密钥、认证令牌、代理密码及 URI 认证凭据
     */
    static String sanitizeErrorMessage(Throwable ex, SmsChannel channel) {
        String raw = extractReadableErrorMessage(ex);
        if (raw == null || raw.isBlank()) {
            return "短信渠道只读探查异常";
        }
        String sanitized = raw;
        if (channel != null) {
            sanitized = maskLiteralSecret(sanitized, channel.getAccessKeySecret());
            sanitized = maskLiteralSecret(sanitized, channel.getAccessKeyId());
            if (channel.getProxy() != null) {
                sanitized = maskLiteralSecret(sanitized, channel.getProxy().getPassword());
                sanitized = maskLiteralSecret(sanitized, channel.getProxy().getUsername());
            }
        }
        sanitized = URI_CREDENTIALS_PATTERN.matcher(sanitized).replaceAll("$1" + MASK + "$2");
        sanitized = SENSITIVE_KV_PATTERN.matcher(sanitized).replaceAll("$1$2" + MASK);
        sanitized = SENSITIVE_AUTH_HEADER_PATTERN.matcher(sanitized).replaceAll("$1 " + MASK);
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

    private static String maskLiteralSecret(String text, String secret) {
        if (text == null || secret == null || secret.isBlank()) {
            return text;
        }
        return text.replace(secret, MASK);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static int safeDynamicSize(SmsChannelRegistry registry) {
        try {
            return registry.dynamicSize();
        } catch (Exception ex) {
            log.debug("获取动态短信渠道缓存数量失败: {}", ex.getMessage());
            return 0;
        }
    }

    private static Duration resolveSmsCacheTtl(ObservabilityProperties properties) {
        if (properties != null && properties.getHealth() != null && properties.getHealth().getSms() != null) {
            Duration smsTtl = properties.getHealth().getSms().getCacheTtl();
            if (smsTtl != null) {
                return smsTtl;
            }
        }
        return DEFAULT_CACHE_TTL;
    }
}
