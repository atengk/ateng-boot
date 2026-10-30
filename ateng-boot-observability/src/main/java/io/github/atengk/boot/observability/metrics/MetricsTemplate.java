package io.github.atengk.boot.observability.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

/**
 * 生产级高可用业务度量编程式操作门面
 * <p>
 * 基于 Micrometer {@link MeterRegistry} 深度封装 Counter、Timer、Gauge 与 DistributionSummary 核心度量原语。
 * 提供多维打标、函数式环绕耗时、集合容量瞬态测量与弱装配防假死保底机制，
 * 在底层指标系统未装配或发生异常时平滑降级，保障业务主链路绝对零阻断。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Slf4j
public class MetricsTemplate {

    @Getter
    private final MeterRegistry registry;

    public MetricsTemplate(MeterRegistry registry) {
        this.registry = registry;
    }

    // ==========================================
    // 1. Counter 计数器操作
    // ==========================================

    /**
     * 递增指定指标计数器（步进 1.0）
     *
     * @param name 指标名称
     * @param tags 标签键值对（按 key1, val1, key2, val2 成对传递）
     */
    public void increment(String name, String... tags) {
        increment(name, 1.0, toTags(tags));
    }

    /**
     * 递增指定指标计数器（步进 1.0）
     *
     * @param name 指标名称
     * @param tags 标签集合
     */
    public void increment(String name, Iterable<Tag> tags) {
        increment(name, 1.0, tags);
    }

    /**
     * 递增指定指标计数器（自定义步进量）
     *
     * @param name   指标名称
     * @param amount 增量数值（非负数）
     * @param tags   标签键值对
     */
    public void increment(String name, double amount, String... tags) {
        increment(name, amount, toTags(tags));
    }

    /**
     * 递增指定指标计数器（自定义步进量）
     *
     * @param name   指标名称
     * @param amount 增量数值（非负数）
     * @param tags   标签集合
     */
    public void increment(String name, double amount, Iterable<Tag> tags) {
        if (amount < 0) {
            log.warn("Counter 指标 [{}] 增量不能为负数: {}", name, amount);
            return;
        }
        Counter counter = counter(name, tags);
        if (counter != null) {
            counter.increment(amount);
        }
    }

    /**
     * 获取或注册 Counter 实例
     *
     * @param name 指标名称
     * @param tags 标签键值对
     * @return Counter 实例，若注册表为空则返回 null
     */
    public Counter counter(String name, String... tags) {
        return counter(name, toTags(tags));
    }

    /**
     * 获取或注册 Counter 实例
     *
     * @param name 指标名称
     * @param tags 标签集合
     * @return Counter 实例，若注册表为空则返回 null
     */
    public Counter counter(String name, Iterable<Tag> tags) {
        if (this.registry == null || isBlank(name)) {
            return null;
        }
        return Counter.builder(name)
                .tags(tags != null ? tags : Tags.empty())
                .register(this.registry);
    }

    /**
     * 查询指定 Counter 指标当前累计值（保底返回 0.0）
     *
     * @param name 指标名称
     * @param tags 标签键值对
     * @return 当前计数值
     */
    public double count(String name, String... tags) {
        return count(name, toTags(tags));
    }

    /**
     * 查询指定 Counter 指标当前累计值（保底返回 0.0）
     *
     * @param name 指标名称
     * @param tags 标签集合
     * @return 当前计数值
     */
    public double count(String name, Iterable<Tag> tags) {
        Counter counter = counter(name, tags);
        return counter != null ? counter.count() : 0.0;
    }

    // ==========================================
    // 2. Timer 计时器操作
    // ==========================================

    /**
     * 直接记录指定耗时时长
     *
     * @param name     指标名称
     * @param duration 耗时时长
     * @param tags     标签键值对
     */
    public void record(String name, Duration duration, String... tags) {
        record(name, duration, toTags(tags));
    }

    /**
     * 直接记录指定耗时时长
     *
     * @param name     指标名称
     * @param duration 耗时时长
     * @param tags     标签集合
     */
    public void record(String name, Duration duration, Iterable<Tag> tags) {
        if (duration == null || duration.isNegative()) {
            return;
        }
        Timer timer = timer(name, tags);
        if (timer != null) {
            timer.record(duration);
        }
    }

    /**
     * 函数式环绕执行无返回值任务并记录耗时
     *
     * @param name     指标名称
     * @param runnable 待执行任务
     * @param tags     标签键值对
     */
    public void record(String name, Runnable runnable, String... tags) {
        record(name, runnable, toTags(tags));
    }

    /**
     * 函数式环绕执行无返回值任务并记录耗时
     *
     * @param name     指标名称
     * @param runnable 待执行任务
     * @param tags     标签集合
     */
    public void record(String name, Runnable runnable, Iterable<Tag> tags) {
        if (runnable == null) {
            return;
        }
        if (this.registry == null || isBlank(name)) {
            runnable.run();
            return;
        }
        Timer.Sample sample = Timer.start(this.registry);
        try {
            runnable.run();
        } finally {
            sample.stop(timer(name, tags));
        }
    }

    /**
     * 函数式环绕执行有返回值任务并记录耗时
     *
     * @param name     指标名称
     * @param supplier 结果提供者
     * @param tags     标签键值对
     * @param <T>      返回类型
     * @return 执行结果
     */
    public <T> T record(String name, Supplier<T> supplier, String... tags) {
        return record(name, supplier, toTags(tags));
    }

    /**
     * 函数式环绕执行有返回值任务并记录耗时
     *
     * @param name     指标名称
     * @param supplier 结果提供者
     * @param tags     标签集合
     * @param <T>      返回类型
     * @return 执行结果
     */
    public <T> T record(String name, Supplier<T> supplier, Iterable<Tag> tags) {
        if (supplier == null) {
            return null;
        }
        if (this.registry == null || isBlank(name)) {
            return supplier.get();
        }
        Timer.Sample sample = Timer.start(this.registry);
        try {
            return supplier.get();
        } finally {
            sample.stop(timer(name, tags));
        }
    }

    /**
     * 函数式环绕执行可能抛出受检异常的任务并记录耗时
     *
     * @param name     指标名称
     * @param callable 待执行 Callable
     * @param tags     标签键值对
     * @param <T>      返回类型
     * @param <E>      异常类型
     * @return 执行结果
     * @throws E 任务抛出的原始异常
     */
    public <T, E extends Throwable> T recordCallable(String name, Callable<T> callable, String... tags) throws E {
        return recordCallable(name, callable, toTags(tags));
    }

    /**
     * 函数式环绕执行可能抛出受检异常的任务并记录耗时
     *
     * @param name     指标名称
     * @param callable 待执行 Callable
     * @param tags     标签集合
     * @param <T>      返回类型
     * @param <E>      异常类型
     * @return 执行结果
     * @throws E 任务抛出的原始异常
     */
    public <T, E extends Throwable> T recordCallable(String name, Callable<T> callable, Iterable<Tag> tags) throws E {
        if (callable == null) {
            return null;
        }
        if (this.registry == null || isBlank(name)) {
            try {
                return callable.call();
            } catch (Exception e) {
                @SuppressWarnings("unchecked")
                E typedException = (E) e;
                throw typedException;
            }
        }
        Timer.Sample sample = Timer.start(this.registry);
        try {
            return callable.call();
        } catch (Exception e) {
            @SuppressWarnings("unchecked")
            E typedException = (E) e;
            throw typedException;
        } finally {
            sample.stop(timer(name, tags));
        }
    }

    /**
     * 获取或注册 Timer 实例
     *
     * @param name 指标名称
     * @param tags 标签键值对
     * @return Timer 实例，若注册表为空则返回 null
     */
    public Timer timer(String name, String... tags) {
        return timer(name, toTags(tags));
    }

    /**
     * 获取或注册 Timer 实例
     *
     * @param name 指标名称
     * @param tags 标签集合
     * @return Timer 实例，若注册表为空则返回 null
     */
    public Timer timer(String name, Iterable<Tag> tags) {
        if (this.registry == null || isBlank(name)) {
            return null;
        }
        return Timer.builder(name)
                .tags(tags != null ? tags : Tags.empty())
                .register(this.registry);
    }

    /**
     * 启动一个独立的计时采样器（用于跨代码块手动测量耗时）
     *
     * @return Timer.Sample 实例，若注册表为空则返回 null
     */
    public Timer.Sample startSample() {
        return this.registry != null ? Timer.start(this.registry) : null;
    }

    /**
     * 停止计时采样器并记录到指定 Timer
     *
     * @param sample 采样器实例
     * @param name   指标名称
     * @param tags   标签键值对
     */
    public void stopSample(Timer.Sample sample, String name, String... tags) {
        stopSample(sample, name, toTags(tags));
    }

    /**
     * 停止计时采样器并记录到指定 Timer
     *
     * @param sample 采样器实例
     * @param name   指标名称
     * @param tags   标签集合
     */
    public void stopSample(Timer.Sample sample, String name, Iterable<Tag> tags) {
        if (sample != null) {
            Timer timer = timer(name, tags);
            if (timer != null) {
                sample.stop(timer);
            }
        }
    }

    // ==========================================
    // 3. Gauge 瞬时量测量操作
    // ==========================================

    /**
     * 注册追踪基础数值对象的 Gauge（基于 Number）
     *
     * @param name   指标名称
     * @param number 数值对象（如 AtomicInteger、Double 等）
     * @param tags   标签键值对
     * @param <T>    数值类型
     * @return 注册的数值对象
     */
    public <T extends Number> T gauge(String name, T number, String... tags) {
        return gauge(name, number, toTags(tags));
    }

    /**
     * 注册追踪基础数值对象的 Gauge（基于 Number）
     *
     * @param name   指标名称
     * @param number 数值对象
     * @param tags   标签集合
     * @param <T>    数值类型
     * @return 注册的数值对象
     */
    public <T extends Number> T gauge(String name, T number, Iterable<Tag> tags) {
        if (number == null || this.registry == null || isBlank(name)) {
            return number;
        }
        this.registry.gauge(name, tags != null ? tags : Tags.empty(), number, Number::doubleValue);
        return number;
    }

    /**
     * 注册追踪任意状态对象的函数式 Gauge
     *
     * @param name          指标名称
     * @param stateObject   被观察的状态对象
     * @param valueFunction 计算当前数值的函数
     * @param tags          标签键值对
     * @param <T>           状态对象类型
     * @return 状态对象实例
     */
    public <T> T gauge(String name, T stateObject, ToDoubleFunction<T> valueFunction, String... tags) {
        return gauge(name, stateObject, valueFunction, toTags(tags));
    }

    /**
     * 注册追踪任意状态对象的函数式 Gauge
     *
     * @param name          指标名称
     * @param stateObject   被观察的状态对象
     * @param valueFunction 计算当前数值的函数
     * @param tags          标签集合
     * @param <T>           状态对象类型
     * @return 状态对象实例
     */
    public <T> T gauge(String name, T stateObject, ToDoubleFunction<T> valueFunction, Iterable<Tag> tags) {
        if (stateObject == null || valueFunction == null || this.registry == null || isBlank(name)) {
            return stateObject;
        }
        this.registry.gauge(name, tags != null ? tags : Tags.empty(), stateObject, valueFunction);
        return stateObject;
    }

    /**
     * 注册追踪集合元素容量的 Gauge
     *
     * @param name       指标名称
     * @param collection 集合对象
     * @param tags       标签键值对
     * @param <T>        集合类型
     * @return 集合实例
     */
    public <T extends Collection<?>> T gaugeCollectionSize(String name, T collection, String... tags) {
        return gauge(name, collection, Collection::size, tags);
    }

    /**
     * 注册追踪 Map 键值对容量的 Gauge
     *
     * @param name 指标名称
     * @param map  Map 对象
     * @param tags 标签键值对
     * @param <T>  Map 类型
     * @return Map 实例
     */
    public <T extends Map<?, ?>> T gaugeMapSize(String name, T map, String... tags) {
        return gauge(name, map, Map::size, tags);
    }

    // ==========================================
    // 4. DistributionSummary 分布摘要操作
    // ==========================================

    /**
     * 记录分布摘要采样（如数据载荷大小、响应行数等）
     *
     * @param name   指标名称
     * @param amount 采样数值
     * @param tags   标签键值对
     */
    public void summary(String name, double amount, String... tags) {
        summary(name, amount, toTags(tags));
    }

    /**
     * 记录分布摘要采样
     *
     * @param name   指标名称
     * @param amount 采样数值
     * @param tags   标签集合
     */
    public void summary(String name, double amount, Iterable<Tag> tags) {
        if (amount < 0) {
            log.warn("DistributionSummary 指标 [{}] 数值不能为负数: {}", name, amount);
            return;
        }
        DistributionSummary summary = distributionSummary(name, tags);
        if (summary != null) {
            summary.record(amount);
        }
    }

    /**
     * 获取或注册 DistributionSummary 实例
     *
     * @param name 指标名称
     * @param tags 标签键值对
     * @return DistributionSummary 实例，若注册表为空则返回 null
     */
    public DistributionSummary distributionSummary(String name, String... tags) {
        return distributionSummary(name, toTags(tags));
    }

    /**
     * 获取或注册 DistributionSummary 实例
     *
     * @param name 指标名称
     * @param tags 标签集合
     * @return DistributionSummary 实例，若注册表为空则返回 null
     */
    public DistributionSummary distributionSummary(String name, Iterable<Tag> tags) {
        if (this.registry == null || isBlank(name)) {
            return null;
        }
        return DistributionSummary.builder(name)
                .tags(tags != null ? tags : Tags.empty())
                .register(this.registry);
    }

    // ==========================================
    // 5. 辅助与标签清洗方法
    // ==========================================

    /**
     * 将可变键值对转换为 Micrometer Tags，具备奇数标签容错与防 NPE 防卫
     *
     * @param keyValues 键值对参数列表（key1, val1, key2, val2...）
     * @return Tags 实例
     */
    public static Tags toTags(String... keyValues) {
        if (keyValues == null || keyValues.length == 0) {
            return Tags.empty();
        }
        List<Tag> list = new ArrayList<>(keyValues.length / 2);
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = keyValues[i];
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = (i + 1 < keyValues.length && keyValues[i + 1] != null) ? keyValues[i + 1] : "UNKNOWN";
            list.add(Tag.of(key, value));
        }
        return Tags.of(list);
    }

    private static boolean isBlank(String str) {
        return str == null || str.isBlank();
    }
}
