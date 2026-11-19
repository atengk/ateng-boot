package io.github.atengk.boot.sample.flyway;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;
import io.github.atengk.boot.sample.flyway.dto.BaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.BatchBaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.BatchPolicyRequest;
import io.github.atengk.boot.sample.flyway.dto.BatchTargetsRequest;
import io.github.atengk.boot.sample.flyway.dto.DestructiveDdlDemoRequest;
import io.github.atengk.boot.sample.flyway.dto.IsolatedCredentialDemoRequest;
import io.github.atengk.boot.sample.flyway.dto.TargetOperationRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantBatchBaselineRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantMountRequest;
import io.github.atengk.boot.sample.flyway.dto.TenantOrderCreateRequest;
import io.github.atengk.boot.sample.flyway.entity.TenantRegistryEntity;
import io.github.atengk.boot.sample.flyway.listener.SampleFlywayEventListener;
import io.github.atengk.boot.sample.flyway.mapper.TenantRegistryMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flyway 三大领域 Controller 与 Actuator 探针端到端 MockMvc 集成测试
 *
 * @author Ateng
 * @since 2026-11-17
 */
@SpringBootTest
@AutoConfigureMockMvc
class FlywaySampleControllersMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DynamicDatasourceTemplate dynamicDatasourceTemplate;

    @Autowired
    private TenantRegistryMapper tenantRegistryMapper;

    @Autowired
    private SampleFlywayEventListener sampleFlywayEventListener;

    @AfterEach
    void cleanupContexts() {
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
        if (dynamicDatasourceTemplate.containsDataSource("tenant_1003")) {
            dynamicDatasourceTemplate.removeDataSource("tenant_1003", true);
        }
        tenantRegistryMapper.delete(
                new LambdaQueryWrapper<TenantRegistryEntity>().eq(TenantRegistryEntity::getTenantCode, "1003"));
    }

    private boolean tableExists(DataSource ds, String tableName) throws Exception {
        try (Connection conn = ds.getConnection();
             var rs = conn.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                if (tableName.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    @DisplayName("启动拓扑隔离校验：master、log_db、tenant_1001/1002 自动建表，slave 从库零入侵")
    void shouldIsolateStartupMigrationsAndKeepSlaveUntouched() throws Exception {
        DynamicRoutingDataSource routing = (DynamicRoutingDataSource) dataSource;

        DataSource masterDs = routing.getDataSources().get("master");
        DataSource slaveDs = routing.getDataSources().get("slave");
        DataSource logDs = routing.getDataSources().get("log_db");
        DataSource t1001Ds = routing.getDataSources().get("tenant_1001");
        DataSource t1002Ds = routing.getDataSources().get("tenant_1002");

        assertThat(tableExists(masterDs, "sys_tenant_registry")).isTrue();
        assertThat(tableExists(masterDs, "flyway_schema_history")).isTrue();
        assertThat(tableExists(masterDs, "biz_tenant_order")).isFalse();

        assertThat(tableExists(logDs, "biz_audit_log")).isTrue();
        assertThat(tableExists(logDs, "log_flyway_schema_history")).isTrue();
        assertThat(tableExists(logDs, "flyway_schema_history")).isFalse();

        assertThat(tableExists(t1001Ds, "biz_tenant_order")).isTrue();
        assertThat(tableExists(t1001Ds, "sys_tenant_registry")).isFalse();
        assertThat(tableExists(t1002Ds, "biz_tenant_order")).isTrue();

        assertThat(tableExists(slaveDs, "flyway_schema_history")).isFalse();
        assertThat(tableExists(slaveDs, "sys_tenant_registry")).isFalse();
        assertThat(tableExists(slaveDs, "biz_audit_log")).isFalse();
        assertThat(slaveDs).isNotNull();
        assertThat(tableExists(slaveDs, "biz_tenant_order")).isFalse();
    }

    @Test
    @DisplayName("FlywayMigrationController: 主库与静态库迁移、预检、校验及 MyBatis-Plus 数据查询")
    void shouldVerifyMigrationControllerAndMybatisPlusBarrier() throws Exception {
        mockMvc.perform(get("/sample/flyway/migration/platform/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].tenantCode", containsInAnyOrder("1000", "1001", "1002")))
                .andExpect(jsonPath("$.data[?(@.tenantCode=='1000')].sourceDs").value(hasItem("java-migration")));

        mockMvc.perform(get("/sample/flyway/migration/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].auditScope").value("platform-audit"));

        mockMvc.perform(get("/sample/flyway/migration/pending").param("targetKey", "master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));

        TargetOperationRequest validateReq = TargetOperationRequest.builder().targetKey("log_db").build();
        mockMvc.perform(post("/sample/flyway/migration/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.valid").value(true));

        int completedBefore = sampleFlywayEventListener.getCompletedEventCount();

        mockMvc.perform(post("/sample/flyway/migration/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TargetOperationRequest.builder().targetKey("master").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.success").value(true));

        mockMvc.perform(post("/sample/flyway/migration/execute-static"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2));

        assertThat(sampleFlywayEventListener.getCompletedEventCount()).isGreaterThanOrEqualTo(completedBefore + 3);

        // JSR-303 参数空值拦截
        mockMvc.perform(post("/sample/flyway/migration/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TargetOperationRequest.builder().targetKey(" ").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 批量多目标待迁移预检与 JSR-303 空列表拦截
        BatchTargetsRequest pendingTargetsReq = BatchTargetsRequest.builder()
                .targetKeys(List.of("master", "log_db"))
                .policy(BatchExecutionPolicy.FAIL_SAFE)
                .build();
        mockMvc.perform(post("/sample/flyway/migration/pending-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pendingTargetsReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2))
                .andExpect(jsonPath("$.data.results.master", hasSize(0)));

        mockMvc.perform(post("/sample/flyway/migration/pending-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchTargetsRequest.builder()
                                .targetKeys(Collections.emptyList())
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("FlywayTenantController: 全租户批处理、动态挂载 tenant_1003 自动建表及跨租户数据物理隔离")
    void shouldVerifyTenantControllerDynamicMountAndPhysicalIsolation() throws Exception {
        mockMvc.perform(post("/sample/flyway/tenant/migrate-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2));

        mockMvc.perform(post("/sample/flyway/tenant/validate-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true));

        mockMvc.perform(get("/sample/flyway/tenant/pending-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2));

        mockMvc.perform(get("/sample/flyway/tenant/info-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.results.tenant_1001.currentVersion").value("2"))
                .andExpect(jsonPath("$.data.results.tenant_1002.currentVersion").value("2"));

        mockMvc.perform(post("/sample/flyway/tenant/repair-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchPolicyRequest.builder()
                                .policy(BatchExecutionPolicy.FAIL_FAST)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2));

        // 租户已含迁移历史，FAIL_SAFE 下聚合返回失败明细 62005，FAIL_FAST 下立即抛出 62005
        mockMvc.perform(post("/sample/flyway/tenant/baseline-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TenantBatchBaselineRequest.builder()
                                .version("1")
                                .description("tenant-batch-baseline")
                                .policy(BatchExecutionPolicy.FAIL_SAFE)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(false))
                .andExpect(jsonPath("$.data.failedCount").value(2))
                .andExpect(jsonPath("$.data.failures[0].errorCode").value(FlywayResultCode.BASELINE_FAILED.getCode()));

        mockMvc.perform(post("/sample/flyway/tenant/baseline-all")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TenantBatchBaselineRequest.builder()
                                .policy(BatchExecutionPolicy.FAIL_FAST)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.BASELINE_FAILED.getCode()));

        // 1. 动态挂载 tenant_1003 并触发自动建表（V1 SQL + V2 JavaMigration）
        TenantMountRequest mountReq = TenantMountRequest.builder()
                .tenantId("1003")
                .tenantName("新锐智算科技有限公司")
                .build();
        mockMvc.perform(post("/sample/flyway/tenant/mount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mountReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.tenantId").value("1003"))
                .andExpect(jsonPath("$.data.dsKey").value("tenant_1003"))
                .andExpect(jsonPath("$.data.mounted").value(true))
                .andExpect(jsonPath("$.data.currentVersion").value("2"))
                .andExpect(jsonPath("$.data.appliedCount").value(2))
                .andExpect(jsonPath("$.data.initialOrderCount").value(2));

        // 2. 向 tenant_1003 写入专属订单并验证与 tenant_1001 物理隔离
        TenantOrderCreateRequest orderReq = TenantOrderCreateRequest.builder()
                .tenantId("1003")
                .orderNo("ORD-1003-LIVE-888")
                .productName("云原生分布式网关授权")
                .amount(new BigDecimal("4999.00"))
                .build();
        mockMvc.perform(post("/sample/flyway/tenant/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("ORD-1003-LIVE-888"))
                .andExpect(jsonPath("$.data.routedDataSource").value("tenant_1003"));

        mockMvc.perform(get("/sample/flyway/tenant/orders").param("tenantId", "1003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].orderNo", hasItem("ORD-1003-LIVE-888")));

        mockMvc.perform(get("/sample/flyway/tenant/orders").param("tenantId", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].orderNo", not(hasItem("ORD-1003-LIVE-888"))));

        // 3. 非法租户 ID 格式触发 JSR-303 快速失败
        TenantMountRequest invalidMount = TenantMountRequest.builder().tenantId("../bad").build();
        mockMvc.perform(post("/sample/flyway/tenant/mount")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidMount)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("FlywayGovernanceController 与 /actuator/health: 历史探查、修复基线、clean 守卫拦截与 SM4 瞬态演练")
    void shouldVerifyGovernanceControllerAndActuatorHealth() throws Exception {
        mockMvc.perform(get("/sample/flyway/governance/info").param("targetKey", "log_db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetKey").value("log_db"))
                .andExpect(jsonPath("$.data.currentVersion").value("2"))
                .andExpect(jsonPath("$.data.appliedCount").value(2));

        mockMvc.perform(get("/sample/flyway/governance/info-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[*].targetKey", hasItem("master")))
                .andExpect(jsonPath("$.data[*].targetKey", hasItem("log_db")))
                .andExpect(jsonPath("$.data[*].targetKey", hasItem("tenant_1001")));

        mockMvc.perform(get("/sample/flyway/governance/info-batch").param("policy", "FAIL_SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.results.master.targetKey").value("master"))
                .andExpect(jsonPath("$.data.results.log_db.currentVersion").value("2"));

        // 指定多目标聚合探查（含不存在目标时 FAIL_SAFE 返回局部成功与脱敏失败项）
        mockMvc.perform(post("/sample/flyway/governance/info-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchTargetsRequest.builder()
                                .targetKeys(List.of("master", "unknown_nonexistent_db"))
                                .policy(BatchExecutionPolicy.FAIL_SAFE)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(false))
                .andExpect(jsonPath("$.data.successCount").value(1))
                .andExpect(jsonPath("$.data.failedCount").value(1))
                .andExpect(jsonPath("$.data.failedTargets", hasItem("unknown_nonexistent_db")))
                .andExpect(jsonPath("$.data.failures[0].errorCode").value(FlywayResultCode.TARGET_NOT_FOUND.getCode()));

        mockMvc.perform(post("/sample/flyway/governance/repair-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchTargetsRequest.builder()
                                .targetKeys(List.of("master", "log_db"))
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.totalTargets").value(2));

        mockMvc.perform(post("/sample/flyway/governance/baseline-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchBaselineRequest.builder()
                                .targetKeys(List.of("log_db"))
                                .version("1")
                                .description("batch-baseline")
                                .policy(BatchExecutionPolicy.FAIL_FAST)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.BASELINE_FAILED.getCode()));

        mockMvc.perform(post("/sample/flyway/governance/baseline-targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(BatchBaselineRequest.builder()
                                .targetKeys(Collections.emptyList())
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(get("/sample/flyway/governance/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.failedCount").value(0));

        mockMvc.perform(post("/sample/flyway/governance/repair")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TargetOperationRequest.builder().targetKey("master").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 已含迁移历史的目标执行 baseline 应被转译为语义码 BASELINE_FAILED (62005)
        mockMvc.perform(post("/sample/flyway/governance/baseline")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                BaselineRequest.builder().targetKey("log_db").version("1").description("sample-baseline").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.BASELINE_FAILED.getCode()));

        // Clean 守卫拦截返回 62003
        mockMvc.perform(post("/sample/flyway/governance/clean")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(TargetOperationRequest.builder().targetKey("master").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.CLEAN_FORBIDDEN.getCode()));

        // SM4 加密隔离 DDL 凭据瞬态连接演练
        IsolatedCredentialDemoRequest credReq = IsolatedCredentialDemoRequest.builder()
                .targetKey("log_db")
                .username("sa")
                .plainPassword("")
                .build();
        mockMvc.perform(post("/sample/flyway/governance/isolated-credential/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(credReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.targetKey").value("log_db"))
                .andExpect(jsonPath("$.data.validationPassed").value(true))
                .andExpect(jsonPath("$.data.activeTransientPoolCount").value(0))
                .andExpect(jsonPath("$.data.closedTransientPoolCount").value("1"));

        mockMvc.perform(get("/sample/flyway/governance/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.flyway.status").value("UP"));

        // 未知目标数据源触发 TARGET_NOT_FOUND (62004)
        mockMvc.perform(get("/sample/flyway/governance/info").param("targetKey", "unknown_nonexistent_db"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.TARGET_NOT_FOUND.getCode()));

        // 待执行 SQL 高危 DDL 静态预检演练：未授权 TRUNCATE 触发 62010，豁免关闭同样触发 62010，豁免放行返回 200，空参数触发 400
        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DestructiveDdlDemoRequest.builder()
                                .sqlScript("CREATE TABLE t_audit (id BIGINT); TRUNCATE TABLE t_audit;")
                                .allowExemptionAnnotation(true)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode()));

        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DestructiveDdlDemoRequest.builder()
                                .sqlScript("-- @ateng-allow-destructive-ddl\nCREATE TABLE t_audit (id BIGINT); DROP TABLE t_audit;")
                                .allowExemptionAnnotation(false)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(FlywayResultCode.DESTRUCTIVE_DDL_FORBIDDEN.getCode()));

        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DestructiveDdlDemoRequest.builder()
                                .sqlScript("-- @ateng-allow-destructive-ddl\nCREATE TABLE t_audit (id BIGINT); DROP TABLE t_audit;")
                                .allowExemptionAnnotation(true)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.passed").value(true))
                .andExpect(jsonPath("$.data.executedMigrations").value(1));

        mockMvc.perform(post("/sample/flyway/governance/destructive-ddl-guard/demo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DestructiveDdlDemoRequest.builder()
                                .sqlScript("  ")
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
