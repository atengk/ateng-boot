package io.github.atengk.boot.sample.mybatisplus;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.audit.DataChangeRecord;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.OptimisticLockTemplate;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import io.github.atengk.boot.sample.mybatisplus.audit.MemoryDataChangeAuditProvider;
import io.github.atengk.boot.sample.mybatisplus.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.mybatisplus.service.TenantOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 租户订单领域模型（tenant_order）端到端集成自验测试
 * 覆盖：
 * 1. 行级多租户安全隔离与租户 ID 自动填充
 * 2. 声明式 @IgnoreTenant 注解与 runWithoutTenant 闭包绕过
 * 3. 原生单条多值高性能批量插入（insertBatchSomeColumn / BatchUtil.insertBatch）
 * 4. 双方言原生批量 Upsert 增强（upsertBatchSomeColumn / @ConflictKeys）
 * 5. 原生单条批量更新与主键升序排序防死锁引擎（updateBatchSomeColumn / BatchUtil.updateBatch）
 * 6. 乐观锁并发自旋重试模板（OptimisticLockTemplate & updateWithOptimisticRetry）
 * 7. 受管游标流式查询模板（StreamingQueryTemplate 逐条与分批防 OOM）
 * 8. 声明式数据变更 Diff 审计拦截器（@AuditChange 与事务提交后快照捕获）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@DisplayName("租户订单领域模型 (tenant_order) 核心特性集成自验")
class TenantOrderDomainIntegrationTest {

    @Autowired
    private TenantOrderService tenantOrderService;

    @Autowired
    private TenantOrderMapper tenantOrderMapper;

    @Autowired
    private MemoryDataChangeAuditProvider memoryDataChangeAuditProvider;

    @Autowired
    private StreamingQueryTemplate streamingQueryTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanState() {
        TenantContextHolder.clear();
        memoryDataChangeAuditProvider.clear();
        jdbcTemplate.execute("DELETE FROM tenant_order");
    }

    @Test
    @DisplayName("1. 验证行级多租户安全隔离与租户 ID 自动填充")
    void shouldEnforceRowLevelTenantIsolationAndAutoFillTenantId() {
        // 租户 A 上下文
        TenantContextHolder.setTenantId("tenant_A");
        TenantOrderEntity orderA = new TenantOrderEntity("ORD_A_001", "租户A商品订单", "SKU_001", new BigDecimal("99.00"), "CREATED");
        tenantOrderService.save(orderA);
        assertThat(orderA.getTenantId()).isEqualTo("tenant_A");

        // 租户 B 上下文
        TenantContextHolder.setTenantId("tenant_B");
        TenantOrderEntity orderB = new TenantOrderEntity("ORD_B_001", "租户B数码订单", "SKU_002", new BigDecimal("199.00"), "CREATED");
        tenantOrderService.save(orderB);
        assertThat(orderB.getTenantId()).isEqualTo("tenant_B");

        // 在租户 B 视图下只能查询到租户 B 的订单
        List<TenantOrderEntity> listB = tenantOrderService.list();
        assertThat(listB).hasSize(1);
        assertThat(listB.get(0).getOrderNo()).isEqualTo("ORD_B_001");

        // 切换回租户 A 视图
        TenantContextHolder.setTenantId("tenant_A");
        List<TenantOrderEntity> listA = tenantOrderService.list();
        assertThat(listA).hasSize(1);
        assertThat(listA.get(0).getOrderNo()).isEqualTo("ORD_A_001");
    }

    @Test
    @DisplayName("2. 验证 @IgnoreTenant 注解与 runWithoutTenant 编程式闭包绕过行级隔离")
    void shouldBypassTenantIsolationViaIgnoreTenantAnnotationAndClosure() {
        // 预置两个不同租户的订单
        TenantContextHolder.setTenantId("tenant_1");
        tenantOrderService.save(new TenantOrderEntity("ORD_T1", "订单1", "SKU_1", new BigDecimal("50.00"), "CREATED"));

        TenantContextHolder.setTenantId("tenant_2");
        tenantOrderService.save(new TenantOrderEntity("ORD_T2", "订单2", "SKU_2", new BigDecimal("60.00"), "CREATED"));

        // 当前处于租户 2 下
        assertThat(tenantOrderService.list()).hasSize(1);

        // 1. 调用标注 @IgnoreTenant 的 Mapper 方法：直接查出全量数据
        List<TenantOrderEntity> allOrders = tenantOrderMapper.selectAllOrdersIgnoreTenant();
        assertThat(allOrders).hasSize(2)
                .extracting(TenantOrderEntity::getOrderNo)
                .containsExactlyInAnyOrder("ORD_T1", "ORD_T2");

        // 2. 编程式闭包绕过租户隔离
        TenantContextHolder.runWithoutTenant(() -> {
            List<TenantOrderEntity> closureOrders = tenantOrderService.list();
            assertThat(closureOrders).hasSize(2);
        });
    }

