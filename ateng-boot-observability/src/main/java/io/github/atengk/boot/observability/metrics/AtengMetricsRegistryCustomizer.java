package io.github.atengk.boot.observability.metrics;

import io.github.atengk.boot.observability.properties.ObservabilityProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 全局度量注册表定制器与 Prometheus 高基数防爆护栏
 * <p>
 * 统一实现以下生产治理防线：
 * 1. 自动注入 {@code application} 应用标识标签（来自 {@code spring.application.name}）与全局自定义 {@code common-tags}；
 * 2. 按前缀黑名单（{@code deny-metric-prefixes}）丢弃冗余噪音指标；
 * 3. 对超过 {@code max-tag-value-length} 的超长标签值执行自动截断，防止恶意业务文本撑爆标签索引；
 * 4. 对单指标不同标签组合实施 {@code max-meter-cardinality} 基数上限熔断（默认 1000），配合 {@code onMeterRemoved} 同步释放额度，杜绝 TSDB 维度爆炸与内存泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-16
 */
@Slf4j
public class AtengMetricsRegistryCustomizer implements MeterRegistryCustomizer<MeterRegistry> {

    private static final String APPLICATION_TAG_KEY = "application";
    private static final String APPLICATION_NAME_PROPERTY = "spring.application.name";

    private final ObservabilityProperties properties;
    private final Environment environment;

