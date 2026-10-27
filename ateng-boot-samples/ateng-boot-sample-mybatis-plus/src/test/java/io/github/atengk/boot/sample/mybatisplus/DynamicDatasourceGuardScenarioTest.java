package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicDatasourceGuardScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多数据源上下文隔离与协同守卫端到端集成测试
 * 验证：
 * 1. 在 Spring 活跃事务内部调用 DynamicDatasourceGuard.validateContextIsolated() 立即抛出异常，防御假切源
 * 2. 在 DataPermissionContextHolder 激活超级管理员或忽略状态时调用该守卫方法立即抛出异常，防御跨库越权穿透
 * 3. 显式上下文污染标记触发阻断与清理自愈
 * 4. 租户上下文一致性校验
 * 5. 干净独立上下文中安全放行与多数据源特征探测
 *
 * @author Ateng
 * @since 2026-10-28
 */
@SpringBootTest
@DisplayName("多数据源上下文隔离与协同守卫端到端测试")
class DynamicDatasourceGuardScenarioTest {

    @Autowired
    private DynamicDatasourceGuardScenarioService scenarioService;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        DynamicDatasourceGuard.clearTaintedContext();
        DataPermissionContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("在活跃 Spring 事务内部尝试切源或校验隔离性时立即抛出 IllegalStateException 防御假切源")
    void shouldThrowExceptionWhenValidatingInsideActiveTransaction() {
        assertThatThrownBy(() -> scenarioService.executeInTransaction(DynamicDatasourceGuard::validateContextIsolated))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("活跃事务")
                .hasMessageContaining("TransactionActive=true");
    }

    @Test
    @DisplayName("数据权限处于超级管理员状态时调用守卫立即抛出异常防御跨库越权穿透")
    void shouldThrowExceptionWhenDataPermissionSuperAdminActive() {
        assertThatThrownBy(() -> scenarioService.executeWithSuperAdminPermission(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据权限上下文残留")
                .hasMessageContaining("isSuperAdmin=true");
    }

    @Test
    @DisplayName("数据权限处于忽略状态时调用守卫立即抛出异常防御跨库越权穿透")
    void shouldThrowExceptionWhenDataPermissionIgnoreActive() {
        assertThatThrownBy(() -> scenarioService.executeWithIgnorePermission(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据权限上下文残留")
                .hasMessageContaining("isIgnore=true");
    }

    @Test
    @DisplayName("显式上下文污染标记状态下调用守卫立即阻断并在清理后恢复放行")
    void shouldBlockWhenContextTaintedAndRecoverAfterClear() {
        DynamicDatasourceGuard.markContextTainted("模拟中间件切源异常现场");
        assertThat(DynamicDatasourceGuard.isContextTainted()).isTrue();

        assertThatThrownBy(DynamicDatasourceGuard::validateContextIsolated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多数据源上下文污染异常")
                .hasMessageContaining("模拟中间件切源异常现场");

        DynamicDatasourceGuard.clearTaintedContext();
        assertThat(DynamicDatasourceGuard.isContextTainted()).isFalse();
        assertThatCode(DynamicDatasourceGuard::validateContextIsolated).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("租户上下文一致性校验：匹配时安全放行，不一致时抛出受管异常")
    void shouldValidateTenantContextConsistency() {
        // 期望租户与当前租户一致
        assertThatCode(() -> scenarioService.validateTenantContext("TENANT_001", "TENANT_001"))
                .doesNotThrowAnyException();

        // 期望租户与当前租户不一致
        assertThatThrownBy(() -> scenarioService.validateTenantContext("TENANT_001", "TENANT_002"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("租户上下文不一致")
                .hasMessageContaining("TENANT_001")
                .hasMessageContaining("TENANT_002");
    }

    @Test
    @DisplayName("在干净无污染上下文中调用守卫安全放行")
    void shouldPassWhenContextIsCleanAndIsolated() {
        assertThatCode(() -> scenarioService.validateCleanContext()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("单数据源环境中健康自检探针正常运行")
    void shouldDetectDataSourceFeatureHealthily() {
        // 标准单 H2 数据源环境下探测结果应正常
        boolean detected = scenarioService.isMultiDataSourceDetected();
        // 单数据源应为 false
        assertThat(detected).isFalse();
    }
}
