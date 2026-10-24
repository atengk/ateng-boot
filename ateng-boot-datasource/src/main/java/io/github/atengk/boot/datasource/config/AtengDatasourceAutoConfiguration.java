package io.github.atengk.boot.datasource.config;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ateng-boot-datasource 核心自动装配入口
 *
 * @author Ateng
 * @since 2026-10-24
 */
@AutoConfiguration
@ConditionalOnClass(DynamicRoutingDataSource.class)
@EnableConfigurationProperties(DatasourceProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.datasource", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengDatasourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AtengDatasourceAutoConfiguration.class);

    public AtengDatasourceAutoConfiguration() {
        log.info("初始化 ateng-boot-datasource 动态多数据源与物理隔离底座");
    }
}
