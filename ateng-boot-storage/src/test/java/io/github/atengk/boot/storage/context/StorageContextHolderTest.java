package io.github.atengk.boot.storage.context;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * StorageContextHolder 函数式作用域闭包与异步上下文快照提供者测试
 *
 * @author Ateng
 * @since 2026-11-14
 */
@DisplayName("StorageContextHolder 作用域与五维异步透传测试")
class StorageContextHolderTest {

    @AfterEach
    void tearDown() {
        StorageContextHolder.clear();
    }

    @Test
    @DisplayName("executeWith 与 runWith 在正常返回及异常抛出时严格恢复外层数据源")
    void testExecuteWithAndRunWithRestoresOuterContextOnSuccessAndException() {
        assertThat(StorageContextHolder.getDataSource()).isNull();

        StorageContextHolder.set("primary-oss");

        String result = StorageContextHolder.executeWith("secondary-s3", () -> {
            assertThat(StorageContextHolder.get()).isEqualTo("secondary-s3");
            assertThat(StorageContextHolder.getDataSource()).isEqualTo("secondary-s3");
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(StorageContextHolder.getDataSource()).isEqualTo("primary-oss");

        assertThatThrownBy(() -> StorageContextHolder.runWith("archive", () -> {
            assertThat(StorageContextHolder.getDataSource()).isEqualTo("archive");
            throw new IllegalStateException("simulated failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated failure");
        assertThat(StorageContextHolder.getDataSource()).isEqualTo("primary-oss");

        StorageContextHolder.clear();
        assertThatThrownBy(() -> StorageContextHolder.executeWith("temp-bucket", () -> {
            assertThat(StorageContextHolder.getDataSource()).isEqualTo("temp-bucket");
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class);
        assertThat(StorageContextHolder.getDataSource()).isNull();
    }

    @Test
    @DisplayName("多层嵌套 executeWith 与 runWith 作用域按栈序准确回滚")
    void testNestedScopesUnwindCleanly() {
        StorageContextHolder.executeWith("level-1", () -> {
            assertThat(StorageContextHolder.getDataSource()).isEqualTo("level-1");
            StorageContextHolder.runWith("level-2", () -> {
                assertThat(StorageContextHolder.getDataSource()).isEqualTo("level-2");
                assertThatThrownBy(() -> StorageContextHolder.executeWith("level-3", () -> {
                    assertThat(StorageContextHolder.getDataSource()).isEqualTo("level-3");
                    throw new IllegalArgumentException("inner error");
                })).isInstanceOf(IllegalArgumentException.class);
                assertThat(StorageContextHolder.getDataSource()).isEqualTo("level-2");
            });
            assertThat(StorageContextHolder.getDataSource()).isEqualTo("level-1");
            return null;
        });

        assertThat(StorageContextHolder.getDataSource()).isNull();
    }

    @Test
    @DisplayName("传入 null 或空白 sourceKey 时平滑回退至 default 数据源，并校验 action 非空")
    void testBlankSourceFallsBackToDefaultAndNullActionFailsFast() {
        StorageContextHolder.set("outer-minio");

        StorageContextHolder.executeWith(null, () -> {
            assertThat(StorageContextHolder.getDataSource()).isEqualTo(StorageContextHolder.DEFAULT_SOURCE);
            return null;
        });
        assertThat(StorageContextHolder.getDataSource()).isEqualTo("outer-minio");

        StorageContextHolder.runWith("   ", () ->
                assertThat(StorageContextHolder.getDataSource()).isEqualTo("default"));
        assertThat(StorageContextHolder.getDataSource()).isEqualTo("outer-minio");

        assertThatThrownBy(() -> StorageContextHolder.executeWith("oss", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> StorageContextHolder.runWith("oss", null))
                .isInstanceOf(NullPointerException.class);
        assertThat(StorageContextHolder.getDataSource()).isEqualTo("outer-minio");
    }

    @Test
    @DisplayName("StorageAsyncContextSnapshotProvider 协同 ContextCopyingTaskDecorator 跨线程透传并清理工作线程")
    void testSnapshotProviderWithContextCopyingTaskDecorator() throws Exception {
        StorageContextHolder.StorageAsyncContextSnapshotProvider provider =
                new StorageContextHolder.StorageAsyncContextSnapshotProvider();
        AsyncContext.registerSnapshotProvider(provider);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.setThreadNamePrefix("storage-ctx-test-");
        executor.initialize();

        try {
            // 1. 正常透传：父线程绑定 oss-public，工作线程获取一致存储源
            StorageContextHolder.set("oss-public");
            AtomicReference<String> capturedInWorker = new AtomicReference<>();
            CompletableFuture.runAsync(
                    () -> capturedInWorker.set(StorageContextHolder.getDataSource()),
                    executor
            ).get(5, TimeUnit.SECONDS);
            assertThat(capturedInWorker.get()).isEqualTo("oss-public");

            // 2. 异常自清理：工作线程任务内主动改写上下文并抛异常，finally 仍将工作线程 ThreadLocal 彻底清零
            StorageContextHolder.set("minio-secure");
            CompletableFuture<Void> failingTask = CompletableFuture.runAsync(() -> {
                StorageContextHolder.set("worker-mutated");
                throw new IllegalStateException("worker failed");
            }, executor);
            assertThatThrownBy(() -> failingTask.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(IllegalStateException.class);

            // 3. 复用同一单线程验证工作线程 ThreadLocal 已彻底清理无残留
            StorageContextHolder.clear();
            AtomicReference<String> afterTaskWorkerContext = new AtomicReference<>("UNSET");
            CompletableFuture.runAsync(
                    () -> afterTaskWorkerContext.set(StorageContextHolder.getDataSource()),
                    executor
            ).get(5, TimeUnit.SECONDS);
            assertThat(afterTaskWorkerContext.get()).isNull();
        } finally {
            AsyncContext.unregisterSnapshotProvider(provider);
            executor.shutdown();
        }
    }
}
