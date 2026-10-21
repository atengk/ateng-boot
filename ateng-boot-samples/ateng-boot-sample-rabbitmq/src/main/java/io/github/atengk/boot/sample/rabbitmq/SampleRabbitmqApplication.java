package io.github.atengk.boot.sample.rabbitmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * RabbitMQ 场景自验样例工程主启动类
 *
 * @author Ateng
 * @since 2026-10-21
 */
@EnableTransactionManagement
@SpringBootApplication
public class SampleRabbitmqApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleRabbitmqApplication.class, args);
    }

    /**
     * 提供纯内存轻量事务管理器，用于无 DB 环境下自洽自验 sendAfterCommit 机制
     */
    @Bean
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
