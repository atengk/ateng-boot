package io.github.atengk.boot.milvus.customizer;

import io.github.atengk.boot.milvus.properties.AtengMilvusProperties;
import io.milvus.v2.client.ConnectConfig;

/**
 * Milvus 客户端连接配置自定义扩展点 SPI
 * 允许业务方通过注册 Bean 无损定制底层 {@link ConnectConfig}（如 RPC 超时、SSL 上下文、KeepAlive 参数等），无需覆盖完整自动装配
 *
 * @author Ateng
 * @since 2026-11-20
 */
@FunctionalInterface
public interface MilvusClientCustomizer {

    /**
     * 定制底层 Milvus 客户端连接配置构建器
     *
     * @param builder    已填充默认属性的连接配置构建器
     * @param properties 当前 Milvus 配置属性
     */
    void customize(ConnectConfig.ConnectConfigBuilder<?, ?> builder, AtengMilvusProperties properties);
}
