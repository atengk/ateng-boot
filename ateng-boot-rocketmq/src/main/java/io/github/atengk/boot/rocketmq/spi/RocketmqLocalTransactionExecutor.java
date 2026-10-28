package io.github.atengk.boot.rocketmq.spi;

import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;

/**
 * RocketMQ 本地事务执行器函数式接口
 *
 * <p>在 2PC 分布式事务半消息投递成功后被回调执行，根据本地业务数据库事务或逻辑执行结果返回事务决断（COMMIT / ROLLBACK / UNKNOWN）。
 * 若执行过程抛出异常，门面将自动触发事务回滚并向外传播异常。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface RocketmqLocalTransactionExecutor {

    /**
     * 执行本地业务事务
     *
     * @param sendReceipt 事务半消息投递成功的发送回执
     * @return 事务决断状态 (COMMIT, ROLLBACK, UNKNOWN)
     * @throws Throwable 本地事务执行过程中产生的任何异常
     */
    TransactionResolution execute(SendReceipt sendReceipt) throws Throwable;
}
