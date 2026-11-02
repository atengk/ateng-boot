package io.github.atengk.boot.sample.sms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 短信基础设施场景自验演示启动类
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootApplication
public class SampleSmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(SampleSmsApplication.class, args);
    }

    /**
     * 注册内存轻量级事务管理器，用于无物理数据库环境下自洽演练本地物理事务与提交感知发信
     *
     * @return PlatformTransactionManager
     */
    @org.springframework.context.annotation.Bean
    public org.springframework.transaction.PlatformTransactionManager transactionManager() {
        return new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
            }
        };
    }
}
