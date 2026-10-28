package io.github.atengk.boot.mybatisplus.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.annotation.AuditChange;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.properties.AuditChangeProperties;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据变更 Diff 审计拦截器全维单元测试与 H2 集成测试
 * 覆盖：
 * 1. 三级开关治理（全局配置关闭放行、缺 Provider/Event 自动休眠、编程式闭包 runWithoutAudit 跳过）
 * 2. 声明式注解 @AuditChange 细粒度过滤（includeFields 包含、excludeFields 排除）
 * 3. updateById 精准字段级 Diff 计算与前后快照捕获
 * 4. deleteById 物理删除前置快照捕获
 * 5. 超限批量更新自动降级熔断（max-diff-rows 防 OOM 守卫）
 * 6. 事务提交后回调（afterCommit 触发投递，事务回滚绝不产生幻影审计记录）
 * 7. Spring DataChangeEvent 事件发布与消费验证
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("数据变更 Diff 审计拦截器测试")
class DataChangeAuditInterceptorTest {

    // ==================== 测试实体与 Mapper ====================

    @Getter
    @Setter
    @ToString
    @TableName("t_audit_user")
    @AuditChange(description = "用户核心资料变动")
    static class AuditUserEntity {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String username;
        private String email;
        private Integer age;
        private String status;
    }

    interface AuditUserMapper extends AtengBaseMapper<AuditUserEntity> {
    }

    @Getter
    @Setter
    @ToString
    @TableName("t_audit_field_filtered")
    @AuditChange(includeFields = {"email"}, excludeFields = {"age"})
    static class FilteredAuditUserEntity {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String username;
        private String email;
        private Integer age;
    }

    interface FilteredAuditUserMapper extends AtengBaseMapper<FilteredAuditUserEntity> {
    }

    @Getter
    @Setter
    @ToString
    @TableName("t_no_audit_user")
    static class NoAuditUserEntity {
        @TableId(type = IdType.INPUT)
        private Long id;
        private String username;
    }

    interface NoAuditUserMapper extends AtengBaseMapper<NoAuditUserEntity> {
    }

