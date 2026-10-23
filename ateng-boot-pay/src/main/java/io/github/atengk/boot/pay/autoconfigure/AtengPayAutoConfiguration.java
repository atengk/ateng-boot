package io.github.atengk.boot.pay.autoconfigure;

import io.github.atengk.boot.pay.config.properties.PayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 支付模块顶层统一自动装配类
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(PayProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.pay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengPayAutoConfiguration {

    public AtengPayAutoConfiguration() {
        log.info("Initialized AtengPayAutoConfiguration for enterprise multi-channel payment foundation.");
    }
}
