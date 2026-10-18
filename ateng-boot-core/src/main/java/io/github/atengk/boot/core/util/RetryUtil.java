package io.github.atengk.boot.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 纯函数式指数退避重试执行器
 * 100% 原生 JDK 实现，支持动态异常谓词断言、指数退避因子、Equal Jitter 随机抖动打散、onRetry 回调监听与 Fallback 降级兜底，
 * 无需引入 Spring Retry 或 Resilience4j 等重型 AOP 框架
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class RetryUtil {

    private static final Logger log = LoggerFactory.getLogger(RetryUtil.class);

    private RetryUtil() {
    }

    /**
     * 重试监听器函数式接口
     */
    @FunctionalInterface
    public interface RetryListener {
        /**
         * 发生可重试异常并在休眠等待前触发
         *
         * @param attempt 当前已失败的尝试序号（从 1 开始，1 表示第 1 次执行失败，准备第 1 次重试）
         * @param error   当前捕获的异常对象
         */
        void onRetry(int attempt, Throwable error);
    }

    /**
     * 重试行为配置模型
     *
     * @param maxAttempts    最大尝试总次数（包含首次执行，如 3 表示首次 + 2 次重试）
     * @param delay          初始重试等待时长
     * @param multiplier     退避倍数因子（1.0 为固定间隔，2.0 为指数退避）
     * @param maxDelay       单次重试等待时长上限（防止指数爆炸）
     * @param retryPredicate 判定发生异常时是否需要重试的谓词（返回 true 表示重试，false 表示立即终止重试并抛出或降级）
     * @param jitter         是否启用 Equal Jitter 平滑随机抖动（打散重试洪峰，公式：delay/2 + random(0, delay/2)）
     * @param onRetry        重试监听器（在休眠等待前被调度，可用于度量打点与告警）
     */
    public record RetryConfig(
            int maxAttempts,
            Duration delay,
            double multiplier,
            Duration maxDelay,
            Predicate<Throwable> retryPredicate,
            boolean jitter,
            RetryListener onRetry
    ) {
        public RetryConfig {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("maxAttempts 必须大于或等于 1");
            }
            Objects.requireNonNull(delay, "delay 不能为空");
            if (delay.isNegative()) {
                throw new IllegalArgumentException("delay 不能为负数");
            }
            if (multiplier < 1.0) {
                throw new IllegalArgumentException("multiplier 不能小于 1.0");
            }
            Objects.requireNonNull(maxDelay, "maxDelay 不能为空");
            if (maxDelay.isNegative()) {
                throw new IllegalArgumentException("maxDelay 不能为负数");
            }
            Objects.requireNonNull(retryPredicate, "retryPredicate 不能为空");
        }

        /**
         * 兼容既有 5 参数构造器，保持 100% 向后二进制与源码兼容（默认禁用 jitter，onRetry 为 null）
         */
        public RetryConfig(int maxAttempts, Duration delay, double multiplier, Duration maxDelay, Predicate<Throwable> retryPredicate) {
            this(maxAttempts, delay, multiplier, maxDelay, retryPredicate, false, null);
        }

        /**
         * 默认重试配置：最多重试 3 次，初始间隔 100ms，指数退避倍数 2.0，最大上限 5000ms，无抖动，所有异常均重试
         */
        public static final RetryConfig DEFAULT = builder().build();

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private int maxAttempts = 3;
            private Duration delay = Duration.ofMillis(100);
            private double multiplier = 2.0;
            private Duration maxDelay = Duration.ofMillis(5000);
            private Predicate<Throwable> retryPredicate = ex -> true;
            private boolean jitter = false;
            private RetryListener onRetry = null;

            public Builder maxAttempts(int maxAttempts) {
                if (maxAttempts < 1) {
                    throw new IllegalArgumentException("maxAttempts 必须大于或等于 1");
                }
                this.maxAttempts = maxAttempts;
                return this;
            }

            public Builder delay(Duration delay) {
                Objects.requireNonNull(delay, "delay 不能为空");
                this.delay = delay;
                return this;
            }

            public Builder multiplier(double multiplier) {
                if (multiplier < 1.0) {
                    throw new IllegalArgumentException("multiplier 不能小于 1.0");
                }
                this.multiplier = multiplier;
                return this;
            }

            public Builder maxDelay(Duration maxDelay) {
                Objects.requireNonNull(maxDelay, "maxDelay 不能为空");
                this.maxDelay = maxDelay;
                return this;
            }

            public Builder retryPredicate(Predicate<Throwable> retryPredicate) {
                Objects.requireNonNull(retryPredicate, "retryPredicate 不能为空");
                this.retryPredicate = retryPredicate;
                return this;
            }

            public Builder jitter(boolean jitter) {
                this.jitter = jitter;
                return this;
            }

            public Builder onRetry(RetryListener onRetry) {
                this.onRetry = onRetry;
                return this;
            }

            public RetryConfig build() {
                return new RetryConfig(maxAttempts, delay, multiplier, maxDelay, retryPredicate, jitter, onRetry);
            }
        }
    }

    /**
     * 在默认配置下执行带返回值的任务
     *
     * @param supplier 业务执行体
     * @param <T>      返回值泛型
     * @return 执行结果
     */
    public static <T> T execute(Supplier<T> supplier) {
        return execute(supplier, RetryConfig.DEFAULT, null);
    }

    /**
     * 在自定义配置下执行带返回值的重试任务
     *
     * @param supplier 业务执行体
     * @param config   重试配置
     * @param <T>      返回值泛型
     * @return 执行结果
     */
    public static <T> T execute(Supplier<T> supplier, RetryConfig config) {
        return execute(supplier, config, null);
    }

    /**
     * 在默认配置下执行带返回值的重试任务并提供 Fallback 降级回调
     *
     * @param supplier 业务执行体
     * @param fallback 重试耗尽或异常终止时的降级回调（接收最终抛出的异常并返回兜底值）
     * @param <T>      返回值泛型
     * @return 执行结果或降级结果
     */
    public static <T> T execute(Supplier<T> supplier, Function<Throwable, T> fallback) {
        return execute(supplier, RetryConfig.DEFAULT, fallback);
    }

    /**
     * 在自定义配置下执行带返回值的重试任务并提供 Fallback 降级回调
     *
     * @param supplier 业务执行体
     * @param config   重试配置
     * @param fallback 重试耗尽或异常终止时的降级回调（若为 null 则保持既有行为抛出根异常）
     * @param <T>      返回值泛型
     * @return 执行结果或降级结果
     */
    public static <T> T execute(Supplier<T> supplier, RetryConfig config, Function<Throwable, T> fallback) {
        Objects.requireNonNull(supplier, "supplier 不能为空");
        Objects.requireNonNull(config, "config 不能为空");

        int maxAttempts = config.maxAttempts();
        long currentDelayMs = config.delay().toMillis();
        long maxDelayMs = config.maxDelay().toMillis();
        double multiplier = config.multiplier();
        Predicate<Throwable> predicate = config.retryPredicate();
        boolean jitter = config.jitter();
        RetryListener onRetry = config.onRetry();

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return supplier.get();
            } catch (Throwable ex) {
                if (attempt >= maxAttempts || !predicate.test(ex)) {
                    if (attempt >= maxAttempts) {
                        log.warn("重试执行已耗尽全部 {} 次尝试", maxAttempts);
                    } else {
                        log.warn("捕获到不满足重试谓词条件的异常，终止重试: {}", ex.getMessage());
                    }
                    if (fallback != null) {
                        log.info("触发 Fallback 降级回调处理");
                        return fallback.apply(ex);
                    }
                    sneakyThrow(ex);
                }

                // 调度 onRetry 监听器（异常受控隔离）
                if (onRetry != null) {
                    try {
                        onRetry.onRetry(attempt, ex);
                    } catch (Throwable t) {
                        log.warn("重试监听器 onRetry 执行异常，已受控隔离: {}", t.getMessage(), t);
                    }
                }

                // 计算本次休眠实际时长（Equal Jitter: delay/2 + random(0, delay/2)）
                long actualSleepMs = currentDelayMs;
                if (jitter) {
                    long half = currentDelayMs / 2;
                    if (half > 0) {
                        long jitterOffset = ThreadLocalRandom.current().nextLong(half + 1);
                        actualSleepMs = half + jitterOffset;
                    }
                }

                log.warn("第 {} 次执行失败，捕获异常: {}，将在 {}ms 后尝试第 {} 次重试...",
                        attempt, ex.getMessage(), actualSleepMs, attempt + 1);

                try {
                    Thread.sleep(actualSleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("重试等待被中断", ie);
                }

                currentDelayMs = Math.min((long) (currentDelayMs * multiplier), maxDelayMs);
            }
        }
    }

    /**
     * 在默认配置下执行无返回值的任务
     *
     * @param runnable 业务执行体
     */
    public static void execute(Runnable runnable) {
        execute(runnable, RetryConfig.DEFAULT);
    }

    /**
     * 在自定义配置下执行无返回值的重试任务
     *
     * @param runnable 业务执行体
     * @param config   重试配置
     */
    public static void execute(Runnable runnable, RetryConfig config) {
        Objects.requireNonNull(runnable, "runnable 不能为空");
        execute(() -> {
            runnable.run();
            return null;
        }, config, null);
    }

    /**
     * 在默认配置下执行无返回值的重试任务并提供 Fallback 降级回调
     *
     * @param runnable 业务执行体
     * @param fallback 重试耗尽或异常终止时的降级回调
     */
    public static void executeAction(Runnable runnable, Consumer<Throwable> fallback) {
        executeAction(runnable, RetryConfig.DEFAULT, fallback);
    }

    /**
     * 在自定义配置下执行无返回值的重试任务并提供 Fallback 降级回调
     *
     * @param runnable 业务执行体
     * @param config   重试配置
     * @param fallback 重试耗尽或异常终止时的降级回调（若为 null 则抛出根异常）
     */
    public static void executeAction(Runnable runnable, RetryConfig config, Consumer<Throwable> fallback) {
        Objects.requireNonNull(runnable, "runnable 不能为空");
        execute(() -> {
            runnable.run();
            return null;
        }, config, ex -> {
            if (fallback != null) {
                fallback.accept(ex);
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}
