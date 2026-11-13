package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.audit.DataChangeAuditContextHolder;
import io.github.atengk.boot.sample.mybatisplus.audit.MemoryDataChangeAuditProvider;
import io.github.atengk.boot.sample.mybatisplus.entity.ProductSkuEntity;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.ProductSkuMapper;
import io.github.atengk.boot.sample.mybatisplus.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.mybatisplus.model.BatchSkuDTO;
import io.github.atengk.boot.sample.mybatisplus.model.OrderAuditUpdateDTO;
import io.github.atengk.boot.sample.mybatisplus.model.SkuDeductDTO;
import io.github.atengk.boot.sample.mybatisplus.slowsql.SampleSlowSqlAlertProvider;
import io.github.atengk.boot.sample.mybatisplus.sqllog.MemorySqlLogStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 切片 3：原生高性能批处理、流式游标、并发锁与持久层全链路可观测 Controller 集成测试
 * 覆盖 MpBatchStreamAndLockController 与 MpObservabilityAndAuditController 全部功能与极端防御边界
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("切片3：批处理/流式游标/并发锁与可观测性 Controller 集成测试")
class MpBatchLockAndObservabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProductSkuMapper productSkuMapper;

    @Autowired
    private TenantOrderMapper tenantOrderMapper;

    @Autowired
    private MemorySqlLogStorageProvider sqlLogStorageProvider;

    @Autowired
    private MemoryDataChangeAuditProvider dataChangeAuditProvider;

    @Autowired
    private SampleSlowSqlAlertProvider slowSqlAlertProvider;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DataChangeAuditContextHolder.clear();
        sqlLogStorageProvider.clear();
        dataChangeAuditProvider.clear();
        slowSqlAlertProvider.clear();
        jdbcTemplate.execute("DELETE FROM product_sku");
        jdbcTemplate.execute("DELETE FROM task_queue");
        jdbcTemplate.execute("DELETE FROM tenant_order");
        jdbcTemplate.execute("DELETE FROM sys_user");
    }

    // =========================================================================
    // 1. 原生高性能批处理、流式游标、并发锁与全表防卫 Controller 测试
    // =========================================================================

    @Test
    @DisplayName("1. 验证 BatchUtil 原生单条多值批量插入与自愈补偿")
    void shouldPerformNativeBatchInsertViaController() throws Exception {
        List<BatchSkuDTO> dtoList = List.of(
                new BatchSkuDTO("SKU_CTRL_001", "机械键盘 87键", new BigDecimal("399.00")),
                new BatchSkuDTO("SKU_CTRL_002", "无线机械鼠标", new BigDecimal("199.00")),
                new BatchSkuDTO("SKU_CTRL_003", "4K Type-C 显示器", new BigDecimal("1899.00"))
        );

        mockMvc.perform(post("/sample/mybatis-plus/high-perf/batch/insert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dtoList)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].id", notNullValue()))
                .andExpect(jsonPath("$.data[0].skuCode", equalTo("SKU_CTRL_001")))
                .andExpect(jsonPath("$.data[1].skuCode", equalTo("SKU_CTRL_002")))
                .andExpect(jsonPath("$.data[2].skuCode", equalTo("SKU_CTRL_003")));

        assertThat(productSkuMapper.selectCount(null)).isEqualTo(3);
    }

    @Test
    @DisplayName("2. 验证双方言原生批量 Upsert 与 @ConflictKeys 原地更新")
    void shouldPerformNativeBatchUpsertViaController() throws Exception {
        // 先插入初始数据
        ProductSkuEntity existing = new ProductSkuEntity("SKU_UPSERT_001", "初始旧商品名称");
        existing.setPrice(new BigDecimal("100.00"));
        productSkuMapper.insert(existing);

        // 构造 Upsert 列表：SKU_UPSERT_001 原地更新名称与价格，SKU_UPSERT_002 全新插入
        List<BatchSkuDTO> dtoList = List.of(
                new BatchSkuDTO("SKU_UPSERT_001", "已原地更新名称", new BigDecimal("150.00")),
                new BatchSkuDTO("SKU_UPSERT_002", "全新插入商品", new BigDecimal("250.00"))
        );

        mockMvc.perform(post("/sample/mybatis-plus/high-perf/batch/upsert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(dtoList)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", greaterThanOrEqualTo(2)));

        ProductSkuEntity updated = productSkuMapper.selectById(existing.getId());
        assertThat(updated.getSkuName()).isEqualTo("已原地更新名称");
        assertThat(updated.getPrice()).isEqualByComparingTo("150.00");

        assertThat(productSkuMapper.selectCount(null)).isEqualTo(2);
    }

    @Test
    @DisplayName("3. 验证主键升序排序防死锁与 CASE WHEN 空值保护批量更新")
    void shouldPerformAntiDeadlockBatchUpdateWithCaseWhenNullProtection() throws Exception {
        // 先插入 3 条数据
        ProductSkuEntity s1 = new ProductSkuEntity("SKU_UP_001", "商品1");
        s1.setPrice(new BigDecimal("10.00"));
        s1.setCategory("原有类目1");
        ProductSkuEntity s2 = new ProductSkuEntity("SKU_UP_002", "商品2");
        s2.setPrice(new BigDecimal("20.00"));
        s2.setCategory("原有类目2");
        ProductSkuEntity s3 = new ProductSkuEntity("SKU_UP_003", "商品3");
        s3.setPrice(new BigDecimal("30.00"));
        s3.setCategory("原有类目3");
        productSkuMapper.insert(s1);
        productSkuMapper.insert(s2);
        productSkuMapper.insert(s3);

        // 构造乱序更新请求：故意将 id 大的排在前面（s3 -> s1 -> s2），且 category 为 null
        BatchSkuDTO u3 = new BatchSkuDTO("SKU_UP_003", "已改商品3", new BigDecimal("33.00"));
        u3.setId(s3.getId());
        u3.setCategory(null); // null 字段测试 CASE WHEN 空值保护

        BatchSkuDTO u1 = new BatchSkuDTO("SKU_UP_001", "已改商品1", new BigDecimal("11.00"));
        u1.setId(s1.getId());
        u1.setCategory(null);

        BatchSkuDTO u2 = new BatchSkuDTO("SKU_UP_002", "已改商品2", new BigDecimal("22.00"));
        u2.setId(s2.getId());
        u2.setCategory(null);

        List<BatchSkuDTO> updateList = List.of(u3, u1, u2);

        mockMvc.perform(post("/sample/mybatis-plus/high-perf/batch/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(updateList)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(3)));

        // 验证更新成功且原本 category 未被 null 覆盖（CASE WHEN 空值保护有效）
        ProductSkuEntity check1 = productSkuMapper.selectById(s1.getId());
        assertThat(check1.getSkuName()).isEqualTo("已改商品1");
        assertThat(check1.getPrice()).isEqualByComparingTo("11.00");
        assertThat(check1.getCategory()).isEqualTo("原有类目1");
    }

    @Test
    @DisplayName("4. 验证受管游标流式分批导出与 Predicate 提前短路检索")
    void shouldStreamExportAndShortCircuitCursor() throws Exception {
        // 4.1 分批导出测试
        mockMvc.perform(get("/sample/mybatis-plus/high-perf/stream/export")
                        .param("batchSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.totalExported", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.batchCount", greaterThanOrEqualTo(3)));

        // 4.2 forEachWhile 提前短路退出测试（限制拉取 2 条）
        mockMvc.perform(get("/sample/mybatis-plus/high-perf/stream/while")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    @DisplayName("5. 验证乐观锁并发自旋扣减与版本号自增")
    void shouldDeductPriceWithOptimisticLockSpinRetry() throws Exception {
        ProductSkuEntity sku = new ProductSkuEntity("SKU_DEDUCT_001", "自旋重试商品");
        sku.setPrice(new BigDecimal("100.00"));
        sku.setVersion(0);
        productSkuMapper.insert(sku);
        assertThat(sku.getVersion()).isEqualTo(0);

        SkuDeductDTO deductDTO = new SkuDeductDTO(sku.getId(), new BigDecimal("15.50"));

        mockMvc.perform(post("/sample/mybatis-plus/high-perf/lock/optimistic/deduct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(deductDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.price", equalTo(84.5)))
                .andExpect(jsonPath("$.data.version", equalTo(1)));
    }

    @Test
    @DisplayName("6. 验证 LockMode.SKIP_LOCKED 悲观排队跳锁抢单与 BlockAttack 防全表更新拦截")
    void shouldGrabTasksWithSkipLockedAndBlockFullTableAttack() throws Exception {
        // 6.1 悲观排队跳锁抢单
        mockMvc.perform(post("/sample/mybatis-plus/high-perf/lock/pessimistic/grab")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].status", equalTo("PROCESSING")));

        // 6.2 BlockAttack 全表更新拦截
        mockMvc.perform(post("/sample/mybatis-plus/high-perf/security/block-attack"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.blocked", equalTo(true)))
                .andExpect(jsonPath("$.data.protectionEngine", equalTo("BlockAttackInnerInterceptor")));

        // 6.3 getOneForUpdate 单对象条件悲观锁查询
        jdbcTemplate.execute("INSERT INTO task_queue (id, task_name, status, version) VALUES (999999, '独立锁定任务', 'PENDING', 0)");
        mockMvc.perform(get("/sample/mybatis-plus/high-perf/lock/pessimistic/one")
                        .param("taskName", "独立锁定任务"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.taskName", equalTo("独立锁定任务")))
                .andExpect(jsonPath("$.data.status", equalTo("PENDING")));

        // 6.4 getByIdForUpdate 与 listForUpdate 强类型 VO 投影端点验证
        mockMvc.perform(get("/sample/mybatis-plus/high-perf/lock/pessimistic/{id}", 999999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.taskName", equalTo("独立锁定任务")))
                .andExpect(jsonPath("$.data.status", equalTo("PENDING")));

        mockMvc.perform(get("/sample/mybatis-plus/high-perf/lock/pessimistic/list")
                        .param("status", "PENDING")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    // =========================================================================
    // 2. 持久层全链路可观测、SQL 诊断、慢 SQL 与数据变更 Diff 审计 Controller 测试
    // =========================================================================

    @Test
    @DisplayName("7. 验证带参可执行 SQL 审计存储与 @IgnoreSqlLog 心跳豁免")
    void shouldCaptureSqlLogsAndExemptHeartbeat() throws Exception {
        // 7.1 先触发一次正常业务查询以生成带参 SQL 审计日志
        productSkuMapper.selectList(null);

        mockMvc.perform(get("/sample/mybatis-plus/observability/sql-log/recent")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", notNullValue()));

        // 7.2 验证 @IgnoreSqlLog 心跳探测排除
        mockMvc.perform(get("/sample/mybatis-plus/observability/sql-log/ignore-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.probeValue", equalTo(1)))
                .andExpect(jsonPath("$.data.ignoredFromAuditLog", equalTo(true)));
    }

    @Test
    @DisplayName("8. 验证慢 SQL 敏感凭据脱敏与告警事件查询")
    void shouldTriggerSlowSqlAndQueryAlertEvents() throws Exception {
        // 8.1 触发带密码参数的慢 SQL 拦截脱敏查询
        mockMvc.perform(get("/sample/mybatis-plus/observability/slow-sql/trigger")
                        .param("username", "admin")
                        .param("password", "p@ssw0rd!Secure"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)));

        // 8.2 查询慢 SQL 事件列表
        mockMvc.perform(get("/sample/mybatis-plus/observability/slow-sql/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)));
    }

    @Test
    @DisplayName("9. 验证 @AuditChange 订单数据变更 Diff 审计与 runWithoutAudit 闭包豁免")
    void shouldAuditOrderDataChangeAndBypassViaClosure() throws Exception {
        // 预置初始订单
        TenantContextHolder.setTenantId("tenant_audit");
        TenantOrderEntity order = new TenantOrderEntity("ORD_TEST_AUDIT", "初始待审计标题", "SKU_A01", new BigDecimal("100.00"), "CREATED");
        order.setTenantId("tenant_audit");
        tenantOrderMapper.insert(order);
        dataChangeAuditProvider.clear();

        // 9.1 执行带审计的订单更新，断言捕获到字段级 Diff（orderTitle 与 amount）
        OrderAuditUpdateDTO updateDTO = new OrderAuditUpdateDTO(order.getId(), "已更新的审计标题", new BigDecimal("250.00"), "PAID");

        mockMvc.perform(post("/sample/mybatis-plus/observability/audit-change/update-order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(updateDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].tableName", equalTo("tenant_order")))
                .andExpect(jsonPath("$.data[0].diffs", notNullValue()));

        assertThat(dataChangeAuditProvider.getRecords()).hasSize(1);

        // 9.2 在 runWithoutAudit 闭包下更新，断言零新增审计记录
        OrderAuditUpdateDTO noAuditDTO = new OrderAuditUpdateDTO(order.getId(), "闭包豁免标题", new BigDecimal("300.00"), "COMPLETED");

        mockMvc.perform(post("/sample/mybatis-plus/observability/audit-change/without-audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(noAuditDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.bypassed", equalTo(true)));

        // 审计记录数依然为 1，说明闭包更新完全被豁免
        assertThat(dataChangeAuditProvider.getRecords()).hasSize(1);

        // 9.3 查询全量审计记录接口
        mockMvc.perform(get("/sample/mybatis-plus/observability/audit-change/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)));
    }
}
