package io.github.atengk.boot.datasource.guard;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.dynamic.datasource.processor.DsProcessor;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 事务上下文智能协同与假切源刚性拦截防卫 (DynamicDatasourceGuard) 单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class DynamicDatasourceGuardTest {

    private DatasourceProperties properties;
    private DynamicDatasourceGuard guard;
    private DynamicDatasourceTemplate template;
    private AtengDynamicRoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        properties = new DatasourceProperties();
        properties.getGuard().setEnabled(true);
        properties.getGuard().setFailOnFalseSwitch(true);
        properties.getMultiTenancy().setDefaultDataSource("master");
        properties.getMultiTenancy().setDataSourcePrefix("tenant_");

        guard = new DynamicDatasourceGuard(properties);

        DataSource masterDs = mock(DataSource.class);
        DataSource tenant1001Ds = mock(DataSource.class);

        routingDataSource = new AtengDynamicRoutingDataSource(Collections.emptyList(), properties, new DefaultTenantDataSourceProvider(properties));
        routingDataSource.setPrimary("master");
        routingDataSource.addDataSource("master", masterDs);
        routingDataSource.addDataSource("tenant_1001", tenant1001Ds);

        template = new DynamicDatasourceTemplate(routingDataSource, properties, new DefaultTenantDataSourceProvider(properties), guard);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(DynamicDatasourceGuard.TX_BOUND_DATASOURCE_RESOURCE_KEY);
        TransactionSynchronizationManager.clear();
        TransactionContext.remove();
        DynamicDataSourceContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试无活动事务时安全放行切源")
    void testNoActiveTransaction_AllowsSwitch() {
        assertThat(guard.isSpringTransactionActive()).isFalse();
        assertThat(guard.isDSTransactionActive()).isFalse();

        // 不应抛出异常
        guard.validateSwitch("tenant_1001");
    }

    @Test
    @DisplayName("测试处于 @DSTransactional 本地多源协调事务时安全放行跨库切源")
    void testDSTransactionalActive_AllowsSwitch() {
        TransactionContext.bind("test-xid-8888");
        assertThat(guard.isDSTransactionActive()).isTrue();

        // 模拟 Spring 事务标记可能并存的情况
        TransactionSynchronizationManager.setActualTransactionActive(true);

        // @DSTransactional 具备多数据源协调能力，应安全放行
        guard.validateSwitch("tenant_1001");
        guard.validateSwitch("master");
    }

    @Test
    @DisplayName("测试 Spring 单连接事务中尝试跨库切源刚性拦截并抛出 DataSourceSwitchException")
    void testSpringTransactionActive_SwitchToDifferentDataSource_ThrowsException() {
        // 激活 Spring 事务并初始化同步器
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        // 首次切源/执行绑定到 master
        assertThat(guard.getCurrentTransactionDataSourceKey()).isEqualTo("master");

        // 尝试切源至 tenant_1001 触发假切源拦截
        assertThatThrownBy(() -> guard.validateSwitch("tenant_1001"))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("当前事务已绑定数据源 [master]")
                .hasMessageContaining("尝试切源至 [tenant_1001] 将被 Spring 事务静默忽略并造成串库");
    }

    @Test
    @DisplayName("测试 Spring 单连接事务中切源至相同数据源安全放行")
    void testSpringTransactionActive_SwitchToSameDataSource_Allowed() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        // 初始绑定 master
        guard.validateSwitch("master");

        // 再次声明 master，未发生物理跨库，安全放行
        guard.validateSwitch("master");
    }

    @Test
    @DisplayName("测试方法标注 @Transactional(propagation = REQUIRES_NEW) 时安全放行独立切源")
    void testSpringTransactionActive_RequiresNewMethod_Allowed() throws NoSuchMethodException {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        Method requiresNewMethod = SampleService.class.getMethod("requiresNewMethod");

        // 虽然处于 Spring 事务，但目标方法声明了 REQUIRES_NEW，安全放行
        guard.validateSwitch("tenant_1001", requiresNewMethod);
    }

    @Test
    @DisplayName("测试方法标注普通 @Transactional (REQUIRED) 时跨库切源被拦截")
    void testSpringTransactionActive_RequiredMethod_ThrowsException() throws NoSuchMethodException {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        Method requiredMethod = SampleService.class.getMethod("requiredMethod");

        assertThatThrownBy(() -> guard.validateSwitch("tenant_1001", requiredMethod))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("当前事务已绑定数据源 [master]");
    }

    @Test
    @DisplayName("测试配置 failOnFalseSwitch=false 时发生假切源仅告警不阻断")
    void testFailOnFalseSwitchDisabled_WarnOnly() {
        properties.getGuard().setFailOnFalseSwitch(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        // 不应抛出异常
        guard.validateSwitch("tenant_1001");
    }

    @Test
    @DisplayName("测试配置 guard.enabled=false 时全局跳过假切源校验")
    void testGuardDisabled_BypassesCheck() {
        properties.getGuard().setEnabled(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        // 不应抛出异常
        guard.validateSwitch("tenant_1001");
    }

    @Test
    @DisplayName("测试 DynamicDatasourceTemplate 在 Spring 事务中尝试假切源被刚性拦截")
    void testDynamicDatasourceTemplate_FalseSwitchIntercepted() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        assertThatThrownBy(() -> template.callInDataSource("tenant_1001", () -> "data"))
                .isInstanceOf(DataSourceSwitchException.class)
                .hasMessageContaining("检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险");

        assertThatThrownBy(() -> template.runInTenantDataSource("1001", () -> {}))
                .isInstanceOf(DataSourceSwitchException.class);
    }

    @Test
    @DisplayName("测试 AtengDynamicDataSourceAnnotationInterceptor 对 @DS 注解的拦截与放行")
    void testAtengDynamicDataSourceAnnotationInterceptor() throws Throwable {
        DsProcessor dsProcessor = mock(DsProcessor.class);
        AtengDynamicDataSourceAnnotationInterceptor interceptor = new AtengDynamicDataSourceAnnotationInterceptor(
                false, dsProcessor, guard
        );

        SampleService service = new SampleService();
        Method requiredMethod = SampleService.class.getMethod("requiredMethod");
        Method requiresNewMethod = SampleService.class.getMethod("requiresNewMethod");

        // 场景 1：处于 Spring 事务中调用普通 @DS 方法 -> 抛出 DataSourceSwitchException
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();

        MethodInvocation invocationRequired = mock(MethodInvocation.class);
        when(invocationRequired.getMethod()).thenReturn(requiredMethod);
        when(invocationRequired.getThis()).thenReturn(service);

        assertThatThrownBy(() -> interceptor.invoke(invocationRequired))
                .isInstanceOf(DataSourceSwitchException.class);

        // 场景 2：处于 Spring 事务中调用 REQUIRES_NEW @DS 方法 -> 放行
        MethodInvocation invocationRequiresNew = mock(MethodInvocation.class);
        when(invocationRequiresNew.getMethod()).thenReturn(requiresNewMethod);
        when(invocationRequiresNew.getThis()).thenReturn(service);
        when(invocationRequiresNew.proceed()).thenReturn("requires_new_success");

        Object result = interceptor.invoke(invocationRequiresNew);
        assertThat(result).isEqualTo("requires_new_success");
        assertThat(DynamicDataSourceContextHolder.peek()).isNull();

        // 场景 3：处于 @DSTransactional 中调用普通 @DS 方法 -> 放行
        TransactionContext.bind("test-xid-999");
        when(invocationRequired.proceed()).thenReturn("ds_tx_success");

        Object dsTxResult = interceptor.invoke(invocationRequired);
        assertThat(dsTxResult).isEqualTo("ds_tx_success");
        assertThat(DynamicDataSourceContextHolder.peek()).isNull();
    }

    @Test
    @DisplayName("测试只读事务下准确识别从库为事务数据源并安全放行从库操作")
    void testReadOnlyTransaction_IdentifiesSlaveDataSource() {
        properties.getReadWriteSplit().setEnabled(true);
        properties.getReadWriteSplit().setMasterDataSource("master");
        properties.getReadWriteSplit().setSlaveDataSource("slave");

        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        // 验证只读事务初始绑定的数据源为 slave
        String currentTxDsKey = guard.getCurrentTransactionDataSourceKey();
        assertThat(currentTxDsKey).isEqualTo("slave");

        // 目标切向 slave 时安全放行
        guard.validateSwitch("slave");
        guard.validateContextIsolated("slave");
    }

    /**
     * 测试用例专属桩服务类
     */
    static class SampleService {

        @DS("tenant_1001")
        @Transactional(propagation = Propagation.REQUIRED)
        public String requiredMethod() {
            return "required";
        }

        @DS("tenant_1001")
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public String requiresNewMethod() {
            return "requires_new";
        }
    }
}
