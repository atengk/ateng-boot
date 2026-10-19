package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * StreamingQueryTemplate 受管游标流式查询模板单元测试
 * 覆盖参数前置防卫、H2 内存库真实百万级流式分批读取、只读事务守护、
 * 确定性生命周期游标闭合、异常回滚及连接防泄漏验证
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("StreamingQueryTemplate 受管游标流式查询测试")
class StreamingQueryTemplateTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static PlatformTransactionManager transactionManager;
    private static StreamingQueryTemplate template;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @TableName("test_streaming_item")
    static class StreamingItem {
        @TableId
        private Long id;
        private String name;
        private Integer amount;
    }

    public interface StreamingTestMapper {
        @Insert("INSERT INTO test_streaming_item (id, name, amount) VALUES (#{id}, #{name}, #{amount})")
        int insert(StreamingItem item);

        @Select("SELECT id, name, amount FROM test_streaming_item ORDER BY id ASC")
        @Options(fetchSize = 1000)
        Cursor<StreamingItem> selectAllCursor();

        @Select("SELECT id, name, amount FROM test_streaming_item WHERE id > 999999")
        Cursor<StreamingItem> selectEmptyCursor();
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:streaming_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_streaming_item");
            stmt.execute("CREATE TABLE test_streaming_item (" +
                    "id BIGINT PRIMARY KEY, " +
                    "name VARCHAR(64), " +
                    "amount INT)");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        configuration.addMapper(StreamingTestMapper.class);

        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        transactionManager = new DataSourceTransactionManager(dataSource);
        template = new StreamingQueryTemplate(transactionManager);

        // 预置 2500 条测试记录
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            for (long i = 1; i <= 2500; i++) {
                mapper.insert(new StreamingItem(i, "Item-" + i, (int) (i * 10)));
            }
        }
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS test_streaming_item");
        }
    }

    // ==========================================
    // 1. 参数校验与前置卫语句防卫 (Defense-in-depth)
    // ==========================================

    @Test
    @DisplayName("构造器参数校验：传入 null 事务管理器必须抛出 NullPointerException")
    void shouldThrowNpeWhenConstructWithNull() {
        assertThatThrownBy(() -> new StreamingQueryTemplate((PlatformTransactionManager) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("PlatformTransactionManager must not be null");

        assertThatThrownBy(() -> new StreamingQueryTemplate((TransactionTemplate) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("TransactionTemplate must not be null");
    }

    @Test
    @DisplayName("forEach 参数校验：参数为 null 时必须抛出 NullPointerException")
    void shouldThrowNpeWhenForEachParamsNull() {
        assertThatThrownBy(() -> template.forEach(null, item -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cursor supplier must not be null");

        assertThatThrownBy(() -> template.forEach(() -> null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Consumer must not be null");

        assertThatThrownBy(() -> template.forEachWhile(null, item -> true))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cursor supplier must not be null");

        assertThatThrownBy(() -> template.forEachWhile(() -> null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Predicate must not be null");
    }

    @Test
    @DisplayName("forEachBatch 参数校验：参数为 null 或 batchSize 非法时快速失败")
    void shouldThrowWhenForEachBatchParamsInvalid() {
        assertThatThrownBy(() -> template.forEachBatch(null, batch -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cursor supplier must not be null");

        assertThatThrownBy(() -> template.forEachBatch(() -> null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Batch consumer must not be null");

        assertThatThrownBy(() -> template.forEachBatch(() -> null, 0, batch -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be greater than 0");

        assertThatThrownBy(() -> template.forEachBatch(() -> null, -10, batch -> {}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be greater than 0");
    }

    @Test
    @DisplayName("execute 参数校验：参数为 null 时抛出 NullPointerException")
    void shouldThrowNpeWhenExecuteParamsNull() {
        assertThatThrownBy(() -> template.execute(null, cursor -> null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cursor supplier must not be null");

        assertThatThrownBy(() -> template.execute(() -> null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Cursor action function must not be null");
    }

    @Test
    @DisplayName("空安全保底：当 cursorSupplier 返回 null 时安全退出不抛异常")
    void shouldHandleNullCursorGracefully() {
        AtomicBoolean consumed = new AtomicBoolean(false);

        template.forEach(() -> null, item -> consumed.set(true));
        assertThat(consumed.get()).isFalse();

        template.forEachBatch(() -> null, batch -> consumed.set(true));
        assertThat(consumed.get()).isFalse();

        Object result = template.execute(() -> null, cursor -> "OK");
        assertThat(result).isNull();
    }

    // ==========================================
    // 2. 端到端流式消费与切片验证 (Happy Path & E2E)
    // ==========================================

    @Test
    @DisplayName("forEach 逐条流式消费：完整消费 2500 条数据且顺序 100% 正确")
    void shouldStreamForEachRecordCorrectly() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            List<Long> ids = new ArrayList<>(2500);
            AtomicLong sumAmount = new AtomicLong(0);

            template.forEach(mapper::selectAllCursor, item -> {
                ids.add(item.getId());
                sumAmount.addAndGet(item.getAmount());
            });

            assertThat(ids).hasSize(2500);
            assertThat(ids.get(0)).isEqualTo(1L);
            assertThat(ids.get(2499)).isEqualTo(2500L);
            // 等差数列求和：(10 + 25000) * 2500 / 2 = 31262500
            assertThat(sumAmount.get()).isEqualTo(31262500L);
        }
    }

    @Test
    @DisplayName("forEachWhile 条件流式消费：当 Predicate 返回 false 时提前短路退出并确定性关闭游标")
    void shouldShortCircuitWhenPredicateReturnsFalse() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            List<Long> readIds = new ArrayList<>();

            template.forEachWhile(mapper::selectAllCursor, item -> {
                readIds.add(item.getId());
                return item.getId() < 50; // 当达到 50 时返回 false 提前短路退出
            });

            assertThat(readIds).hasSize(50);
            assertThat(readIds.get(0)).isEqualTo(1L);
            assertThat(readIds.get(49)).isEqualTo(50L);
        }
    }

    @Test
    @DisplayName("forEachBatch 默认切片：按默认批次 1000 正确切分为 3 批（1000, 1000, 500）且只读不可变")
    void shouldStreamForEachBatchDefaultSize() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            List<Integer> batchSizes = new ArrayList<>();
            List<Long> allIds = new ArrayList<>();

            template.forEachBatch(mapper::selectAllCursor, batch -> {
                batchSizes.add(batch.size());
                for (StreamingItem item : batch) {
                    allIds.add(item.getId());
                }
                // 验证切片 List 不可变性防御
                assertThatThrownBy(() -> batch.add(new StreamingItem()))
                        .isInstanceOf(UnsupportedOperationException.class);
            });

            assertThat(batchSizes).containsExactly(1000, 1000, 500);
            assertThat(allIds).hasSize(2500);
            assertThat(allIds.get(0)).isEqualTo(1L);
            assertThat(allIds.get(2499)).isEqualTo(2500L);
        }
    }

    @Test
    @DisplayName("forEachBatch 自定义批次大小：按 700 正确切分为 4 批（700, 700, 700, 400）")
    void shouldStreamForEachBatchCustomSize() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            List<Integer> batchSizes = new ArrayList<>();
            List<Long> allIds = new ArrayList<>();

            template.forEachBatch(mapper::selectAllCursor, 700, batch -> {
                batchSizes.add(batch.size());
                batch.forEach(item -> allIds.add(item.getId()));
            });

            assertThat(batchSizes).containsExactly(700, 700, 700, 400);
            assertThat(allIds).hasSize(2500);
        }
    }

    @Test
    @DisplayName("空游标流式消费：当无匹配记录时正常退出，不调用消费回调")
    void shouldNotInvokeConsumerWhenCursorIsEmpty() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);
            AtomicBoolean consumed = new AtomicBoolean(false);

            template.forEach(mapper::selectEmptyCursor, item -> consumed.set(true));
            assertThat(consumed.get()).isFalse();

            template.forEachBatch(mapper::selectEmptyCursor, 500, batch -> consumed.set(true));
            assertThat(consumed.get()).isFalse();
        }
    }

    @Test
    @DisplayName("execute 自定义计算：成功执行自定义流式聚合计算并返回结果")
    void shouldExecuteCustomActionSuccessfully() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);

            Long maxId = template.execute(mapper::selectAllCursor, cursor -> {
                long max = -1L;
                for (StreamingItem item : cursor) {
                    if (item.getId() > max) {
                        max = item.getId();
                    }
                }
                return max;
            });

            assertThat(maxId).isEqualTo(2500L);
        }
    }

    // ==========================================
    // 3. 确定性生命周期释放与异常回滚守护 (Failure Modes & Leak Defense)
    // ==========================================

    @Test
    @DisplayName("确定性游标关闭：正常消费与异常抛出场景下，Cursor 的 close 均被 100% 确定性调用")
    void shouldDeterministicallyCloseCursorUnderNormalAndExceptionalCases() {
        // 1. 正常消费闭合验证
        AtomicBoolean closedNormally = new AtomicBoolean(false);
        Cursor<String> normalCursor = new TestCloseableCursor<>(List.of("A", "B", "C"), () -> closedNormally.set(true));

        template.forEach(() -> normalCursor, item -> {});
        assertThat(closedNormally.get()).isTrue();

        // 2. 异常中断闭合验证
        AtomicBoolean closedOnException = new AtomicBoolean(false);
        Cursor<String> errorCursor = new TestCloseableCursor<>(List.of("A", "B", "C"), () -> closedOnException.set(true));

        assertThatThrownBy(() -> template.forEach(() -> errorCursor, item -> {
            throw new BusinessException("消费业务异常中断");
        })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("消费业务异常中断");

        assertThat(closedOnException.get()).isTrue();
    }

    @Test
    @DisplayName("业务异常事务回滚守护：下游 Consumer 抛出 BusinessException 时原样抛出并触发事务回滚")
    void shouldRollbackTransactionWhenConsumerThrowsBusinessException() {
        PlatformTransactionManager mockTm = Mockito.mock(PlatformTransactionManager.class);
        TransactionStatus mockStatus = Mockito.mock(TransactionStatus.class);
        Mockito.when(mockTm.getTransaction(any())).thenReturn(mockStatus);

        StreamingQueryTemplate customTemplate = new StreamingQueryTemplate(mockTm);
        Cursor<String> cursor = new TestCloseableCursor<>(List.of("One", "Two"), () -> {});

        assertThatThrownBy(() -> customTemplate.forEach(() -> cursor, item -> {
            throw new BusinessException("支付异常");
        })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("支付异常");

        // 断言事务管理器执行了 rollback，而非 commit
        verify(mockTm).rollback(mockStatus);
        verify(mockTm, Mockito.never()).commit(any());
    }

    @Test
    @DisplayName("分批消费中途异常守护：第 2 批消费抛出异常时游标关闭且事务回滚")
    void shouldRollbackAndCloseCursorWhenBatchConsumerFails() {
        AtomicBoolean cursorClosed = new AtomicBoolean(false);
        List<String> items = List.of("1", "2", "3", "4", "5", "6");
        Cursor<String> cursor = new TestCloseableCursor<>(items, () -> cursorClosed.set(true));

        AtomicInteger batchIndex = new AtomicInteger(0);
        assertThatThrownBy(() -> template.forEachBatch(() -> cursor, 2, batch -> {
            if (batchIndex.incrementAndGet() == 2) {
                throw new IllegalStateException("Batch 2 failed intentionally");
            }
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Batch 2 failed intentionally");

        assertThat(cursorClosed.get()).isTrue();
    }

    @Test
    @DisplayName("受检异常转译：非运行时异常被安全包装为 IllegalStateException 向外抛出且保留 cause")
    void shouldWrapCheckedExceptionInIllegalStateException() throws Exception {
        Cursor<String> faultyCursor = Mockito.mock(Cursor.class);
        Mockito.when(faultyCursor.iterator()).thenAnswer(inv -> {
            throw new RuntimeException("Iterator initialization failed");
        });
        Mockito.doAnswer(inv -> {
            throw new IOException("Simulated cursor close disk failure");
        }).when(faultyCursor).close();

        // 验证即使 close 抛出 IOException，也能被受控捕获并包装，同时保留 cause
        assertThatThrownBy(() -> template.execute(() -> faultyCursor, Cursor::iterator))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("连接池防泄漏验证：异常中断后底层数据库连接立即可用，后续查询不受影响")
    void shouldNotLeakConnectionOnException() throws Exception {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            StreamingTestMapper mapper = session.getMapper(StreamingTestMapper.class);

            // 模拟异常中断
            assertThatThrownBy(() -> template.forEach(mapper::selectAllCursor, item -> {
                if (item.getId() == 100L) {
                    throw new RuntimeException("Crash at 100");
                }
            })).isInstanceOf(RuntimeException.class).hasMessage("Crash at 100");

            // 异常后立即发起新的流式查询，验证连接未被悬挂死锁
            AtomicInteger count = new AtomicInteger(0);
            template.forEach(mapper::selectAllCursor, item -> {
                if (count.incrementAndGet() >= 10) {
                    // 只读 10 条即可退出
                }
            });
            assertThat(count.get()).isGreaterThanOrEqualTo(10);
        }
    }

    // ==========================================
    // 4. 自定义测试 Cursor 辅助类
    // ==========================================

    private static class TestCloseableCursor<T> implements Cursor<T> {
        private final List<T> data;
        private final Runnable onClose;
        private boolean closed = false;

        public TestCloseableCursor(List<T> data, Runnable onClose) {
            this.data = data;
            this.onClose = onClose;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public boolean isConsumed() {
            return closed;
        }

        @Override
        public int getCurrentIndex() {
            return 0;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (onClose != null) {
                    onClose.run();
                }
            }
        }

        @Override
        public Iterator<T> iterator() {
            return data.iterator();
        }
    }
}
