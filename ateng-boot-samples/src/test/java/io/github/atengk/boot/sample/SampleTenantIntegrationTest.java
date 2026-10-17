package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.sample.entity.SysDictEntity;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mapper.SysDictMapper;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多租户行级安全隔离与自动填充端到端集成测试
 * 覆盖自动填充、物理字段校验、行级查询/更新/删除隔离、@IgnoreTenant 注解短路、编程式闭包与 ignore-tables 白名单全生命周期
 *
 * @author Ateng
 * @since 2026-10-16
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.tenant.enabled=true",
        "ateng.boot.mybatis-plus.tenant.column=tenant_id",
        "ateng.boot.mybatis-plus.tenant.ignore-tables=sys_dict,sys_user,sys_user_role"
})
@DisplayName("多租户行级数据安全隔离端到端集成测试")
class SampleTenantIntegrationTest {

    @Autowired
    private TenantOrderMapper tenantOrderMapper;

    @Autowired
    private SysDictMapper sysDictMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void clean() {
        TenantContextHolder.clear();
        // 直接使用 jdbcTemplate 清理测试表数据，避免触发 BlockAttackInnerInterceptor 防全表删除拦截
        jdbcTemplate.execute("DELETE FROM sys_tenant_order");
        jdbcTemplate.execute("DELETE FROM sys_dict");
    }


