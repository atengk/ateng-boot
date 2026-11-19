package io.github.atengk.boot.datasource.guard;

import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.util.StringUtils;

/**
 * Spring 事务启动阶段路由感知与数据源绑定监听器
 * <p>
 * 在 {@code beforeBegin} 至 {@code afterBegin} 窗口内透传真实事务的活跃态与只读语义，
 * 并在 {@code afterBegin} 完成事务同步初始化后，补齐事务初始物理数据源标识绑定，
 * 彻底消除 {@code DataSourceTransactionManager.doBegin()} 先取连接后激活同步带来的路由盲区。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public class AtengTransactionExecutionListener implements TransactionExecutionListener {

    @Override
    public void beforeBegin(TransactionExecution transaction) {
        if (transaction != null && transaction.hasTransaction()) {
            TransactionRoutingContext.pushStartingTransaction(true, transaction.isReadOnly());
        }
    }

    @Override
    public void afterBegin(TransactionExecution transaction, Throwable beginFailure) {
        if (transaction == null || !transaction.hasTransaction()) {
            return;
        }
        String resolvedDsKey = TransactionRoutingContext.popStartingTransaction();
        if (beginFailure == null && StringUtils.hasText(resolvedDsKey)) {
            DynamicDatasourceGuard.bindTransactionDataSourceKeyIfAbsent(resolvedDsKey);
        }
    }
}
