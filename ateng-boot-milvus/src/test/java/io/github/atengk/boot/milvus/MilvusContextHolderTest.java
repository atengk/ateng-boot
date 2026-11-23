package io.github.atengk.boot.milvus;

import io.github.atengk.boot.core.concurrent.NamedThreadFactory;
import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MilvusContextHolder 线程上下文持有者单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusContextHolderTest {

    @BeforeEach
    @AfterEach
    void cleanup() {
        MilvusContextHolder.clear();
    }

    @Test
    @DisplayName("setDatabase & getDatabase - 当前线程动态切库与清理")
    void testSetAndGetDatabase() {
        assertThat(MilvusContextHolder.getDatabase()).isNull();

        MilvusContextHolder.setDatabase("ecommerce_db");
        assertThat(MilvusContextHolder.getDatabase()).isEqualTo("ecommerce_db");

        MilvusContextHolder.clearDatabase();
        assertThat(MilvusContextHolder.getDatabase()).isNull();
    }

    @Test
    @DisplayName("ThreadLocal 隔离性 - 多线程间数据库上下文互不污染")
    void testThreadLocalIsolation() throws InterruptedException {
        MilvusContextHolder.setDatabase("main_db");

        AtomicReference<String> workerDb = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            workerDb.set(MilvusContextHolder.getDatabase());
            MilvusContextHolder.setDatabase("sub_worker_db");
        });
        worker.start();
        worker.join();

        // 子线程初始读取应为 null，子线程修改不影响主线程
        assertThat(workerDb.get()).isNull();
        assertThat(MilvusContextHolder.getDatabase()).isEqualTo("main_db");
    }

    @Test
    @DisplayName("useDatabase(AutoCloseable) - 支持嵌套作用域切库、幂等关闭及异常退出确定性恢复")
    void testUseDatabaseScopeNestedAndExceptionRestore() {
        assertThat(MilvusContextHolder.getDatabase()).isNull();

        try (MilvusContextHolder.DatabaseScope outer = MilvusContextHolder.useDatabase(" db_outer ")) {
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_outer");

            try (MilvusContextHolder.DatabaseScope inner = MilvusContextHolder.useDatabase("db_inner")) {
                assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_inner");
                throw new IllegalStateException("模拟业务异常");
            } catch (IllegalStateException expected) {
                // 内层异常退出后立即恢复外层数据库上下文
                assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_outer");
            }

            // 重复调用 close() 不破坏后续显式修改的上下文
            MilvusContextHolder.DatabaseScope scope = MilvusContextHolder.useDatabase("db_mid");
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_mid");
            scope.close();
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_outer");
            MilvusContextHolder.setDatabase("db_explicit");
            scope.close();
            assertThat(MilvusContextHolder.getDatabase()).isEqualTo("db_explicit");
        }

        assertThat(MilvusContextHolder.getDatabase()).isNull();
    }

    @Test
    @DisplayName("useDatabase - 空白数据库名快速失败且多线程并发切库零串库污染")
    void testUseDatabaseValidationAndConcurrentIsolation() throws InterruptedException {
        assertThatThrownBy(() -> MilvusContextHolder.useDatabase(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MilvusContextHolder.useDatabase("   "))
                .isInstanceOf(IllegalArgumentException.class);

        int threads = 8;
        int rounds = 50;
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(threads);
        pool.setMaxPoolSize(threads);
        pool.setThreadFactory(new NamedThreadFactory("milvus-ctx-test-"));
        pool.initialize();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        AtomicInteger mismatchCount = new AtomicInteger(0);

        try {
            for (int t = 0; t < threads; t++) {
                final String threadDb = "tenant_db_" + t;
                pool.execute(() -> {
                    try {
                        startLatch.await();
                        for (int r = 0; r < rounds; r++) {
                            try (MilvusContextHolder.DatabaseScope ignored = MilvusContextHolder.useDatabase(threadDb)) {
                                if (!threadDb.equals(MilvusContextHolder.getDatabase())) {
                                    mismatchCount.incrementAndGet();
                                }
                                String nestedDb = threadDb + "_nested";
                                try (MilvusContextHolder.DatabaseScope nested = MilvusContextHolder.useDatabase(nestedDb)) {
                                    if (!nestedDb.equals(MilvusContextHolder.getDatabase())) {
                                        mismatchCount.incrementAndGet();
                                    }
                                }
                                if (!threadDb.equals(MilvusContextHolder.getDatabase())) {
                                    mismatchCount.incrementAndGet();
                                }
                            }
                            if (MilvusContextHolder.getDatabase() != null) {
                                mismatchCount.incrementAndGet();
                            }
                        }
                    } catch (Throwable ex) {
                        mismatchCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }
            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(mismatchCount.get()).isZero();
        } finally {
            pool.shutdown();
        }
    }
}
