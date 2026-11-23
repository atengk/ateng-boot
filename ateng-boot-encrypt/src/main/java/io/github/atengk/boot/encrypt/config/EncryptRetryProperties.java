package io.github.atengk.boot.encrypt.config;

import io.github.atengk.boot.core.util.RetryUtil;
import io.github.atengk.boot.encrypt.decryptor.RetryingConfigDecryptor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * 外部 KMS / HSM 解密弹性退避重试属性配置类
 * 依托 ateng-boot-core 原生 RetryUtil 纯函数式退避机制，提供强类型时间量纲与生产级抖动防风暴配置
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Data
public class EncryptRetryProperties {

    private static final Logger log = LoggerFactory.getLogger(EncryptRetryProperties.class);

    /**
     * 初始重试延迟（支持 300ms, 1s 等时间量纲格式），默认 300ms
     */
    private Duration delay = Duration.ofMillis(300);

    /**
     * 最大延迟上限，默认 5s
     */
    private Duration maxDelay = Duration.ofSeconds(5);

    /**
     * 最大尝试次数（含首次执行），默认 3 次
     */
    private int maxAttempts = 3;

    /**
     * 指数退避乘数，默认 2.0
     */
    private double multiplier = 2.0;

    /**
     * 是否启用随机抖动以打散重试洪峰，默认 true
     */
    private boolean jitter = true;

    /**
     * 转换为 Core 底座标准的 RetryConfig
     *
     * @param predicate 重试断言谓词，为 null 时采用默认密码学排除谓词
     * @param onRetry   重试触发回调，为 null 时采用默认 warn 日志打印
     * @return RetryConfig 实例
     */
    public RetryUtil.RetryConfig toRetryConfig(Predicate<Throwable> predicate, BiConsumer<Integer, Throwable> onRetry) {
        RetryUtil.RetryListener retryListener = onRetry != null
                ? onRetry::accept
                : (attempt, error) -> log.warn("[ateng-boot-encrypt] 外部解密器执行抖动异常，正在进行第 {} 次指数退避重试: {}", attempt, error.getMessage());

        return RetryUtil.RetryConfig.builder()
                .maxAttempts(Math.max(1, this.maxAttempts))
                .delay(this.delay != null && !this.delay.isNegative() ? this.delay : Duration.ofMillis(300))
                .multiplier(this.multiplier >= 1.0 ? this.multiplier : 2.0)
                .maxDelay(this.maxDelay != null && !this.maxDelay.isNegative() ? this.maxDelay : Duration.ofSeconds(5))
                .jitter(this.jitter)
                .retryPredicate(predicate != null ? predicate : RetryingConfigDecryptor.DEFAULT_RETRYABLE_PREDICATE)
                .onRetry(retryListener)
                .build();
    }

    /**
     * 转换为使用默认密码学谓词与日志回调的 RetryConfig
     *
     * @return RetryConfig 实例
     */
    public RetryUtil.RetryConfig toRetryConfig() {
        return toRetryConfig(null, null);
    }
}
