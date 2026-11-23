package io.github.atengk.boot.sample.storage.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 样例工程轻量内存事务管理器装配
 * 用于在不引入外部数据库驱动的前提下，完整演示 Spring TransactionSynchronizationManager
 * 驱动的事务回滚防孤儿补偿与提交后延迟删除联动闭环
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Configuration(proxyBeanMethods = false)
public class SampleStorageTransactionConfig {

    /**
     * 装配轻量无数据源事务管理器（当容器不存在真实持久层事务管理器时生效）
     *
     * @return PlatformTransactionManager 实例
     */
    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager samplePlatformTransactionManager() {
        return new InMemoryDemoTransactionManager();
    }

    /**
     * 装配编程式事务模板
     *
     * @param transactionManager 事务管理器
     * @return TransactionTemplate 实例
     */
    @Bean
    @ConditionalOnMissingBean(TransactionTemplate.class)
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 仅激活 Spring 事务同步生命周期的轻量事务管理器实现
     */
    private static final class InMemoryDemoTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        @NonNull
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(@NonNull Object transaction, @NonNull TransactionDefinition definition) {
            // 无需物理数据库连接，交由 AbstractPlatformTransactionManager 激活 TransactionSynchronizationManager
        }

        @Override
        protected void doCommit(@NonNull DefaultTransactionStatus status) {
            // 无需物理提交动作，基类自动回调 afterCommit / afterCompletion
        }

        @Override
        protected void doRollback(@NonNull DefaultTransactionStatus status) {
            // 无需物理回滚动作，基类自动回调 afterCompletion(STATUS_ROLLED_BACK)
        }
    }
}
