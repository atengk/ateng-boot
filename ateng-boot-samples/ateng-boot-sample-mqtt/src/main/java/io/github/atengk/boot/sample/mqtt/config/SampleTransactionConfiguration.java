package io.github.atengk.boot.sample.mqtt.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 本地无外部数据库场景下的轻量级事务管理器配置
 * 为演示 sendAfterCommit 事务感知投递与回滚安全丢弃提供纯原生支撑
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement
public class SampleTransactionConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager sampleTransactionManager() {
        return new SimpleLocalTransactionManager();
    }

    /**
     * 纯内存轻量事务管理器实现，支持 TransactionSynchronizationManager 完整生命周期
     */
    static class SimpleLocalTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 纯内存事务启动，生命周期由 AbstractPlatformTransactionManager 接管
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 纯内存事务提交
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 纯内存事务回滚
        }
    }
}
