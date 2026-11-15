package io.github.atengk.boot.kafka.spi;

import org.apache.kafka.clients.admin.AdminClient;

import java.util.Map;

/**
 * Kafka {@link AdminClient} 创建工厂 SPI
 *
 * <p>默认场景由底座自动委托 {@link AdminClient#create(Map)} 构建原生客户端；
 * 在离线仿真、自定义安全认证或专用测试环境中，可通过向 Spring 容器注入该 Bean 接管底层管理客户端创建。</p>
 *
 * @author Ateng
 * @since 2026-11-15
 */
@FunctionalInterface
public interface KafkaAdminClientFactory {

    /**
     * 基于连接属性创建 Kafka 管理客户端
     *
     * @param properties Kafka 客户端连接属性映射
     * @return {@link AdminClient} 实例
     */
    AdminClient create(Map<String, Object> properties);
}
