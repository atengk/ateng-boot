package io.github.atengk.boot.sample.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 企业协同与多端 Webhook 机器人通知基础设施场景自验应用启动入口
 *
 * @author Ateng
 * @since 2026-10-23
 */
@SpringBootApplication
public class SampleNotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleNotificationApplication.class, args);
    }

    /**
     * 场景样例纯内存轻量事务管理器（零 DB 依赖下支持本地事务感知发信 sendAfterCommit 自验）
     */
    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
