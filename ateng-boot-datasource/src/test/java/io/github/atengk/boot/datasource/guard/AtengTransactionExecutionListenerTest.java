package io.github.atengk.boot.datasource.guard;

import io.github.atengk.boot.datasource.config.AtengDatasourceAutoConfiguration;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spring 事务启动阶段路由感知监听器对抗性单测
 *
 * @author Ateng
 * @since 2026-11-19
 */
class AtengTransactionExecutionListenerTest {

    @AfterEach
    void tearDown() {
        TransactionRoutingContext.clear();
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("真实 DataSourceTransactionManager 启动写/只读事务时，beforeBegin/afterBegin 精准透传只读语义并绑定数据源")
    void testRealDataSourceTransactionManagerBindingAndReadOnlyRouting() throws Exception {
        DatasourceProperties properties = new DatasourceProperties();
        properties.getReadWriteSplit().setEnabled(true);
        properties.getReadWriteSplit().setMasterDataSource("master");
        properties.getReadWriteSplit().setSlaveDataSource("slave");
        properties.getReadWriteSplit().getReadAfterWrite().setEnabled(true);

        DataSource masterDs = mock(DataSource.class);
        DataSource slaveDs = mock(DataSource.class);
        Connection masterConn = mock(Connection.class);
        Connection slaveConn = mock(Connection.class);
        when(masterDs.getConnection()).thenReturn(masterConn);
        when(slaveDs.getConnection()).thenReturn(slaveConn);
        when(masterConn.getAutoCommit()).thenReturn(true);
        when(slaveConn.getAutoCommit()).thenReturn(true);

        DefaultWriteOccurredResolver resolver = new DefaultWriteOccurredResolver();
        ReadAfterWriteManager rawManager = new ReadAfterWriteManager(properties.getReadWriteSplit().getReadAfterWrite(), resolver);

        AtengDynamicRoutingDataSource routingDs = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
                new DefaultTenantDataSourceProvider(properties),
                null,
                rawManager
        );
        routingDs.setPrimary("master");
        routingDs.addDataSource("master", masterDs);
        routingDs.addDataSource("slave", slaveDs);

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(routingDs);
        txManager.setTransactionExecutionListeners(List.of(new AtengTransactionExecutionListener()));

        // 1. 只读事务：doBegin 期间即感知 readOnly=true，路由至 slave 并绑定 slave 标识，不误触发写标记
        TransactionTemplate readOnlyTemplate = new TransactionTemplate(txManager);
        readOnlyTemplate.setReadOnly(true);
        readOnlyTemplate.executeWithoutResult(status -> {
            assertThat(TransactionRoutingContext.isStartingActualTransaction()).isFalse();
            assertThat(TransactionSynchronizationManager.getResource(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY))
                    .isEqualTo("slave");
            assertThat(rawManager.isWriteOccurred()).isFalse();
        });
        assertThat(TransactionSynchronizationManager.hasResource(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY)).isFalse();

        // 2. 写事务：doBegin 期间感知非只读事务，路由至 master，触发写后即读并绑定 master 标识
        TransactionTemplate writeTemplate = new TransactionTemplate(txManager);
        writeTemplate.setReadOnly(false);
        writeTemplate.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.getResource(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY))
                    .isEqualTo("master");
            assertThat(rawManager.isWriteOccurred()).isTrue();
        });
        assertThat(TransactionSynchronizationManager.hasResource(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY)).isFalse();
    }

    @Test
    @DisplayName("对抗性边界：beginFailure 异常与嵌套栈弹出必须彻底清理 ThreadLocal，杜绝污染后续请求")
    void testListenerCleansUpOnBeginFailureAndNestedFrames() {
        AtengTransactionExecutionListener listener = new AtengTransactionExecutionListener();

        TransactionExecution outerTx = mock(TransactionExecution.class);
        when(outerTx.hasTransaction()).thenReturn(true);
        when(outerTx.isReadOnly()).thenReturn(false);

        TransactionExecution innerTx = mock(TransactionExecution.class);
        when(innerTx.hasTransaction()).thenReturn(true);
        when(innerTx.isReadOnly()).thenReturn(true);

        // 1. 嵌套入栈并验证栈顶只读状态隔离
        listener.beforeBegin(outerTx);
        TransactionRoutingContext.recordResolvedDataSourceKey("master");
        assertThat(TransactionRoutingContext.isStartingActualTransaction()).isTrue();
        assertThat(TransactionRoutingContext.isStartingReadOnly()).isFalse();

        listener.beforeBegin(innerTx);
        TransactionRoutingContext.recordResolvedDataSourceKey("slave");
        assertThat(TransactionRoutingContext.isStartingReadOnly()).isTrue();

        // 2. 内层启动失败：必须弹出内层帧且不触发绑定
        listener.afterBegin(innerTx, new RuntimeException("simulated db error"));
        assertThat(TransactionRoutingContext.isStartingActualTransaction()).isTrue();
        assertThat(TransactionRoutingContext.isStartingReadOnly()).isFalse();
        assertThat(TransactionSynchronizationManager.hasResource(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY)).isFalse();

        // 3. 外层弹出后栈彻底清空
        listener.afterBegin(outerTx, new RuntimeException("outer aborted"));
        assertThat(TransactionRoutingContext.isStartingActualTransaction()).isFalse();
    }

    @Test
    @DisplayName("空安全与非物理事务防御：null 与 hasTransaction=false 均安全忽略")
    void testNullAndNonPhysicalTransactionSafeNoOp() {
        AtengTransactionExecutionListener listener = new AtengTransactionExecutionListener();
        TransactionExecution noTx = mock(TransactionExecution.class);
        when(noTx.hasTransaction()).thenReturn(false);

        listener.beforeBegin(null);
        listener.beforeBegin(noTx);
        listener.afterBegin(null, null);
        listener.afterBegin(noTx, null);

        assertThat(TransactionRoutingContext.isStartingActualTransaction()).isFalse();
        assertThat(new AtengDatasourceAutoConfiguration().atengTransactionExecutionListener()).isNotNull();
    }
}