    /**
     * 构造全局度量注册表定制器
     *
     * @param properties 可观测性配置属性
     * @param environment Spring 环境上下文（用于解析 {@code spring.application.name}）
     */
    public AtengMetricsRegistryCustomizer(ObservabilityProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void customize(MeterRegistry registry) {
        if (registry == null) {
            return;
        }
        ObservabilityProperties.MetricsProperties metrics = (properties != null) ? properties.getMetrics() : null;
        if (metrics == null) {
            return;
        }

        // 1. 指标名称前缀黑名单过滤
        List<String> denyPrefixes = normalizeDenyPrefixes(metrics.getDenyMetricPrefixes());
        if (!denyPrefixes.isEmpty()) {
            registry.config().meterFilter(new DenyPrefixMeterFilter(denyPrefixes));
        }

        // 2. 全局公共标签注入（spring.application.name + 自定义 common-tags）
        List<Tag> resolvedCommonTags = resolveCommonTags(metrics);
        if (!resolvedCommonTags.isEmpty()) {
            registry.config().commonTags(resolvedCommonTags);
        }

        // 3. 超长标签值自动截断保护
        int maxTagValueLength = metrics.getMaxTagValueLength();
        if (maxTagValueLength > 0) {
            registry.config().meterFilter(new TruncatingTagValueMeterFilter(maxTagValueLength));
        }

        // 4. 单指标最大标签组合基数熔断器（每个 MeterRegistry 独立追踪）
        int maxCardinality = metrics.getMaxMeterCardinality();
        if (maxCardinality > 0) {
            CardinalityCircuitBreakerFilter cardinalityFilter = new CardinalityCircuitBreakerFilter(maxCardinality);
            registry.config().meterFilter(cardinalityFilter);
            registry.config().onMeterRemoved(meter -> cardinalityFilter.onMeterRemoved(meter.getId()));
        }
    }

    private List<Tag> resolveCommonTags(ObservabilityProperties.MetricsProperties metrics) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (metrics.isIncludeApplicationTag() && environment != null) {
            String appName = environment.getProperty(APPLICATION_NAME_PROPERTY);
            if (StringUtils.hasText(appName)) {
                merged.put(APPLICATION_TAG_KEY, appName.strip());
            }
        }
        Map<String, String> configuredCommonTags = metrics.getCommonTags();
        if (configuredCommonTags != null && !configuredCommonTags.isEmpty()) {
            configuredCommonTags.forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    merged.put(key.strip(), value);
                }
            });
        }
        if (merged.isEmpty()) {
            return List.of();
        }
        List<Tag> tags = new ArrayList<>(merged.size());
        merged.forEach((key, value) -> tags.add(Tag.of(key, value)));
        return tags;
    }

    private static List<String> normalizeDenyPrefixes(List<String> rawPrefixes) {
        if (rawPrefixes == null || rawPrefixes.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>(rawPrefixes.size());
        for (String prefix : rawPrefixes) {
            if (StringUtils.hasText(prefix)) {
                normalized.add(prefix.strip());
            }
        }
        return normalized;
    }

    private static final class DenyPrefixMeterFilter implements MeterFilter {

        private final List<String> denyPrefixes;

        private DenyPrefixMeterFilter(List<String> denyPrefixes) {
            this.denyPrefixes = denyPrefixes;
        }

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            String metricName = id.getName();
            for (String prefix : denyPrefixes) {
                if (metricName.startsWith(prefix)) {
                    return MeterFilterReply.DENY;
                }
            }
            return MeterFilterReply.NEUTRAL;
        }
    }

    private static final class TruncatingTagValueMeterFilter implements MeterFilter {

        private final int maxTagValueLength;

        private TruncatingTagValueMeterFilter(int maxTagValueLength) {
            this.maxTagValueLength = maxTagValueLength;
        }

        @Override
        public Meter.Id map(Meter.Id id) {
            List<Tag> tags = id.getTags();
            if (tags.isEmpty()) {
                return id;
            }
            boolean modified = false;
            List<Tag> updatedTags = new ArrayList<>(tags.size());
            for (Tag tag : tags) {
                String value = tag.getValue();
                if (value.length() > maxTagValueLength) {
                    updatedTags.add(Tag.of(tag.getKey(), value.substring(0, maxTagValueLength)));
                    modified = true;
                } else {
                    updatedTags.add(tag);
                }
            }
            return modified ? id.replaceTags(updatedTags) : id;
        }
    }

    private static final class CardinalityCircuitBreakerFilter implements MeterFilter {

        private final int maxCardinality;
        private final ConcurrentHashMap<String, MetricSeriesTracker> trackers = new ConcurrentHashMap<>();

        private CardinalityCircuitBreakerFilter(int maxCardinality) {
            this.maxCardinality = maxCardinality;
        }

        @Override
        public MeterFilterReply accept(Meter.Id id) {
            String metricName = id.getName();
            List<Tag> tags = id.getTags();
            while (true) {
                MetricSeriesTracker tracker = trackers.computeIfAbsent(metricName, ignored -> new MetricSeriesTracker());
                Boolean accepted = tracker.tryRegister(metricName, tags, maxCardinality);
                if (accepted != null) {
                    return accepted ? MeterFilterReply.NEUTRAL : MeterFilterReply.DENY;
                }
            }
        }

        private void onMeterRemoved(Meter.Id id) {
            if (id == null) {
                return;
            }
            String metricName = id.getName();
            MetricSeriesTracker tracker = trackers.get(metricName);
            if (tracker != null) {
                tracker.removeAndRetireIfEmpty(id.getTags(), () -> trackers.remove(metricName, tracker));
            }
        }
    }

    private static final class MetricSeriesTracker {

        private final Set<List<Tag>> seenSeries = new HashSet<>();
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicBoolean limitWarned = new AtomicBoolean(false);
        private boolean retired = false;

        private Boolean tryRegister(String metricName, List<Tag> tags, int maxCardinality) {
            lock.lock();
            try {
                if (retired) {
                    return null;
                }
                if (seenSeries.contains(tags)) {
                    return Boolean.TRUE;
                }
                if (seenSeries.size() >= maxCardinality) {
                    if (limitWarned.compareAndSet(false, true)) {
                        log.warn("指标 [{}] 标签基数达到熔断上限 [{}]，后续新增异常维度序列将被拒绝注册以保护 Prometheus TSDB",
                                metricName, maxCardinality);
                    }
                    return Boolean.FALSE;
                }
                seenSeries.add(tags);
                return Boolean.TRUE;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        private void removeAndRetireIfEmpty(List<Tag> tags, Runnable retireCallback) {
            lock.lock();
            try {
                seenSeries.remove(tags);
                if (seenSeries.isEmpty() && !retired) {
                    retired = true;
                    retireCallback.run();
                }
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }
}