    @Test
    @DisplayName("1. 插入自动填充：上下文存在租户 1001 时，实体未设租户自动安全填充，物理数据库写入正确租户 ID")
    void shouldAutoFillTenantIdOnInsert() {
        TenantContextHolder.setTenantId(1001L);

        TenantOrderEntity order = new TenantOrderEntity();
        order.setOrderNo("ORD-1001-01");
        order.setAmount(new BigDecimal("199.00"));

        tenantOrderMapper.insert(order);

        // 实体内存对象自动填充当前租户 ID
        assertThat(order.getTenantId()).isEqualTo("1001");
        assertThat(order.getId()).isNotNull();

        // 探查底层 H2 数据库物理字段，断言数据库列值确实为 '1001'
        Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT tenant_id, order_no FROM sys_tenant_order WHERE id = ?", order.getId()
        );
        assertThat(rawDbRow.get("tenant_id")).isEqualTo("1001");
        assertThat(rawDbRow.get("order_no")).isEqualTo("ORD-1001-01");
    }

    @Test
    @DisplayName("2. 显式赋值保护：当开发者显式指定租户 ID 时，自动填充尊重显式值不予覆盖")
    void shouldPreserveExplicitTenantIdOnInsert() {
        TenantContextHolder.setTenantId(1001L);

        TenantOrderEntity order = new TenantOrderEntity();
        order.setOrderNo("ORD-SPEC-01");
        order.setAmount(new BigDecimal("888.00"));
        order.setTenantId("special_tenant_999");

        tenantOrderMapper.insert(order);

        // 显式指定的值被保留
        assertThat(order.getTenantId()).isEqualTo("special_tenant_999");

        Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT tenant_id FROM sys_tenant_order WHERE id = ?", order.getId()
        );
        assertThat(rawDbRow.get("tenant_id")).isEqualTo("special_tenant_999");
    }

    @Test
    @DisplayName("3. 行级查询隔离：租户 1001 只能查询自身记录，无法窥探租户 1002 的数据")
    void shouldIsolateQueryBetweenTenants() {
        // 1. 在租户 1001 下写入 2 条数据
        TenantContextHolder.runWithTenant(1001L, () -> {
            TenantOrderEntity o1 = new TenantOrderEntity();
            o1.setOrderNo("T1001-A");
            o1.setAmount(BigDecimal.TEN);
            tenantOrderMapper.insert(o1);

            TenantOrderEntity o2 = new TenantOrderEntity();
            o2.setOrderNo("T1001-B");
            o2.setAmount(BigDecimal.ONE);
            tenantOrderMapper.insert(o2);
        });

        // 2. 在租户 1002 下写入 1 条数据
        TenantContextHolder.runWithTenant(1002L, () -> {
            TenantOrderEntity o3 = new TenantOrderEntity();
            o3.setOrderNo("T1002-A");
            o3.setAmount(BigDecimal.valueOf(50));
            tenantOrderMapper.insert(o3);
        });

        // 3. 在租户 1001 上下文中查询列表：严格只返回 2 条 1001 数据
        TenantContextHolder.setTenantId(1001L);
        List<TenantOrderEntity> list1001 = tenantOrderMapper.selectList(new LambdaQueryWrapper<>());
        assertThat(list1001).hasSize(2);
        assertThat(list1001).allMatch(o -> "1001".equals(o.getTenantId()));

        // 4. 切换至租户 1002 上下文查询：严格只返回 1 条 1002 数据，杜绝数据穿透
        TenantContextHolder.setTenantId(1002L);
        List<TenantOrderEntity> list1002 = tenantOrderMapper.selectList(new LambdaQueryWrapper<>());
        assertThat(list1002).hasSize(1);
        assertThat(list1002.get(0).getOrderNo()).isEqualTo("T1002-A");
        assertThat(list1002.get(0).getTenantId()).isEqualTo("1002");
    }

    @Test
    @DisplayName("4. 越权防范：租户 1002 通过 selectById、updateById 与 deleteById 操作租户 1001 数据被行级拦截")
    void shouldPreventCrossTenantModificationAndAccess() {
        TenantOrderEntity order1001 = new TenantOrderEntity();
        order1001.setOrderNo("SECRET-1001");
        order1001.setAmount(BigDecimal.valueOf(9999));
        TenantContextHolder.runWithTenant(1001L, () -> tenantOrderMapper.insert(order1001));

        Long orderId = order1001.getId();

        // 切换到租户 1002 尝试越权访问
        TenantContextHolder.setTenantId(1002L);

        // 4.1 越权查单：底层追加 WHERE tenant_id = 1002，返回 null
        TenantOrderEntity leaked = tenantOrderMapper.selectById(orderId);
        assertThat(leaked).isNull();

        // 4.2 越权修改：影响行数为 0
        TenantOrderEntity maliciousUpdate = new TenantOrderEntity();
        maliciousUpdate.setId(orderId);
        maliciousUpdate.setOrderNo("HACKED-BY-1002");
        int updatedRows = tenantOrderMapper.updateById(maliciousUpdate);
        assertThat(updatedRows).isEqualTo(0);

        // 4.3 越权删除：影响行数为 0
        int deletedRows = tenantOrderMapper.deleteById(orderId);
        assertThat(deletedRows).isEqualTo(0);

        // 4.4 校验租户 1001 数据毫发无损
        TenantContextHolder.setTenantId(1001L);
        TenantOrderEntity intact = tenantOrderMapper.selectById(orderId);
        assertThat(intact).isNotNull();
        assertThat(intact.getOrderNo()).isEqualTo("SECRET-1001");
    }

    @Test
    @DisplayName("5. 注解忽略：标注 @IgnoreTenant 的 Mapper 方法跳过租户过滤，支持跨租户统计汇总")
    void shouldBypassTenantFilterWithIgnoreTenantAnnotation() {
        TenantContextHolder.runWithTenant(1001L, () -> {
            TenantOrderEntity o1 = new TenantOrderEntity();
            o1.setOrderNo("ALL-01");
            tenantOrderMapper.insert(o1);
        });
        TenantContextHolder.runWithTenant(1002L, () -> {
            TenantOrderEntity o2 = new TenantOrderEntity();
            o2.setOrderNo("ALL-02");
            tenantOrderMapper.insert(o2);
        });

        // 当前处于租户 1002 上下文
        TenantContextHolder.setTenantId(1002L);

        // 普通查询仅查到 1 条
        assertThat(tenantOrderMapper.selectList(new LambdaQueryWrapper<>())).hasSize(1);

        // 调用标注 @IgnoreTenant 的特权方法，查询出全量 2 条跨租户数据
        List<TenantOrderEntity> allOrders = tenantOrderMapper.selectAllWithIgnoreTenant();
        assertThat(allOrders).hasSize(2);
    }

    @Test
    @DisplayName("6. 编程式忽略：TenantContextHolder.runWithoutTenant 闭包内部跳过租户过滤，退出闭包后自动恢复隔离")
    void shouldBypassTenantFilterWithProgrammaticClosure() {
        TenantContextHolder.runWithTenant(1001L, () -> {
            TenantOrderEntity o1 = new TenantOrderEntity();
            o1.setOrderNo("CLOSURE-01");
            tenantOrderMapper.insert(o1);
        });
        TenantContextHolder.runWithTenant(1002L, () -> {
            TenantOrderEntity o2 = new TenantOrderEntity();
            o2.setOrderNo("CLOSURE-02");
            tenantOrderMapper.insert(o2);
        });

        TenantContextHolder.setTenantId(1001L);

        // 进入闭包前受租户隔离保护，仅查出 1 条
        assertThat(tenantOrderMapper.selectList(new LambdaQueryWrapper<>())).hasSize(1);

        // 闭包内跳过租户过滤，查出全量 2 条数据
        TenantContextHolder.runWithoutTenant(() -> {
            List<TenantOrderEntity> all = tenantOrderMapper.selectList(new LambdaQueryWrapper<>());
            assertThat(all).hasSize(2);
        });

        // 退出闭包后严格恢复租户 1001 隔离保护，恢复为 1 条
        assertThat(tenantOrderMapper.selectList(new LambdaQueryWrapper<>())).hasSize(1);
    }

    @Test
    @DisplayName("7. 白名单忽略表：ignore-tables 配置的 sys_dict 表在任何租户或无租户环境下均免除租户过滤")
    void shouldIgnoreWhitelistedTables() {
        // 在租户 1001 下写入字典数据
        TenantContextHolder.setTenantId(1001L);
        SysDictEntity dict = new SysDictEntity();
        dict.setDictKey("theme_color");
        dict.setDictValue("deep_blue");
        sysDictMapper.insert(dict);

        // 切换至租户 1002 依然能正常读取该字典数据（因为表在 ignore-tables 白名单中）
        TenantContextHolder.setTenantId(1002L);
        SysDictEntity reloaded = sysDictMapper.selectById(dict.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getDictKey()).isEqualTo("theme_color");
        assertThat(reloaded.getDictValue()).isEqualTo("deep_blue");

        // 在无租户上下文下同样可正常读取
        TenantContextHolder.clear();
        SysDictEntity noTenantReloaded = sysDictMapper.selectById(dict.getId());
        assertThat(noTenantReloaded).isNotNull();
    }

    @Test
    @DisplayName("8. 异步线程透传：结合 AsyncContext 在异步线程中准确继承租户隔离与忽略标记")
    void shouldPropagateTenantContextAcrossAsyncThreads() throws Exception {
        TenantContextHolder.runWithTenant(1001L, () -> {
            TenantOrderEntity o1 = new TenantOrderEntity();
            o1.setOrderNo("ASYNC-01");
            tenantOrderMapper.insert(o1);
        });
        TenantContextHolder.runWithTenant(1002L, () -> {
            TenantOrderEntity o2 = new TenantOrderEntity();
            o2.setOrderNo("ASYNC-02");
            tenantOrderMapper.insert(o2);
        });

        TenantContextHolder.setTenantId(1001L);

        // 使用 AsyncContext 包装异步执行任务
        CompletableFuture<List<TenantOrderEntity>> asyncFuture = CompletableFuture.supplyAsync(
                AsyncContext.wrapSupplier(() -> tenantOrderMapper.selectList(new LambdaQueryWrapper<>()))
        );

        // 验证异步子线程精准隔离在 1001 租户下，查询仅返回 1 条数据
        List<TenantOrderEntity> asyncOrders = asyncFuture.get();
        assertThat(asyncOrders).hasSize(1);
        assertThat(asyncOrders.get(0).getTenantId()).isEqualTo("1001");

        // 在忽略租户环境下异步查询，验证异步子线程继承忽略标记，查询返回全量 2 条
        TenantContextHolder.runWithoutTenant(() -> {
            CompletableFuture<List<TenantOrderEntity>> ignoreFuture = CompletableFuture.supplyAsync(
                    AsyncContext.wrapSupplier(() -> tenantOrderMapper.selectList(new LambdaQueryWrapper<>()))
            );
            try {
                List<TenantOrderEntity> allOrders = ignoreFuture.get();
                assertThat(allOrders).hasSize(2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
