package io.github.atengk.boot.sms.metrics;

import io.github.atengk.boot.sms.guard.SmsChannelCircuitBreaker;
import io.github.atengk.boot.sms.registry.SmsChannelRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Micrometer 的短信基础设施低基数度量指标采集组件
 * <p>
 * 严格限定使用低基数标签（{@code channel}、{@code supplier}、{@code outcome} 等），严禁将手机号、短信正文、
 * 业务流水号或动态租户标识无界透传为 Metric Tag；绑定 {@link SmsChannelRegistry} 时仅静态注册渠道保留原名，
 * 动态按需供给渠道统一收敛为 {@code dynamic}，未绑定注册中心时上限保留 64 个静态基数并收敛溢出项为 {@code other}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public class SmsMetrics implements SmsMetricsRecorder {

    public static final String METRIC_SEND_COUNT = "ateng.sms.send.count";
    public static final String METRIC_SEND_DURATION = "ateng.sms.send.duration";
    public static final String METRIC_RETRY_COUNT = "ateng.sms.retry.count";
    public static final String METRIC_FAILOVER_COUNT = "ateng.sms.failover.count";
    public static final String METRIC_CB_TRANSITION_COUNT = "ateng.sms.circuit_breaker.transition.count";
    public static final String METRIC_RATE_LIMITED_COUNT = "ateng.sms.rate_limit.rejected.count";
    public static final String METRIC_INTERCEPTED_COUNT = "ateng.sms.intercepted.count";
    public static final String METRIC_CHANNELS_STATIC_SIZE = "ateng.sms.channels.static.size";
    public static final String METRIC_CHANNELS_DYNAMIC_SIZE = "ateng.sms.channels.dynamic.size";
    public static final String METRIC_CHANNELS_DYNAMIC_CAPACITY = "ateng.sms.channels.dynamic.capacity";

    public static final String TAG_CHANNEL = "channel";
    public static final String TAG_SUPPLIER = "supplier";
    public static final String TAG_OUTCOME = "outcome";
    public static final String TAG_FROM = "from";
    public static final String TAG_TO = "to";
    public static final String TAG_NAMESPACE = "namespace";
    public static final String TAG_REASON = "reason";

    private static final String CHANNEL_DYNAMIC = "dynamic";
    private static final String CHANNEL_UNKNOWN = "unknown";
    private static final String CHANNEL_OTHER = "other";
    private static final int MAX_STANDALONE_CHANNEL_TAGS = 64;
    private static final int MAX_SUPPLIER_TAG_LENGTH = 32;

    @Getter
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean registryBound = new AtomicBoolean(false);
    private volatile SmsChannelRegistry channelRegistry;
    private final ReentrantLock tagGuardLock = new ReentrantLock();
    private final Set<String> trackedStandaloneChannels = new HashSet<>();

    public SmsMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, null);
    }

    public SmsMetrics(MeterRegistry meterRegistry, SmsChannelRegistry channelRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry 不能为空");
        if (channelRegistry != null) {
            bindChannelRegistry(channelRegistry);
        }
    }

    /**
     * 绑定渠道注册中心的静态数量、动态缓存数量与容量 Gauge（幂等一次绑定）
     *
     * @param channelRegistry 短信渠道注册中心
     */
    public void bindChannelRegistry(SmsChannelRegistry channelRegistry) {
        if (channelRegistry == null || !registryBound.compareAndSet(false, true)) {
            return;
        }
        this.channelRegistry = channelRegistry;
        Gauge.builder(METRIC_CHANNELS_STATIC_SIZE, channelRegistry, SmsChannelRegistry::size)
                .description("Number of registered static SMS channels")
                .register(meterRegistry);
        Gauge.builder(METRIC_CHANNELS_DYNAMIC_SIZE, channelRegistry, SmsChannelRegistry::dynamicSize)
                .description("Current number of cached dynamic SMS channels")
                .register(meterRegistry);
        Gauge.builder(METRIC_CHANNELS_DYNAMIC_CAPACITY, channelRegistry,
                        reg -> reg.getRegistryProperties() != null ? reg.getRegistryProperties().getDynamicCacheMaxSize() : 0)
                .description("Configured capacity of dynamic SMS channel LRU cache")
                .register(meterRegistry);
    }

    @Override
    public void recordSend(String channelName, String supplierCode, SmsSendOutcome outcome, Duration duration) {
        String channelTag = normalizeChannelTag(channelName);
        String supplierTag = normalizeLowCardinalityTag(supplierCode, "unknown", MAX_SUPPLIER_TAG_LENGTH);
        String outcomeTag = (outcome != null ? outcome : SmsSendOutcome.FAILURE).getTagValue();

        Counter.builder(METRIC_SEND_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_SUPPLIER, supplierTag)
                .tag(TAG_OUTCOME, outcomeTag)
                .description("Total count of SMS send invocations by channel, supplier and outcome")
                .register(meterRegistry)
                .increment();

        if (duration != null && !duration.isNegative()) {
            Timer.builder(METRIC_SEND_DURATION)
                    .tag(TAG_CHANNEL, channelTag)
                    .tag(TAG_SUPPLIER, supplierTag)
                    .tag(TAG_OUTCOME, outcomeTag)
                    .description("Duration distribution of SMS send operations")
                    .register(meterRegistry)
                    .record(duration);
        }
    }

    @Override
    public void recordRetry(String channelName, String supplierCode) {
        String channelTag = normalizeChannelTag(channelName);
        String supplierTag = normalizeLowCardinalityTag(supplierCode, "unknown", MAX_SUPPLIER_TAG_LENGTH);
        Counter.builder(METRIC_RETRY_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_SUPPLIER, supplierTag)
                .description("Total count of SMS delivery retries by channel and supplier")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordFailover(String primaryChannelName, String supplierCode, boolean success) {
        String channelTag = normalizeChannelTag(primaryChannelName);
        String supplierTag = normalizeLowCardinalityTag(supplierCode, "unknown", MAX_SUPPLIER_TAG_LENGTH);
        Counter.builder(METRIC_FAILOVER_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_SUPPLIER, supplierTag)
                .tag(TAG_OUTCOME, success ? "success" : "failure")
                .description("Total count of SMS cascade failover attempts by channel, supplier and outcome")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordCircuitBreakerTransition(String channelName,
                                               SmsChannelCircuitBreaker.State fromState,
                                               SmsChannelCircuitBreaker.State toState) {
        String channelTag = normalizeChannelTag(channelName);
        String fromTag = fromState != null ? fromState.name() : "UNKNOWN";
        String toTag = toState != null ? toState.name() : "UNKNOWN";
        Counter.builder(METRIC_CB_TRANSITION_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_FROM, fromTag)
                .tag(TAG_TO, toTag)
                .description("Total count of SMS channel circuit breaker state transitions")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordRateLimited(String namespace, String channelName) {
        String nsTag = normalizeLowCardinalityTag(namespace, "default", 16);
        String channelTag = normalizeChannelTag(channelName);
        Counter.builder(METRIC_RATE_LIMITED_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_NAMESPACE, nsTag)
                .description("Total count of SMS requests rejected by sliding-window rate limiter")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordBlocked(String channelName, String reasonType) {
        String channelTag = normalizeChannelTag(channelName);
        String reasonTag = normalizeLowCardinalityTag(reasonType, "policy", 32);
        Counter.builder(METRIC_INTERCEPTED_COUNT)
                .tag(TAG_CHANNEL, channelTag)
                .tag(TAG_REASON, reasonTag)
                .description("Total count of SMS requests blocked/intercepted before dispatch")
                .register(meterRegistry)
                .increment();
    }

    private String normalizeChannelTag(String channelName) {
        if (channelName == null || channelName.isBlank()) {
            return CHANNEL_UNKNOWN;
        }
        String trimmed = channelName.strip();
        if ("mock".equalsIgnoreCase(trimmed)) {
            return "mock";
        }
        if (CHANNEL_UNKNOWN.equals(trimmed) || "load-balance".equalsIgnoreCase(trimmed)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        SmsChannelRegistry registry = this.channelRegistry;
        if (registry != null) {
            // 仅静态注册渠道保留原名；按需动态租户渠道一律收敛为 dynamic 彻底防维度爆炸
            return registry.getChannels().containsKey(trimmed) ? trimmed : CHANNEL_DYNAMIC;
        }
        tagGuardLock.lock();
        try {
            if (trackedStandaloneChannels.contains(trimmed)) {
                return trimmed;
            }
            if (trackedStandaloneChannels.size() < MAX_STANDALONE_CHANNEL_TAGS) {
                trackedStandaloneChannels.add(trimmed);
                return trimmed;
            }
            return CHANNEL_OTHER;
        } finally {
            if (tagGuardLock.isHeldByCurrentThread()) {
                tagGuardLock.unlock();
            }
        }
    }

    private static String normalizeLowCardinalityTag(String raw, String fallback, int maxLen) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String clean = raw.strip().toLowerCase(Locale.ROOT);
        return clean.length() > maxLen ? clean.substring(0, maxLen) : clean;
    }
}
