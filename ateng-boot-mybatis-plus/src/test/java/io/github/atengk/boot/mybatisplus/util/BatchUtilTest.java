package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;
import io.github.atengk.boot.mybatisplus.entity.BaseTimeEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchUtil 原生批量插入门面工具类测试
 * 覆盖空集合与 null Mapper 防卫、切片分批、双轨制自愈补偿（Fast-Path + 反射元数据缓存）、
 * 内存对象主键回填、以及基于 H2 内存库的端到端批量插入自愈零 null 入库验证
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("BatchUtil 批量工具类与智能自愈引擎测试")
class BatchUtilTest {

    static class MockEntity {
        private final int id;
        public MockEntity(int id) { this.id = id; }
        public int getId() { return id; }
    }

    @Getter
    @Setter
    static class CustomAssignIdEntity {
        @TableId(type = IdType.ASSIGN_ID)
        private Long id;
        private String name;
    }

    @Getter
    @Setter
    static class CustomStringAssignIdEntity {
        @TableId(type = IdType.ASSIGN_ID)
        private String id;
        private String name;
    }

    @Getter
    @Setter
    static class CustomAutoIdEntity {
        @TableId(type = IdType.AUTO)
        private Long id;
        private String name;
    }

    @Getter
    @Setter
    static class TestFullAuditEntity extends BaseFullEntity implements TenantEntity {
        private String tenantId;
        private String title;
    }

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_batch_heal_order")
    static class BatchHealOrderEntity extends BaseDeleteIdEntity implements TenantEntity {
        private String orderNo;
        private String tenantId;
    }

    interface BatchHealOrderMapper extends AtengBaseMapper<BatchHealOrderEntity> {
    }

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initH2Database() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_batch_heal;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_batch_heal_order (
                    id BIGINT PRIMARY KEY,
                    order_no VARCHAR(64) NOT NULL,
                    tenant_id VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP NOT NULL,
                    update_time TIMESTAMP NOT NULL,
                    create_by VARCHAR(64) NOT NULL,
                    update_by VARCHAR(64) NOT NULL,
                    version INT NOT NULL,
                    deleted BIGINT DEFAULT 0 NOT NULL
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(BatchHealOrderMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_batch_heal_order");
        }
    }

