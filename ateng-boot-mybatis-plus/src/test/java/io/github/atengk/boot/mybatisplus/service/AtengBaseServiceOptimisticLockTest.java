package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.OptimisticLockTemplate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtengBaseService 乐观锁高并发自旋重试门面测试 (Issue #129)
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("AtengBaseService 乐观锁自旋重试门面测试")
class AtengBaseServiceOptimisticLockTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession sqlSession;
    private static TestOptimisticItemMapper itemMapper;
    private static TestOptimisticItemService itemService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    @TableName("t_optimistic_service_item")
    static class TestOptimisticItemEntity extends BaseDeleteIdEntity implements Serializable {
        @TableId
        private Long id;
        private String name;
        private Integer balance;
    }

    interface TestOptimisticItemMapper extends AtengBaseMapper<TestOptimisticItemEntity> {
    }

    interface TestOptimisticItemService extends AtengBaseService<TestOptimisticItemEntity> {
    }

    static class TestOptimisticItemServiceImpl extends AtengServiceImpl<TestOptimisticItemMapper, TestOptimisticItemEntity>
            implements TestOptimisticItemService {
        public TestOptimisticItemServiceImpl(TestOptimisticItemMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_optimistic_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_optimistic_service_item");
            stmt.execute("""
                CREATE TABLE t_optimistic_service_item (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    balance INT NOT NULL,
                    create_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by VARCHAR(64),
                    version INT DEFAULT 1,
                    deleted BIGINT DEFAULT 0
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("optimistic_service_test", new JdbcTransactionFactory(), dataSource));
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);

        // 注册乐观锁插件
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(TestOptimisticItemMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);

        itemMapper = sqlSession.getMapper(TestOptimisticItemMapper.class);
        itemService = new TestOptimisticItemServiceImpl(itemMapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @BeforeEach
    void setUpEach() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("TRUNCATE TABLE t_optimistic_service_item");
        }
    }

    @Test
    @DisplayName("1. 首次更新即成功无需重试")
    void shouldUpdateSuccessfullyWithoutRetry() {
        TestOptimisticItemEntity entity = new TestOptimisticItemEntity();
        entity.setId(1L);
        entity.setName("Initial");
        entity.setBalance(100);
        entity.setVersion(1);
        itemService.save(entity);

        TestOptimisticItemEntity updated = itemService.updateWithOptimisticRetry(1L, e -> {
            e.setName("Updated");
            e.setBalance(80);
        });

        assertThat(updated).isNotNull();
        assertThat(updated.getName()).isEqualTo("Updated");
        assertThat(updated.getBalance()).isEqualTo(80);
        assertThat(updated.getVersion()).isEqualTo(2);

        TestOptimisticItemEntity inDb = itemService.getById(1L);
        assertThat(inDb.getName()).isEqualTo("Updated");
        assertThat(inDb.getBalance()).isEqualTo(80);
        assertThat(inDb.getVersion()).isEqualTo(2);
    }

    @Test
    @DisplayName("2. 模拟并发冲突自旋重试并在第 2 次成功重新加载最新快照")
    void shouldRetryAndSucceedWhenConcurrentConflictOccurs() {
        TestOptimisticItemEntity entity = new TestOptimisticItemEntity();
        entity.setId(2L);
        entity.setName("Account-2");
        entity.setBalance(500);
        entity.setVersion(1);
        itemService.save(entity);

        AtomicInteger attempts = new AtomicInteger(0);

        TestOptimisticItemEntity updated = itemService.updateWithOptimisticRetry(2L, e -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                // 模拟并发事务修改该行数据，使版本号递增至 2，余额被外部扣减 100
                try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                    stmt.execute("UPDATE t_optimistic_service_item SET version = 2, balance = 400 WHERE id = 2");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            // 扣减 50
            e.setBalance(e.getBalance() - 50);
        }, 5, Duration.ofMillis(10));

        assertThat(attempts.get()).isEqualTo(2);
        assertThat(updated).isNotNull();
        // 第一次读取时 balance=500，但由于外部修改碰撞导致 updateById 失败（受影响行数 0）
        // 第二次重试读取最新 balance=400，扣减 50 变为 350，版本号由 2 递增为 3
        assertThat(updated.getBalance()).isEqualTo(350);
        assertThat(updated.getVersion()).isEqualTo(3);

        TestOptimisticItemEntity inDb = itemService.getById(2L);
        assertThat(inDb.getBalance()).isEqualTo(350);
        assertThat(inDb.getVersion()).isEqualTo(3);
    }

    @Test
    @DisplayName("3. 模拟并发冲突持续碰撞导致重试超限抛出 OptimisticLockConflictException")
    void shouldThrowOptimisticLockConflictExceptionWhenMaxAttemptsExceeded() {
        TestOptimisticItemEntity entity = new TestOptimisticItemEntity();
        entity.setId(3L);
        entity.setName("Account-3");
        entity.setBalance(1000);
        entity.setVersion(1);
        itemService.save(entity);

        AtomicInteger attempts = new AtomicInteger(0);

        assertThatThrownBy(() -> itemService.updateWithOptimisticRetry(3L, e -> {
            attempts.incrementAndGet();
            // 每次执行 updater 时都在底层并发抢先递增版本号，导致实体持有的版本号始终过期
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("UPDATE t_optimistic_service_item SET version = version + 1 WHERE id = 3");
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
            e.setBalance(e.getBalance() - 10);
        }, 3, Duration.ofMillis(5)))
                .isInstanceOf(OptimisticLockTemplate.OptimisticLockConflictException.class)
                .hasMessageContaining("Optimistic lock conflict detected");

        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("4. 实体不存在时快速失败抛出 IllegalArgumentException")
    void shouldFailFastWhenEntityNotFound() {
        assertThatThrownBy(() -> itemService.updateWithOptimisticRetry(999L, e -> e.setName("NotExist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OptimisticLock loader returned null entity");
    }

    @Test
    @DisplayName("5. 防御性校验：id 或 updater 为 null 时抛出 NPE")
    void shouldThrowNullPointerExceptionOnNullArguments() {
        assertThatThrownBy(() -> itemService.updateWithOptimisticRetry(null, e -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id 不能为 null");

        assertThatThrownBy(() -> itemService.updateWithOptimisticRetry(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updater 不能为 null");
    }
}
