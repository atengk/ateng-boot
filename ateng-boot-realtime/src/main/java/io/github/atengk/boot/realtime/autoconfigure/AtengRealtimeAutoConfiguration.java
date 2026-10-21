package io.github.atengk.boot.realtime.autoconfigure;

import io.github.atengk.boot.realtime.properties.RealtimeProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 实时通信底座主自动装配类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@AutoConfiguration
@EnableConfigurationProperties(RealtimeProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.realtime", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengRealtimeAutoConfiguration {

}
