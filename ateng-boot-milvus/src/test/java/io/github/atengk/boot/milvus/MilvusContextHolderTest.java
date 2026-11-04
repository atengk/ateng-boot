package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.context.MilvusContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

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
}
