package io.github.atengk.boot.mail.metrics;

import io.github.atengk.boot.mail.registry.MailAccountRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;

import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 基于 Micrometer 的邮件基础设施低基数度量指标采集组件
 * <p>
 * 严格限定使用低基数标签 {@code account} 与 {@code outcome}，严禁将收件人邮箱或邮件主题作为指标标签；
 * 当绑定 {@link MailAccountRegistry} 时，仅保留静态注册账号标识，动态租户账号统一收敛至 {@code dynamic} 标签，
 * 独立使用未绑定注册表时按上限（默认 64）截断并收敛至 {@code other}，彻底杜绝 Prometheus / TSDB 维度爆炸。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
public class MailMetrics implements MailMetricsRecorder {

    public static final String METRIC_SEND_COUNT = "ateng.mail.send.count";
    public static final String METRIC_SEND_DURATION = "ateng.mail.send.duration";
    public static final String METRIC_RETRY_COUNT = "ateng.mail.retry.count";
    public static final String METRIC_FAILOVER_COUNT = "ateng.mail.failover.count";
    public static final String METRIC_ACCOUNTS_STATIC_SIZE = "ateng.mail.accounts.static.size";
    public static final String METRIC_ACCOUNTS_DYNAMIC_SIZE = "ateng.mail.accounts.dynamic.size";
    public static final String METRIC_ACCOUNTS_DYNAMIC_CAPACITY = "ateng.mail.accounts.dynamic.capacity";

    public static final String TAG_ACCOUNT = "account";
    public static final String TAG_OUTCOME = "outcome";

    private static final String ACCOUNT_DYNAMIC = "dynamic";
    private static final String ACCOUNT_UNRESOLVED = "unresolved";
    private static final String ACCOUNT_UNKNOWN = "unknown";
    private static final String ACCOUNT_OTHER = "other";
    private static final int MAX_STANDALONE_ACCOUNT_TAGS = 64;

    @Getter
    private final MeterRegistry meterRegistry;

    private final AtomicBoolean registryBound = new AtomicBoolean(false);
    private volatile MailAccountRegistry accountRegistry;
    private final ReentrantLock tagGuardLock = new ReentrantLock();
    private final Set<String> trackedStandaloneAccounts = new HashSet<>();

    public MailMetrics(MeterRegistry meterRegistry) {
        this(meterRegistry, null);
    }

    public MailMetrics(MeterRegistry meterRegistry, MailAccountRegistry accountRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        if (accountRegistry != null) {
            bindAccountRegistry(accountRegistry);
        }
    }

    /**
     * 绑定发信账号注册中心的静态账号数与动态 LRU 缓存容量/占用概览 Gauge
     *
     * @param accountRegistry 发信账号注册中心
     */
    public void bindAccountRegistry(MailAccountRegistry accountRegistry) {
        if (accountRegistry == null || !registryBound.compareAndSet(false, true)) {
            return;
        }
        this.accountRegistry = accountRegistry;
        Gauge.builder(METRIC_ACCOUNTS_STATIC_SIZE, accountRegistry, MailAccountRegistry::size)
                .description("Number of registered static mail accounts")
                .register(meterRegistry);
        Gauge.builder(METRIC_ACCOUNTS_DYNAMIC_SIZE, accountRegistry, MailAccountRegistry::dynamicSize)
                .description("Current number of cached dynamic mail accounts")
                .register(meterRegistry);
        Gauge.builder(METRIC_ACCOUNTS_DYNAMIC_CAPACITY, accountRegistry, MailAccountRegistry::getMaxDynamicAccounts)
                .description("Maximum capacity of the dynamic mail account LRU cache")
                .register(meterRegistry);
    }

    @Override
    public void recordSend(String accountName, MailSendOutcome outcome, Duration duration) {
        String normalizedAccount = normalizeAccountTag(accountName);
        String outcomeTag = (outcome != null ? outcome : MailSendOutcome.FAILURE).getTagValue();

        Counter.builder(METRIC_SEND_COUNT)
                .tag(TAG_ACCOUNT, normalizedAccount)
                .tag(TAG_OUTCOME, outcomeTag)
                .description("Total count of mail send invocations by account and outcome")
                .register(meterRegistry)
                .increment();

        if (duration != null && !duration.isNegative()) {
            Timer.builder(METRIC_SEND_DURATION)
                    .tag(TAG_ACCOUNT, normalizedAccount)
                    .tag(TAG_OUTCOME, outcomeTag)
                    .description("Duration distribution of mail send operations by account and outcome")
                    .register(meterRegistry)
                    .record(duration);
        }
    }

    @Override
    public void recordRetry(String accountName) {
        String normalizedAccount = normalizeAccountTag(accountName);
        Counter.builder(METRIC_RETRY_COUNT)
                .tag(TAG_ACCOUNT, normalizedAccount)
                .description("Count of mail physical delivery retries by account")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordFailover(String primaryAccountName, boolean success) {
        String normalizedAccount = normalizeAccountTag(primaryAccountName);
        Counter.builder(METRIC_FAILOVER_COUNT)
                .tag(TAG_ACCOUNT, normalizedAccount)
                .tag(TAG_OUTCOME, success ? "success" : "failure")
                .description("Count of mail cascade failover attempts by primary account and outcome")
                .register(meterRegistry)
                .increment();
    }

    private String normalizeAccountTag(String accountName) {
        if (accountName == null || accountName.isBlank()) {
            return ACCOUNT_UNKNOWN;
        }
        String trimmed = accountName.strip();
        if (ACCOUNT_UNRESOLVED.equals(trimmed) || ACCOUNT_UNKNOWN.equals(trimmed)) {
            return trimmed;
        }
        MailAccountRegistry registry = this.accountRegistry;
        if (registry != null) {
            return registry.contains(trimmed) ? trimmed : ACCOUNT_DYNAMIC;
        }
        tagGuardLock.lock();
        try {
            if (trackedStandaloneAccounts.contains(trimmed)) {
                return trimmed;
            }
            if (trackedStandaloneAccounts.size() < MAX_STANDALONE_ACCOUNT_TAGS) {
                trackedStandaloneAccounts.add(trimmed);
                return trimmed;
            }
            return ACCOUNT_OTHER;
        } finally {
            if (tagGuardLock.isHeldByCurrentThread()) {
                tagGuardLock.unlock();
            }
        }
    }
}