    @Test
    @DisplayName("3. 验证原生单条多值批量插入引擎 (insertBatchSomeColumn)")
    void shouldPerformNativeBatchInsert() {
        TenantContextHolder.setTenantId("tenant_batch");

        List<TenantOrderEntity> batchList = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            batchList.add(new TenantOrderEntity("ORD_BATCH_" + i, "批量商品" + i, "SKU_" + i, new BigDecimal("10.00"), "CREATED"));
        }

        int inserted = BatchUtil.insertBatch(tenantOrderMapper, batchList);
        assertThat(inserted).isEqualTo(5);

        List<TenantOrderEntity> loaded = tenantOrderService.list();
        assertThat(loaded).hasSize(5);
    }

    @Test
    @DisplayName("4. 验证双方言原生批量 Upsert 增强与 @ConflictKeys 原地更新")
    void shouldPerformNativeUpsertWithConflictKeys() {
        TenantContextHolder.setTenantId("tenant_upsert");

        // 首次插入初始订单
        TenantOrderEntity initial = new TenantOrderEntity("ORD_UPSERT_KEY", "初始订单标题", "SKU_INIT", new BigDecimal("100.00"), "CREATED");
        tenantOrderService.save(initial);
        Long originId = initial.getId();

        // 模拟批量 Upsert：包含已有冲突键 order_no 的修改项与全新项
        TenantOrderEntity conflictItem = new TenantOrderEntity("ORD_UPSERT_KEY", "已更新订单标题", "SKU_NEW", new BigDecimal("250.00"), "PAID");
        conflictItem.setId(originId); // 携带原 ID
        TenantOrderEntity newItem = new TenantOrderEntity("ORD_UPSERT_NEW", "全新订单标题", "SKU_BRAND_NEW", new BigDecimal("300.00"), "CREATED");

        int affected = BatchUtil.upsertBatch(tenantOrderMapper, List.of(conflictItem, newItem));
        assertThat(affected).isGreaterThanOrEqualTo(2);

        // 验证已有记录原地被更新
        TenantOrderEntity updated = tenantOrderService.getById(originId);
        assertThat(updated.getOrderTitle()).isEqualTo("已更新订单标题");
        assertThat(updated.getSkuCode()).isEqualTo("SKU_NEW");
        assertThat(updated.getAmount()).isEqualByComparingTo("250.00");
        assertThat(updated.getStatus()).isEqualTo("PAID");

        // 验证全新记录被成功插入
        TenantOrderEntity newlyInserted = tenantOrderService.getOne(
                new QueryWrapper<TenantOrderEntity>().eq("order_no", "ORD_UPSERT_NEW"));
        assertThat(newlyInserted).isNotNull();
        assertThat(newlyInserted.getOrderTitle()).isEqualTo("全新订单标题");
    }

    @Test
    @DisplayName("5. 验证原生单条批量更新与主键排序防死锁引擎 (updateBatchSomeColumn)")
    void shouldPerformNativeAntiDeadlockBatchUpdate() {
        TenantContextHolder.setTenantId("tenant_update");

        TenantOrderEntity o1 = new TenantOrderEntity("ORD_UP_1", "待改1", "S1", new BigDecimal("10.00"), "CREATED");
        TenantOrderEntity o2 = new TenantOrderEntity("ORD_UP_2", "待改2", "S2", new BigDecimal("20.00"), "CREATED");
        TenantOrderEntity o3 = new TenantOrderEntity("ORD_UP_3", "待改3", "S3", new BigDecimal("30.00"), "CREATED");
        BatchUtil.insertBatch(tenantOrderMapper, List.of(o1, o2, o3));

        // 乱序传入进行更新，底层由 AntiDeadlockSqlSource 强制按 ID 升序排序生成 CASE WHEN
        o1.setOrderTitle("已改1");
        o1.setAmount(new BigDecimal("11.00"));
        o2.setOrderTitle("已改2");
        o2.setAmount(new BigDecimal("22.00"));
        o3.setOrderTitle("已改3");
        o3.setAmount(new BigDecimal("33.00"));

        List<TenantOrderEntity> updateList = new ArrayList<>(List.of(o3, o1, o2));
        int updated = BatchUtil.updateBatch(tenantOrderMapper, updateList);
        assertThat(updated).isEqualTo(3);

        List<TenantOrderEntity> results = tenantOrderService.list();
        assertThat(results).extracting(TenantOrderEntity::getOrderTitle)
                .containsExactlyInAnyOrder("已改1", "已改2", "已改3");
    }

    @Test
    @DisplayName("6. 验证乐观锁自旋重试模板 (OptimisticLockTemplate & updateWithOptimisticRetry)")
    void shouldRetryOptimisticLockOnConflict() {
        TenantContextHolder.setTenantId("tenant_lock");

        TenantOrderEntity order = new TenantOrderEntity("ORD_LOCK_001", "并发订单", "SKU_LOCK", new BigDecimal("99.00"), "UNPAID");
        tenantOrderService.save(order);
        Long orderId = order.getId();

        // 1. 使用 OptimisticLockTemplate.execute 门面
        TenantOrderEntity paidOrder = OptimisticLockTemplate.execute(
                () -> tenantOrderMapper.selectById(orderId),
                entity -> {
                    entity.setStatus("PAID");
                    return tenantOrderMapper.updateById(entity) > 0;
                }
        );
        assertThat(paidOrder.getStatus()).isEqualTo("PAID");
        assertThat(paidOrder.getVersion()).isEqualTo(1); // 乐观锁版本由 0 升级至 1

        // 2. 使用 AtengBaseService 上的 updateWithOptimisticRetry 语法糖
        TenantOrderEntity deliveredOrder = tenantOrderService.updateWithOptimisticRetry(
                orderId,
                entity -> entity.setStatus("DELIVERED")
        );
        assertThat(deliveredOrder.getStatus()).isEqualTo("DELIVERED");
        assertThat(deliveredOrder.getVersion()).isEqualTo(2); // 乐观锁版本升级至 2
    }

    @Test
    @DisplayName("7. 验证受管游标流式查询模板 (StreamingQueryTemplate) 逐条与分批消费")
    void shouldStreamLargeDataWithStreamingQueryTemplate() {
        TenantContextHolder.setTenantId("tenant_stream");

        List<TenantOrderEntity> streamData = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            streamData.add(new TenantOrderEntity("ORD_STREAM_" + i, "流式商品" + i, "SKU_S" + i, new BigDecimal("1.00"), "CREATED"));
        }
        BatchUtil.insertBatch(tenantOrderMapper, streamData);

        // 1. Service stream 逐条流式消费
        List<String> collectedNos = Collections.synchronizedList(new ArrayList<>());
        tenantOrderService.stream(new QueryWrapper<TenantOrderEntity>().orderByAsc("id"), item -> {
            collectedNos.add(item.getOrderNo());
        });
        assertThat(collectedNos).hasSize(6);

        // 2. StreamingQueryTemplate forEachBatch 分批切片消费（每批 2 条）
        AtomicInteger batchCount = new AtomicInteger(0);
        streamingQueryTemplate.forEachBatch(
                () -> tenantOrderMapper.selectCursor(new QueryWrapper<>()),
                2,
                batch -> {
                    assertThat(batch).hasSizeLessThanOrEqualTo(2);
                    batchCount.incrementAndGet();
                }
        );
        assertThat(batchCount.get()).isEqualTo(3); // 6 条数据分 3 批
    }

    @Test
    @DisplayName("8. 验证声明式数据变更 Diff 审计拦截器 (@AuditChange) 在事务提交后精确捕获")
    void shouldCaptureDataChangeDiffOnTransactionCommit() {
        TenantContextHolder.setTenantId("tenant_audit");

        TenantOrderEntity order = new TenantOrderEntity("ORD_AUDIT_001", "原始订单标题", "SKU_AUDIT", new BigDecimal("500.00"), "CREATED");
        tenantOrderService.save(order);
        memoryDataChangeAuditProvider.clear();

        // 在事务内执行更新操作
        transactionTemplate.execute(status -> {
            TenantOrderEntity toUpdate = tenantOrderService.getById(order.getId());
            toUpdate.setOrderTitle("已变更订单标题");
            toUpdate.setAmount(new BigDecimal("600.00"));
            tenantOrderService.updateById(toUpdate);
            return true;
        });

        // 事务提交后，断言已通过 MemoryDataChangeAuditProvider 精确捕获到字段级 Diff
        List<DataChangeRecord> auditRecords = memoryDataChangeAuditProvider.getRecords();
        assertThat(auditRecords).isNotEmpty();

        DataChangeRecord record = auditRecords.get(0);
        assertThat(record.getTableName()).isEqualToIgnoringCase("tenant_order");
        assertThat(record.getDescription()).isEqualTo("订单变更审计");
        assertThat(record.getDiffs()).isNotEmpty();

        // 断言变更字段的前后快照差异
        boolean hasTitleDiff = record.getDiffs().stream().anyMatch(diff ->
                diff.getPropertyName().equals("orderTitle")
                        && "原始订单标题".equals(diff.getOldValue())
                        && "已变更订单标题".equals(diff.getNewValue())
        );
        assertThat(hasTitleDiff).isTrue();
    }
}