    private JdbcDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DataChangeAuditContextHolder.clear();

        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:test_audit_db;DB_CLOSE_DELAY=-1;MODE=MySQL");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_audit_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64),
                    email VARCHAR(128),
                    age INT,
                    status VARCHAR(32)
                )
                """);
            stmt.execute("DELETE FROM t_audit_user");
            stmt.execute("INSERT INTO t_audit_user VALUES (1, 'Alice', 'alice@test.com', 20, 'ACTIVE')");
            stmt.execute("INSERT INTO t_audit_user VALUES (2, 'Bob', 'bob@test.com', 25, 'ACTIVE')");
            stmt.execute("INSERT INTO t_audit_user VALUES (3, 'Charlie', 'charlie@test.com', 30, 'LOCKED')");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_audit_field_filtered (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64),
                    email VARCHAR(128),
                    age INT
                )
                """);
            stmt.execute("DELETE FROM t_audit_field_filtered");
            stmt.execute("INSERT INTO t_audit_field_filtered VALUES (10, 'FilterUser', 'filter@test.com', 18)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_no_audit_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64)
                )
                """);
            stmt.execute("DELETE FROM t_no_audit_user");
            stmt.execute("INSERT INTO t_no_audit_user VALUES (100, 'NoAudit')");
        }
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        DataChangeAuditContextHolder.clear();
    }

    // ==================== 第一部分：updateById 精准字段 Diff 测试 ====================

    @Test
    @DisplayName("1. 精准字段 Diff：updateById 仅比对修改字段，未修改字段不入 diffs，捕获完整前后快照与上下文")
    void shouldCapturePreciseFieldDiffOnUpdateById() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false); // 同步执行，便于确定性断言

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, AuditUserMapper.class, AuditUserEntity.class);

        UserContextHolder.set("admin_ateng");
        TenantContextHolder.setTenantId("tenant_001");

        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);

            // 修改 username 与 age，email 传原值，status 传 null（未修改）
            AuditUserEntity updateParam = new AuditUserEntity();
            updateParam.setId(1L);
            updateParam.setUsername("Alice_Updated");
            updateParam.setEmail("alice@test.com"); // 值无变动
            updateParam.setAge(22);                 // 20 -> 22
            updateParam.setStatus(null);            // null 表示不更新

            int rows = mapper.updateById(updateParam);
            assertThat(rows).isEqualTo(1);
        }

        assertThat(receivedRecords).hasSize(1);
        DataChangeRecord record = receivedRecords.get(0);

        assertThat(record.getTableName()).isEqualTo("t_audit_user");
        assertThat(record.getEntityClass()).isEqualTo(AuditUserEntity.class.getName());
        assertThat(record.getEntityId()).isEqualTo(1L);
        assertThat(record.getChangeType()).isEqualTo(ChangeType.UPDATE);
        assertThat(record.getOperator()).isEqualTo("admin_ateng");
        assertThat(record.getTenantId()).isEqualTo("tenant_001");
        assertThat(record.getDescription()).isEqualTo("用户核心资料变动");
        assertThat(record.isDegraded()).isFalse();

        // 验证 Diff 集合：只有 username 和 age 两个字段被记录为变更
        assertThat(record.getDiffs()).hasSize(2);
        FieldDiff usernameDiff = record.getDiffs().stream().filter(d -> "username".equals(d.getPropertyName())).findFirst().orElseThrow();
        assertThat(usernameDiff.getOldValue()).isEqualTo("Alice");
        assertThat(usernameDiff.getNewValue()).isEqualTo("Alice_Updated");
        assertThat(usernameDiff.getColumnName()).isEqualTo("username");

        FieldDiff ageDiff = record.getDiffs().stream().filter(d -> "age".equals(d.getPropertyName())).findFirst().orElseThrow();
        assertThat(ageDiff.getOldValue()).isEqualTo(20);
        assertThat(ageDiff.getNewValue()).isEqualTo(22);

        // 验证前置快照包含变更前的完整状态
        assertThat(record.getBeforeSnapshot())
                .containsEntry("username", "Alice")
                .containsEntry("email", "alice@test.com")
                .containsEntry("age", 20)
                .containsEntry("status", "ACTIVE");

        // 验证后置快照包含变更后的新值
        assertThat(record.getAfterSnapshot())
                .containsEntry("username", "Alice_Updated")
                .containsEntry("age", 22);
    }

    // ==================== 第二部分：deleteById 物理删除审计 ====================

    @Test
    @DisplayName("2. 物理删除审计：deleteById 捕获目标行删除前完整快照，变更类型为 DELETE")
    void shouldCaptureSnapshotOnDeleteById() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, AuditUserMapper.class, AuditUserEntity.class);

        UserContextHolder.set("deleter_bob");

        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);
            int rows = mapper.deleteById(2L);
            assertThat(rows).isEqualTo(1);
        }

        assertThat(receivedRecords).hasSize(1);
        DataChangeRecord record = receivedRecords.get(0);
        assertThat(record.getTableName()).isEqualTo("t_audit_user");
        assertThat(record.getEntityClass()).isEqualTo(AuditUserEntity.class.getName());
        assertThat(record.getEntityId()).isEqualTo(2L);
        assertThat(record.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(record.getOperator()).isEqualTo("deleter_bob");
        assertThat(record.getDiffs()).isEmpty();
        assertThat(record.getBeforeSnapshot())
                .containsEntry("username", "Bob")
                .containsEntry("email", "bob@test.com")
                .containsEntry("status", "ACTIVE");
    }

    // ==================== 第三部分：属性包含与排除过滤 ====================

    @Test
    @DisplayName("3. 属性过滤控制：遵循 @AuditChange 的 includeFields 与 excludeFields，排除未指定或被排除的属性")
    void shouldRespectIncludeAndExcludeFields() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, FilteredAuditUserMapper.class, FilteredAuditUserEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            FilteredAuditUserMapper mapper = session.getMapper(FilteredAuditUserMapper.class);

            FilteredAuditUserEntity param = new FilteredAuditUserEntity();
            param.setId(10L);
            param.setUsername("FilterUser_New"); // 未在 includeFields 中 -> 忽略
            param.setEmail("filter_new@test.com"); // 在 includeFields 中 -> 记录
            param.setAge(19);                     // 在 excludeFields 中 -> 忽略

            mapper.updateById(param);
        }

        assertThat(receivedRecords).hasSize(1);
        DataChangeRecord record = receivedRecords.get(0);
        // 只有 email 进入 diffs 列表
        assertThat(record.getDiffs()).hasSize(1);
        assertThat(record.getDiffs().get(0).getPropertyName()).isEqualTo("email");
        assertThat(record.getDiffs().get(0).getOldValue()).isEqualTo("filter@test.com");
        assertThat(record.getDiffs().get(0).getNewValue()).isEqualTo("filter_new@test.com");
    }

    // ==================== 第四部分：超限批量更新降级熔断 ====================

    @Test
    @DisplayName("4. 防爆熔断守护：当批量更新行数超过 maxDiffRows 时，自动熔断降级为摘要记录，不逐行计算 Diff")
    void shouldDegradeToSummaryWhenExceedingMaxDiffRows() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);
        properties.setMaxDiffRows(2); // 设置最大阈值为 2 行

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, AuditUserMapper.class, AuditUserEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);

            // 批量更新 3 条记录（3 > 2，触发超限熔断降级）
            AuditUserEntity u1 = new AuditUserEntity();
            u1.setId(1L);
            u1.setUsername("A1");
            AuditUserEntity u2 = new AuditUserEntity();
            u2.setId(2L);
            u2.setUsername("B2");
            AuditUserEntity u3 = new AuditUserEntity();
            u3.setId(3L);
            u3.setUsername("C3");

            int rows = mapper.updateBatchSomeColumn(List.of(u1, u2, u3));
            assertThat(rows).isGreaterThanOrEqualTo(3);
        }

        assertThat(receivedRecords).hasSize(1);
        DataChangeRecord record = receivedRecords.get(0);
        assertThat(record.isDegraded()).isTrue();
        assertThat(record.getTableName()).isEqualTo("t_audit_user");
        assertThat(record.getAffectedRows()).isGreaterThanOrEqualTo(3);
        assertThat(record.getDiffs()).isEmpty();
        assertThat(record.getDescription()).contains("超限熔断降级");
    }

    // ==================== 第五部分：三级开关与休眠治理 ====================

    @Test
    @DisplayName("5. 全局开关与缺 Provider 自动休眠：enabled=false 或缺 SPI/Event 时，拦截器直接放行，不产生任何快照与调用")
    void shouldSleepWhenDisabledOrNoProvider() {
        // A. 全局 enabled=false
        List<DataChangeRecord> records1 = new CopyOnWriteArrayList<>();
        AuditChangeProperties propDisabled = new AuditChangeProperties();
        propDisabled.setEnabled(false);
        SqlSessionFactory sf1 = buildSessionFactory(dataSource, propDisabled, records1::addAll, null, AuditUserMapper.class, AuditUserEntity.class);

        try (SqlSession session = sf1.openSession(true)) {
            AuditUserEntity u = new AuditUserEntity();
            u.setId(1L);
            u.setUsername("Alice_Disabled");
            session.getMapper(AuditUserMapper.class).updateById(u);
        }
        assertThat(records1).isEmpty();

        // B. 缺 Provider 且 publishEvent=false（自动休眠）
        AuditChangeProperties propNoProvider = new AuditChangeProperties();
        propNoProvider.setEnabled(true);
        propNoProvider.setPublishEvent(false);
        SqlSessionFactory sf2 = buildSessionFactory(dataSource, propNoProvider, null, null, AuditUserMapper.class, AuditUserEntity.class);

        try (SqlSession session = sf2.openSession(true)) {
            AuditUserEntity u = new AuditUserEntity();
            u.setId(1L);
            u.setUsername("Alice_NoProvider");
            session.getMapper(AuditUserMapper.class).updateById(u);
        }
        // 自动休眠安全放行，无任何报错
    }

    @Test
    @DisplayName("6. 编程式闭包治理：DataChangeAuditContextHolder.runWithoutAudit 与 callWithoutAudit 线程安全跳过审计")
    void shouldSkipAuditWithContextHolder() throws Exception {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, AuditUserMapper.class, AuditUserEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);

            // 1. 在 runWithoutAudit 闭包中执行更新 -> 绝不生成审计记录
            DataChangeAuditContextHolder.runWithoutAudit(() -> {
                AuditUserEntity u = new AuditUserEntity();
                u.setId(1L);
                u.setUsername("Alice_InClosure");
                mapper.updateById(u);
            });
            assertThat(receivedRecords).isEmpty();

            // 2. 在 callWithoutAudit 闭包中执行更新 -> 绝不生成审计记录
            Integer updatedRows = DataChangeAuditContextHolder.callWithoutAudit(() -> {
                AuditUserEntity u = new AuditUserEntity();
                u.setId(1L);
                u.setUsername("Alice_InCallable");
                return mapper.updateById(u);
            });
            assertThat(updatedRows).isEqualTo(1);
            assertThat(receivedRecords).isEmpty();

            // 3. 闭包结束后现场自动恢复 -> 后续正常执行更新生成审计记录
            AuditUserEntity u = new AuditUserEntity();
            u.setId(1L);
            u.setUsername("Alice_AfterClosure");
            mapper.updateById(u);

            assertThat(receivedRecords).hasSize(1);
            assertThat(receivedRecords.get(0).getDiffs().get(0).getNewValue()).isEqualTo("Alice_AfterClosure");
        }
    }

    @Test
    @DisplayName("7. 无注解放行：未标注 @AuditChange 的实体与 Mapper 绝不触发审计逻辑")
    void shouldSkipAuditWhenNoAnnotation() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, NoAuditUserMapper.class, NoAuditUserEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            NoAuditUserMapper mapper = session.getMapper(NoAuditUserMapper.class);
            NoAuditUserEntity u = new NoAuditUserEntity();
            u.setId(100L);
            u.setUsername("NoAudit_Changed");
            mapper.updateById(u);
        }

        assertThat(receivedRecords).isEmpty();
    }

    // ==================== 第六部分：Spring Event 事件发布 ====================

    @Test
    @DisplayName("8. Spring Event 事件集成：配置 publishEvent=true 时向 Spring 容器广播 DataChangeEvent")
    void shouldPublishDataChangeEvent() {
        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);
        properties.setPublishEvent(true);

        List<DataChangeEvent> publishedEvents = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher eventPublisher = event -> {
            if (event instanceof DataChangeEvent dce) {
                publishedEvents.add(dce);
            }
        };

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, null, eventPublisher, AuditUserMapper.class, AuditUserEntity.class);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);
            AuditUserEntity u = new AuditUserEntity();
            u.setId(1L);
            u.setUsername("Alice_EventTest");
            mapper.updateById(u);
        }

        assertThat(publishedEvents).hasSize(1);
        assertThat(publishedEvents.get(0).getRecords()).hasSize(1);
        assertThat(publishedEvents.get(0).getRecords().get(0).getEntityId()).isEqualTo(1L);
    }

    // ==================== 第七部分：事务提交与回滚保障 ====================

    @Test
    @DisplayName("9. 事务提交与回滚守护：事务提交后触发投递，事务回滚时绝对不产生幻影审计记录")
    void shouldDispatchOnlyAfterTransactionCommit() {
        List<DataChangeRecord> receivedRecords = new CopyOnWriteArrayList<>();
        DataChangeAuditProvider provider = receivedRecords::addAll;

        AuditChangeProperties properties = new AuditChangeProperties();
        properties.setEnabled(true);
        properties.setAsync(false);

        SqlSessionFactory sessionFactory = buildSessionFactory(dataSource, properties, provider, null, AuditUserMapper.class, AuditUserEntity.class);

        // A. 模拟事务回滚场景
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (SqlSession session = sessionFactory.openSession(false)) {
                AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);
                AuditUserEntity u = new AuditUserEntity();
                u.setId(1L);
                u.setUsername("Alice_Rollback");
                mapper.updateById(u);
            }
            // 此时尚未提交，receivedRecords 必为空
            assertThat(receivedRecords).isEmpty();

            // 模拟事务回滚触发 afterCompletion(STATUS_ROLLED_BACK)
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            // 回滚后仍然为空
            assertThat(receivedRecords).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        // B. 模拟事务提交成功场景
        TransactionSynchronizationManager.initSynchronization();
        try {
            try (SqlSession session = sessionFactory.openSession(false)) {
                AuditUserMapper mapper = session.getMapper(AuditUserMapper.class);
                AuditUserEntity u = new AuditUserEntity();
                u.setId(1L);
                u.setUsername("Alice_Committed");
                mapper.updateById(u);
            }
            // 此时尚未提交，receivedRecords 仍为空
            assertThat(receivedRecords).isEmpty();

            // 模拟事务提交触发 afterCommit
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            // 提交后成功收到审计记录
            assertThat(receivedRecords).hasSize(1);
            assertThat(receivedRecords.get(0).getDiffs().get(0).getNewValue()).isEqualTo("Alice_Committed");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==================== 辅助测试脚手架 ====================

    private SqlSessionFactory buildSessionFactory(DataSource ds,
                                                  AuditChangeProperties auditProps,
                                                  DataChangeAuditProvider provider,
                                                  ApplicationEventPublisher eventPublisher,
                                                  Class<?> mapperClass,
                                                  Class<?> modelClass) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), ds);
        configuration.setEnvironment(environment);

        GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(configuration);
        globalConfig.setSqlInjector(new AtengSqlInjector());
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "resource");
        assistant.setCurrentNamespace(mapperClass.getName());
        TableInfo tableInfo = TableInfoHelper.initTableInfo(assistant, modelClass);

        AtengSqlInjector injector = new AtengSqlInjector();
        injector.inspectInject(assistant, mapperClass);

        configuration.addMapper(mapperClass);

        // 注册 DataChangeAuditInterceptor
        DataChangeAuditInterceptor interceptor = new DataChangeAuditInterceptor(
                auditProps,
                provider,
                null,
                eventPublisher,
                "SYSTEM"
        );
        configuration.addInterceptor(interceptor);

        return new MybatisSqlSessionFactoryBuilder().build(configuration);
    }
}
