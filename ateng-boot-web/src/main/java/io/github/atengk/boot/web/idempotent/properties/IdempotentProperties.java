package io.github.atengk.boot.web.idempotent.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 接口防重幂等运行时配置属性
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.web.idempotent")
public class IdempotentProperties {

    /**
     * 是否启用接口防重幂等切面与存储引擎
     */
    private boolean enabled = true;

    /**
     * 存储全局 Key 前缀
     */
    private String keyPrefix = "idempotent:";

    /**
     * 单机内存存储配置
     */
    private InMemory inMemory = new InMemory();

    /**
     * 单机内存存储配置参数
     */
    @Data
    public static class InMemory {

        /**
         * 内存最大元素容量软上限（超过后触发主动过期淘汰与溢出剔除，防止 OOM）
         */
        private int maxCapacity = 10000;

        /**
         * 后台定期过期淘汰扫描周期
         */
        private Duration cleanInterval = Duration.ofSeconds(60);
    }
}
