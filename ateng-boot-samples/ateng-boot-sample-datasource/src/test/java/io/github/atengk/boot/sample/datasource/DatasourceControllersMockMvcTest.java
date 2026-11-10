package io.github.atengk.boot.sample.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.sample.datasource.dto.DataSourceCreateRequest;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据源 6 大领域 Controller 端到端 MockMvc 集成测试套件
 * <p>
 * 遵循最高测试接缝（The Highest Seam）原则，全链路验证 Web 边界请求解析、
 * 请求头拦截、动态切库路由、从库自愈、生命周期排空、全库批处理与事务守卫拦截。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@SpringBootTest
@AutoConfigureMockMvc
class DatasourceControllersMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReadAfterWriteManager readAfterWriteManager;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        if (readAfterWriteManager != null) {
            readAfterWriteManager.clear();
        }
        ReadAfterWriteContextHolder.clear();
    }

    @Test
    @DisplayName("TenantController: 携带 X-Tenant-Id 路由至对应租户库下单与查询，验证跨租户物理隔离")
    void testTenantController() throws Exception {
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderNo("REST-TENANT-1001")
                .productName("SaaS MockMvc Item")
                .amount(new BigDecimal("128.50"))
                .build();

        // 1. POST /sample/datasource/tenant/order 创建订单
        mockMvc.perform(post("/sample/datasource/tenant/order")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("REST-TENANT-1001"))
                .andExpect(jsonPath("$.data.tenantId").value("1001"));

        // 2. GET /sample/datasource/tenant/order/REST-TENANT-1001 租户 1001 命中
        mockMvc.perform(get("/sample/datasource/tenant/order/REST-TENANT-1001")
                        .header("X-Tenant-Id", "1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value("REST-TENANT-1001"));

        // 3. 租户 1002 查不到租户 1001 的数据（物理隔离）
        mockMvc.perform(get("/sample/datasource/tenant/order/REST-TENANT-1001")
                        .header("X-Tenant-Id", "1002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());

        // 4. JSR-303 参数校验快速失败防线
        OrderCreateRequest invalidReq = OrderCreateRequest.builder()
                .orderNo("")
                .productName("Invalid")
                .amount(new BigDecimal("-10.00"))
                .build();
        mockMvc.perform(post("/sample/datasource/tenant/order")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("ReadWriteController: 主库写入、写后读主防延迟窗口与从库故障主动探活自愈")
    void testReadWriteController() throws Exception {
        String orderNo = "REST-RW-001";
        OrderCreateRequest req = OrderCreateRequest.builder()
                .orderNo(orderNo)
                .productName("RW Item")
                .amount(new BigDecimal("666.00"))
                .build();

        // 1. POST /sample/datasource/read-write/order/master 主库写并激活防延迟
        mockMvc.perform(post("/sample/datasource/read-write/order/master")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // 2. GET /sample/datasource/read-write/order/get/{orderNo} 处于写后读主窗口期内，自动重定向到 master 读取
        mockMvc.perform(get("/sample/datasource/read-write/order/get/" + orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // 验证请求结束后 ReadAfterWriteFilter 安全清理线程上下文
        assertThat(ReadAfterWriteContextHolder.isWriteOccurred()).isFalse();

        // 3. 从库主动探活与熔断隔离
        mockMvc.perform(post("/sample/datasource/read-write/failover/simulate-failure")
                        .param("replicaKey", "slave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        mockMvc.perform(get("/sample/datasource/read-write/failover/blacklisted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("slave"));

        mockMvc.perform(post("/sample/datasource/read-write/failover/recover")
                        .param("replicaKey", "slave"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("LifecycleController: 动态加库建表、业务写入与超时优雅排空注销")
    void testLifecycleController() throws Exception {
        String dsKey = "dynamic_mockmvc_db";
        DataSourceCreateRequest createReq = DataSourceCreateRequest.builder()
                .dsKey(dsKey)
                .url("jdbc:h2:mem:" + dsKey + ";DB_CLOSE_DELAY=-1;MODE=MySQL")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .preWarm(true)
                .preWarmConnections(2)
                .build();

        // 1. 动态挂载并建表
        mockMvc.perform(post("/sample/datasource/lifecycle/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString(dsKey)));

        // 2. 动态数据源业务写入
        mockMvc.perform(post("/sample/datasource/lifecycle/order/create")
                        .param("dsKey", dsKey)
                        .param("orderNo", "DYN-MOCK-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("DYN-MOCK-001")));

        // 3. 优雅排空并注销
        mockMvc.perform(delete("/sample/datasource/lifecycle/remove/" + dsKey)
                        .param("drain", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString(dsKey)));
    }

    @Test
    @DisplayName("BatchController: 全物理库批量容错巡检、全租户遍历与并发预热")
    void testBatchController() throws Exception {
        // 1. 全库批量巡检 (FAIL_SAFE)
        mockMvc.perform(post("/sample/datasource/batch/inspect-all")
                        .param("policy", "FAIL_SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true));

        // 2. 全租户遍历巡检
        mockMvc.perform(post("/sample/datasource/batch/inspect-tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true));

        // 3. 连接池并发预热
        mockMvc.perform(post("/sample/datasource/batch/pre-warm")
                        .param("dsKey", "master")
                        .param("connections", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
    }

    @Test
    @DisplayName("OpsController: 运行指标快照查询、外部差量同步与核心白名单校验")
    void testOpsController() throws Exception {
        // 1. 获取全部连接池指标快照
        mockMvc.perform(get("/sample/datasource/ops/pool-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.master").exists());

        // 2. 获取单个连接池指标快照
        mockMvc.perform(get("/sample/datasource/ops/pool-stats/master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.poolName").value("master"));

        // 3. 触发外部差量同步
        mockMvc.perform(post("/sample/datasource/ops/sync-external"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 核心白名单校验
        mockMvc.perform(get("/sample/datasource/ops/core-whitelist/check")
                        .param("dsKey", "master"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
    }

    @Test
    @DisplayName("GuardController: 事务内假切源刚性拦截报错演练与独立新事务安全放行")
    void testGuardController() throws Exception {
        // 1. 事务内违规切源 -> 触发 DynamicDatasourceGuard 拦截，抛出 DataSourceSwitchException，返回 59002 错误码
        mockMvc.perform(post("/sample/datasource/guard/simulate-false-switch")
                        .param("masterOrderNo", "M-GUARD-1")
                        .param("tenantOrderNo", "T-GUARD-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(59002));

        // 2. 采用 REQUIRES_NEW 挂起事务独立切源 -> 安全放行
        mockMvc.perform(post("/sample/datasource/guard/safe-switch-requires-new")
                        .param("masterOrderNo", "M-SAFE-1")
                        .param("tenantOrderNo", "T-SAFE-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("Safe switch completed")));

        // 3. 同源事务重入切源 -> 安全放行
        mockMvc.perform(post("/sample/datasource/guard/same-ds-reentrant")
                        .param("orderNo", "M-REENTRANT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
