package io.github.atengk.boot.sample.datasource;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.datasource.guard.TransactionRoutingContext;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.dto.AsyncContextPropagateRequest;
import io.github.atengk.boot.sample.datasource.dto.DataSourceCreateRequest;
import io.github.atengk.boot.sample.datasource.dto.DataSourceReloadRequest;
import io.github.atengk.boot.sample.datasource.dto.GroupMemberRequest;
import io.github.atengk.boot.sample.datasource.dto.GuardTransactionRequest;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.dto.PasswordCipherVerifyRequest;
import io.github.atengk.boot.sample.datasource.entity.OrderEntity;
import io.github.atengk.boot.sample.datasource.mapper.OrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
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

    @Autowired(required = false)
    private DefaultWriteOccurredResolver defaultWriteOccurredResolver;

    @Autowired
    private DynamicDatasourceTemplate dynamicDatasourceTemplate;

    @Autowired
    private OrderMapper orderMapper;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
        TransactionRoutingContext.clear();
        MDC.clear();
        if (defaultWriteOccurredResolver != null) {
            defaultWriteOccurredResolver.clearAll();
        }
        if (readAfterWriteManager != null) {
            readAfterWriteManager.clear();
        }
        ReadAfterWriteContextHolder.clear();
        dynamicDatasourceTemplate.removeGroupDataSource("tenant_1001_slave");
        if (dynamicDatasourceTemplate.containsDataSource("dynamic_reload_db")) {
            dynamicDatasourceTemplate.removeDataSource("dynamic_reload_db", false);
        }
        if (dynamicDatasourceTemplate.containsDataSource("dynamic_mockmvc_db")) {
            dynamicDatasourceTemplate.removeDataSource("dynamic_mockmvc_db", false);
        }
        if (dynamicDatasourceTemplate.containsDataSource("dynamic_fault_batch_db")) {
            dynamicDatasourceTemplate.removeDataSource("dynamic_fault_batch_db", false);
        }
        dynamicDatasourceTemplate.forEachDataSource(dsKey ->
                orderMapper.delete(new LambdaQueryWrapper<OrderEntity>().isNotNull(OrderEntity::getId)));
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
    @DisplayName("ReadWriteController: 分组节点对称增删、JSR-303 校验与显式 @DS(master) 写事务自动写后即读保护")
    void testReadWriteGroupMemberAndExplicitMasterController() throws Exception {
        // 1. 动态追加从库分组节点
        GroupMemberRequest memberReq = GroupMemberRequest.builder()
                .groupKey("tenant_1001_slave")
                .memberKey("slave")
                .build();
        mockMvc.perform(post("/sample/datasource/read-write/group/member/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.groupKey").value("tenant_1001_slave"))
                .andExpect(jsonPath("$.data.memberKey").value("slave"))
                .andExpect(jsonPath("$.data.operation").value("ADDED"))
                .andExpect(jsonPath("$.data.operated").value(true))
                .andExpect(jsonPath("$.data.activeGroupKeys").isArray());

        // 2. 对称摘除从库分组节点（首次成功返回 true，重复摘除返回 false）
        mockMvc.perform(post("/sample/datasource/read-write/group/member/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operation").value("REMOVED"))
                .andExpect(jsonPath("$.data.operated").value(true));

        mockMvc.perform(post("/sample/datasource/read-write/group/member/remove")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(memberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.operated").value(false));

        // 3. JSR-303 参数校验失败防线 (code=400)
        GroupMemberRequest invalidMemberReq = GroupMemberRequest.builder()
                .groupKey("")
                .memberKey("")
                .build();
        mockMvc.perform(post("/sample/datasource/read-write/group/member/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidMemberReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 4. 显式 @DS("master") 写事务自动触发写后即读保护，无需手工调用 markWriteOccurred
        String orderNo = "REST-RW-EXPLICIT-001";
        OrderCreateRequest explicitOrderReq = OrderCreateRequest.builder()
                .orderNo(orderNo)
                .productName("云数据库高可用实例")
                .amount(new BigDecimal("1999.00"))
                .build();
        mockMvc.perform(post("/sample/datasource/read-write/order/master-explicit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(explicitOrderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.routedDataSource").value("master"));

        // 5. 处于自动写后即读窗口期内，立即只读查询自动自适应回退至 master 查到刚写入的数据
        mockMvc.perform(get("/sample/datasource/read-write/order/get/" + orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo));

        // 6. 清理写窗口后再次只读查询路由回 slave，验证此前仅写入了 master（slave 无该订单）
        readAfterWriteManager.clear();
        mockMvc.perform(get("/sample/datasource/read-write/order/get/" + orderNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
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
    @DisplayName("LifecycleController: 数据源无损热重载、新库写入验证、核心白名单拦截与 JSR-303 校验")
    void testLifecycleHotReloadController() throws Exception {
        String dsKey = "dynamic_reload_db";
        DataSourceCreateRequest createReq = DataSourceCreateRequest.builder()
                .dsKey(dsKey)
                .url("jdbc:h2:mem:dynamic_reload_v1;DB_CLOSE_DELAY=-1;MODE=MySQL")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .preWarm(false)
                .build();

        // 1. 先动态挂载初始数据源
        mockMvc.perform(post("/sample/datasource/lifecycle/add")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 触发无损热重载切换至新连接池并开启并发预热与优雅排空
        DataSourceReloadRequest reloadReq = DataSourceReloadRequest.builder()
                .dsKey(dsKey)
                .url("jdbc:h2:mem:dynamic_reload_v2;DB_CLOSE_DELAY=-1;MODE=MySQL")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .preWarm(true)
                .preWarmConnections(2)
                .graceful(true)
                .build();

        mockMvc.perform(post("/sample/datasource/lifecycle/reload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reloadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dsKey").value(dsKey))
                .andExpect(jsonPath("$.data.preWarmed").value(true))
                .andExpect(jsonPath("$.data.warmedConnections").value(2))
                .andExpect(jsonPath("$.data.gracefulDrained").value(true))
                .andExpect(jsonPath("$.data.active").value(true));

        // 3. 热重载后在新池上执行真实业务写入验证原子切流生效
        mockMvc.perform(post("/sample/datasource/lifecycle/order/create")
                        .param("dsKey", dsKey)
                        .param("orderNo", "ORD-RELOAD-20261119-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(containsString("ORD-RELOAD-20261119-01")));

        // 4. 核心白名单数据源禁止热重载 (触发 DataSourceSwitchException -> 59002)
        DataSourceReloadRequest masterReloadReq = DataSourceReloadRequest.builder()
                .dsKey("master")
                .url("jdbc:h2:mem:master_override;DB_CLOSE_DELAY=-1;MODE=MySQL")
                .build();
        mockMvc.perform(post("/sample/datasource/lifecycle/reload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(masterReloadReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(59002));

        // 5. JSR-303 参数校验失败 (code=400)
        DataSourceReloadRequest invalidReq = DataSourceReloadRequest.builder()
                .dsKey("")
                .url("")
                .preWarmConnections(0)
                .build();
        mockMvc.perform(post("/sample/datasource/lifecycle/reload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
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
    @DisplayName("BatchController: 带返回值全库与全租户批处理结果聚合 (BatchExecutionResult)")
    void testBatchWithResultController() throws Exception {
        // 先向租户 1001 写入一笔真实订单，确保聚合结果映射中 tenant_1001 / 1001 计数 >= 1
        OrderCreateRequest orderReq = OrderCreateRequest.builder()
                .orderNo("ORD-BATCH-AGG-20261119-01")
                .productName("企业级年度订阅套餐")
                .amount(new BigDecimal("2999.00"))
                .build();
        mockMvc.perform(post("/sample/datasource/tenant/order")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(orderReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 1. 全物理数据源带返回值批处理结果聚合（ateng-boot-web 默认将 Long 序列化为 String 防前端精度丢失）
        mockMvc.perform(post("/sample/datasource/batch/inspect-all-with-result")
                        .param("policy", "FAIL_SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.failureCount").value(0))
                .andExpect(jsonPath("$.data.results.master").value(org.hamcrest.Matchers.matchesRegex("\\d+")))
                .andExpect(jsonPath("$.data.results.tenant_1001").value(org.hamcrest.Matchers.matchesRegex("[1-9]\\d*")));

        // 2. 全租户带返回值批处理结果聚合
        mockMvc.perform(post("/sample/datasource/batch/inspect-tenants-with-result")
                        .param("policy", "FAIL_SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(true))
                .andExpect(jsonPath("$.data.failureCount").value(0))
                .andExpect(jsonPath("$.data.results['1001']").value(org.hamcrest.Matchers.matchesRegex("[1-9]\\d*")))
                .andExpect(jsonPath("$.data.results['1002']").value(org.hamcrest.Matchers.matchesRegex("\\d+")));

        // 3. 部分失败容错聚合（挂载一个未建 t_order 表的异常库，验证 FAIL_SAFE 隔离失败并保留其余库结果）
        DataSourceProperty faultProp = new DataSourceProperty();
        faultProp.setPoolName("dynamic_fault_batch_db");
        faultProp.setUrl("jdbc:h2:mem:dynamic_fault_batch_db;DB_CLOSE_DELAY=-1;MODE=MySQL");
        faultProp.setUsername("sa");
        faultProp.setPassword("");
        faultProp.setDriverClassName("org.h2.Driver");
        dynamicDatasourceTemplate.addDataSource("dynamic_fault_batch_db", faultProp, false);

        mockMvc.perform(post("/sample/datasource/batch/inspect-all-with-result")
                        .param("policy", "FAIL_SAFE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.allSuccess").value(false))
                .andExpect(jsonPath("$.data.failureCount").value(1))
                .andExpect(jsonPath("$.data.failedTargets[0]").value("dynamic_fault_batch_db"))
                .andExpect(jsonPath("$.data.failures[0].target").value("dynamic_fault_batch_db"))
                .andExpect(jsonPath("$.data.failures[0].cause").doesNotExist())
                .andExpect(jsonPath("$.data.results.tenant_1001").value(org.hamcrest.Matchers.matchesRegex("[1-9]\\d*")));
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
    @DisplayName("OpsController: 零明文凭据 SM4/AES 密文探测校验（绝不回传明文）与五维异步上下文跨线程透传")
    void testOpsSecurityAndAsyncContextController() throws Exception {
        // 1. SM4 密文探测与瞬态解密校验（响应绝不包含原始明文）
        String sm4RawSecret = "ProdDbSecret#2026";
        byte[] sm4Key = CryptoUtil.generateKey(128);
        String sm4Encrypted = "enc:sm4:" + CryptoUtil.encodeBase64(sm4Key) + ":"
                + CryptoUtil.encryptSm4Ecb(sm4RawSecret, sm4Key);
        String sm4Body = mockMvc.perform(post("/sample/datasource/ops/security/verify-cipher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                PasswordCipherVerifyRequest.builder().encryptedPassword(sm4Encrypted).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.algorithm").value("SM4"))
                .andExpect(jsonPath("$.data.maskedDigest").value("******"))
                .andReturn().getResponse().getContentAsString();
        assertThat(sm4Body).doesNotContain(sm4RawSecret);

        // 2. AES 密文探测与瞬态解密校验（响应绝不包含原始明文）
        String aesRawSecret = "AesDbSecret#2026";
        byte[] aesKey = CryptoUtil.generateKey(128);
        byte[] aesIv = CryptoUtil.generateIv();
        String aesEncrypted = "enc:aes:" + CryptoUtil.encodeBase64(aesKey) + ":" + CryptoUtil.encodeBase64(aesIv) + ":"
                + CryptoUtil.encryptCbc(aesRawSecret, aesKey, aesIv);
        String aesBody = mockMvc.perform(post("/sample/datasource/ops/security/verify-cipher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                PasswordCipherVerifyRequest.builder().encryptedPassword(aesEncrypted).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.verified").value(true))
                .andExpect(jsonPath("$.data.algorithm").value("AES"))
                .andExpect(jsonPath("$.data.maskedDigest").value("******"))
                .andReturn().getResponse().getContentAsString();
        assertThat(aesBody).doesNotContain(aesRawSecret);

        // 3. 普通明文探测为非受管密文且不回传明文
        String plainSecret = "plainSecret123";
        String plainBody = mockMvc.perform(post("/sample/datasource/ops/security/verify-cipher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                PasswordCipherVerifyRequest.builder().encryptedPassword(plainSecret).build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(false))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.algorithm").value("PLAIN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(plainBody).doesNotContain(plainSecret);

        // 4. 损坏密文安全降级返回 verified=false
        mockMvc.perform(post("/sample/datasource/ops/security/verify-cipher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                PasswordCipherVerifyRequest.builder().encryptedPassword("enc:sm4:bad:payload").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.supported").value(true))
                .andExpect(jsonPath("$.data.verified").value(false))
                .andExpect(jsonPath("$.data.algorithm").value("SM4"));

        // 5. 密文校验 JSR-303 校验失败 (code=400)
        mockMvc.perform(post("/sample/datasource/ops/security/verify-cipher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                PasswordCipherVerifyRequest.builder().encryptedPassword("").build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 6. 五维异步上下文跨线程透传验证
        AsyncContextPropagateRequest asyncReq = AsyncContextPropagateRequest.builder()
                .traceId("trace-ops-20261119")
                .userId("user-ops-88")
                .tenantId("1001")
                .ignoreTenant(true)
                .dsKey("tenant_1001")
                .build();
        mockMvc.perform(post("/sample/datasource/ops/async-context/propagate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(asyncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.traceId").value("trace-ops-20261119"))
                .andExpect(jsonPath("$.data.userId").value("user-ops-88"))
                .andExpect(jsonPath("$.data.tenantId").value("1001"))
                .andExpect(jsonPath("$.data.ignoreTenant").value(true))
                .andExpect(jsonPath("$.data.dsKey").value("tenant_1001"))
                .andExpect(jsonPath("$.data.crossThreadVerified").value(true));

        // 7. 五维异步上下文 JSR-303 校验失败 (code=400)
        AsyncContextPropagateRequest invalidAsyncReq = AsyncContextPropagateRequest.builder()
                .userId("")
                .tenantId("")
                .dsKey("")
                .build();
        mockMvc.perform(post("/sample/datasource/ops/async-context/propagate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidAsyncReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
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

    @Test
    @DisplayName("GuardController: @DSTransactional 多源事务、无 @DS 租户透明事务假切源拦截(59002)与同库重入放行")
    void testGuardDsTransactionalAndTenantTxController() throws Exception {
        // 1. @DSTransactional 本地多源协调事务安全跨库切换
        GuardTransactionRequest dsTxReq = GuardTransactionRequest.builder()
                .primaryOrderNo("ORD-DSTX-WEB-01")
                .targetOrderNo("ORD-DSTX-WEB-02")
                .build();
        mockMvc.perform(post("/sample/datasource/guard/ds-transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dsTxReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("DS_TRANSACTIONAL"))
                .andExpect(jsonPath("$.data.primaryOrderNo").value("ORD-DSTX-WEB-01"))
                .andExpect(jsonPath("$.data.targetOrderNo").value("ORD-DSTX-WEB-02"));

        // 2. 无 @DS 注解的租户透明本地事务内尝试切向 master 触发假切源阻断 (code=59002)
        GuardTransactionRequest tenantBlockReq = GuardTransactionRequest.builder()
                .primaryOrderNo("ORD-TENANT-TX-01")
                .targetOrderNo("ORD-MASTER-ILLEGAL-01")
                .build();
        mockMvc.perform(post("/sample/datasource/guard/tenant-tx-false-switch")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantBlockReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(59002))
                .andExpect(jsonPath("$.msg").value(containsString("假切源致命风险")));

        // 3. 无 @DS 注解的租户透明本地事务内同租户物理库重入切源安全放行（覆盖 1001 与 1002 多租户动态匹配）
        GuardTransactionRequest tenantReentrantReq = GuardTransactionRequest.builder()
                .primaryOrderNo("ORD-TENANT-TX-02")
                .targetOrderNo("ORD-TENANT-REENTRANT-02")
                .build();
        mockMvc.perform(post("/sample/datasource/guard/tenant-tx-reentrant")
                        .header("X-Tenant-Id", "1001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenantReentrantReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("TENANT_TX_REENTRANT"))
                .andExpect(jsonPath("$.data.tenantId").value("1001"));

        GuardTransactionRequest tenant1002ReentrantReq = GuardTransactionRequest.builder()
                .primaryOrderNo("ORD-TENANT-TX-1002")
                .targetOrderNo("ORD-TENANT-REENTRANT-1002")
                .build();
        mockMvc.perform(post("/sample/datasource/guard/tenant-tx-reentrant")
                        .header("X-Tenant-Id", "1002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tenant1002ReentrantReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.mode").value("TENANT_TX_REENTRANT"))
                .andExpect(jsonPath("$.data.tenantId").value("1002"));

        // 4. JSR-303 参数校验失败 (code=400)
        GuardTransactionRequest invalidReq = GuardTransactionRequest.builder()
                .primaryOrderNo("")
                .targetOrderNo("")
                .build();
        mockMvc.perform(post("/sample/datasource/guard/ds-transactional")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