    @BeforeEach
    @AfterEach
    void cleanUpContext() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        BatchUtil.setCurrentUserProvider(null);
    }

    @Test
    @DisplayName("1. 空集合防御：传入 null 或 empty 集合直接返回 0，不触发 Mapper 调用")
    void shouldReturnZeroForEmptyCollection() {
        @SuppressWarnings("unchecked")
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);

        assertThat(BatchUtil.insertBatch(mockMapper, null)).isEqualTo(0);
        assertThat(BatchUtil.insertBatch(mockMapper, Collections.emptyList())).isEqualTo(0);

        verify(mockMapper, never()).insertBatchSomeColumn(any());
    }

    @Test
    @DisplayName("2. null Mapper 防御：传入 null mapper 抛出 IllegalArgumentException")
    void shouldThrowExceptionWhenMapperIsNull() {
        assertThatThrownBy(() -> BatchUtil.insertBatch(null, List.of(new MockEntity(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapper must not be null");
    }

    @Test
    @DisplayName("3. 智能分批切片：2500 条数据、批次 1000 时，精准切为 1000+1000+500 三批执行")
    void shouldChunkCorrectlyWithCustomBatchSize() {
        List<MockEntity> data = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            data.add(new MockEntity(i));
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.insertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<MockEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.insertBatch(mockMapper, data, 1000);

        assertThat(affected).isEqualTo(2500);
        assertThat(batchSizes).containsExactly(1000, 1000, 500);
    }

    @Test
    @DisplayName("4. 兜底默认批次：当传入 batchSize <= 0 时，自动采用默认值 1000")
    void shouldFallbackToDefaultSizeWhenBatchSizeIsInvalid() {
        List<MockEntity> data = new ArrayList<>();
        for (int i = 1; i <= 1500; i++) {
            data.add(new MockEntity(i));
        }

        List<Integer> batchSizes = new ArrayList<>();
        @SuppressWarnings("unchecked")
        AtengBaseMapper<MockEntity> mockMapper = Mockito.mock(AtengBaseMapper.class);
        when(mockMapper.insertBatchSomeColumn(any())).thenAnswer(invocation -> {
            Collection<MockEntity> chunk = invocation.getArgument(0);
            batchSizes.add(chunk.size());
            return chunk.size();
        });

        int affected = BatchUtil.insertBatch(mockMapper, data, -1);

        assertThat(affected).isEqualTo(1500);
        assertThat(batchSizes).containsExactly(1000, 500);
    }

    @Test
    @DisplayName("5. Fast-Path 主键自愈：继承 BaseIdEntity 的实体在 id 为空时自动分配雪花 ID，保留显式赋值")
    void shouldAutoHealBaseIdEntityPrimaryKey() {
        TestFullAuditEntity entity1 = new TestFullAuditEntity();
        assertThat(entity1.getId()).isNull();

        TestFullAuditEntity entity2 = new TestFullAuditEntity();
        entity2.setId(999999L);

        BatchUtil.autoHealAll(List.of(entity1, entity2));

        assertThat(entity1.getId()).isNotNull().isPositive();
        assertThat(entity2.getId()).isEqualTo(999999L);
    }

    @Test
    @DisplayName("6. 反射通道主键自愈：非基类普通实体标有 ASSIGN_ID 时自动分配雪花 ID，AUTO 时安全跳过")
    void shouldAutoHealCustomEntityPrimaryKeyBasedOnAnnotation() {
        CustomAssignIdEntity customAssign = new CustomAssignIdEntity();
        CustomStringAssignIdEntity customString = new CustomStringAssignIdEntity();
        CustomAutoIdEntity customAuto = new CustomAutoIdEntity();

        BatchUtil.autoHeal(customAssign);
        BatchUtil.autoHeal(customString);
        BatchUtil.autoHeal(customAuto);

        // ASSIGN_ID Long 与 String 均自动发号补齐
        assertThat(customAssign.getId()).isNotNull().isPositive();
        assertThat(customString.getId()).isNotNull().isNotBlank();

        // AUTO 自增主键跳过发号，交由数据库自增
        assertThat(customAuto.getId()).isNull();
    }

    @Test
    @DisplayName("7. 审计时间与操作人自愈：空字段自动补全当前时间与操作人，优先 SPI 其次上下文最后默认 SYSTEM")
    void shouldAutoHealAuditTimestampsAndOperators() {
        // 场景 A: 无 SPI 与上下文时，默认 SYSTEM 兜底
        TestFullAuditEntity entityA = new TestFullAuditEntity();
        BatchUtil.autoHeal(entityA);
        assertThat(entityA.getCreateTime()).isNotNull();
        assertThat(entityA.getUpdateTime()).isNotNull();
        assertThat(entityA.getCreateBy()).isEqualTo("SYSTEM");
        assertThat(entityA.getUpdateBy()).isEqualTo("SYSTEM");

        // 场景 B: 存在 UserContextHolder 上下文
        UserContextHolder.set("operator-alice");
        TestFullAuditEntity entityB = new TestFullAuditEntity();
        BatchUtil.autoHeal(entityB);
        assertThat(entityB.getCreateBy()).isEqualTo("operator-alice");
        assertThat(entityB.getUpdateBy()).isEqualTo("operator-alice");

        // 场景 C: 配置 CurrentUserProvider SPI 优先于上下文
        BatchUtil.setCurrentUserProvider(() -> "spi-bob");
        TestFullAuditEntity entityC = new TestFullAuditEntity();
        BatchUtil.autoHeal(entityC);
        assertThat(entityC.getCreateBy()).isEqualTo("spi-bob");
        assertThat(entityC.getUpdateBy()).isEqualTo("spi-bob");

        // 场景 D: 开发者显式赋值被绝对保留
        TestFullAuditEntity entityD = new TestFullAuditEntity();
        LocalDateTime customTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        entityD.setCreateTime(customTime);
        entityD.setCreateBy("explicit-author");
        BatchUtil.autoHeal(entityD);
        assertThat(entityD.getCreateTime()).isEqualTo(customTime);
        assertThat(entityD.getCreateBy()).isEqualTo("explicit-author");
    }

    @Test
    @DisplayName("8. 多租户与状态字段自愈：TenantEntity 上下文租户注入、BaseFullEntity 与 BaseDeleteIdEntity 默认值注入")
    void shouldAutoHealTenantIdAndStatusFlags() {
        // 上下文无租户时保持 null
        TestFullAuditEntity fullEntity = new TestFullAuditEntity();
        BatchUtil.autoHeal(fullEntity);
        assertThat(fullEntity.getTenantId()).isNull();
        assertThat(fullEntity.getVersion()).isEqualTo(1);
        assertThat(fullEntity.getDeleted()).isEqualTo(0);

        // 设置上下文租户
        TenantContextHolder.setTenantId("tenant_corp_100");
        TestFullAuditEntity fullEntityWithTenant = new TestFullAuditEntity();
        BatchUtil.autoHeal(fullEntityWithTenant);
        assertThat(fullEntityWithTenant.getTenantId()).isEqualTo("tenant_corp_100");

        // BaseDeleteIdEntity 默认 version=1, deleted=0L
        BatchHealOrderEntity deleteIdEntity = new BatchHealOrderEntity();
        BatchUtil.autoHeal(deleteIdEntity);
        assertThat(deleteIdEntity.getVersion()).isEqualTo(1);
        assertThat(deleteIdEntity.getDeleted()).isEqualTo(0L);
        assertThat(deleteIdEntity.getTenantId()).isEqualTo("tenant_corp_100");
    }

    @Test
    @DisplayName("9. 端到端 H2 批量插入自愈全字段零 null 验证：2500 条数据自动切片自愈入库，主键原地回填可访问")
    void shouldEndToEndBatchInsertWithCompleteAutoHealingInH2() throws Exception {
        TenantContextHolder.setTenantId("tenant_batch_e2e");
        UserContextHolder.set("admin_batch_user");

        int totalCount = 2500;
        List<BatchHealOrderEntity> orders = new ArrayList<>(totalCount);
        for (int i = 1; i <= totalCount; i++) {
            BatchHealOrderEntity order = new BatchHealOrderEntity();
            order.setOrderNo("ORD-" + i);
            // 故意不设置 id, tenantId, createTime, updateTime, createBy, updateBy, version, deleted
            orders.add(order);
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            BatchHealOrderMapper mapper = session.getMapper(BatchHealOrderMapper.class);

            // 执行原生批量分批切片插入（每批 1000 条）
            int affectedRows = BatchUtil.insertBatch(mapper, orders, 1000);
            assertThat(affectedRows).isEqualTo(totalCount);
        }

        // 1. 验证入参内存对象：主键 ID 已原地回填且全部非空
        assertThat(orders).allMatch(o -> o.getId() != null && o.getId() > 0);
        assertThat(orders).allMatch(o -> "tenant_batch_e2e".equals(o.getTenantId()));
        assertThat(orders).allMatch(o -> "admin_batch_user".equals(o.getCreateBy()));
        assertThat(orders).allMatch(o -> "admin_batch_user".equals(o.getUpdateBy()));
        assertThat(orders).allMatch(o -> o.getCreateTime() != null);
        assertThat(orders).allMatch(o -> o.getUpdateTime() != null);
        assertThat(orders).allMatch(o -> Integer.valueOf(1).equals(o.getVersion()));
        assertThat(orders).allMatch(o -> Long.valueOf(0L).equals(o.getDeleted()));

        // 2. 验证 H2 数据库物理记录：总记录数必须精准为 2500
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM t_batch_heal_order")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(2500L);
            }

            // 核心断言：数据库中绝无任何审计、租户、版本号或逻辑删除为 null 的非法脏数据！
            try (ResultSet rs = stmt.executeQuery("""
                SELECT COUNT(*) FROM t_batch_heal_order
                WHERE id IS NULL
                   OR order_no IS NULL
                   OR tenant_id IS NULL
                   OR create_time IS NULL
                   OR update_time IS NULL
                   OR create_by IS NULL
                   OR update_by IS NULL
                   OR version IS NULL
                   OR deleted IS NULL
            """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }
}
