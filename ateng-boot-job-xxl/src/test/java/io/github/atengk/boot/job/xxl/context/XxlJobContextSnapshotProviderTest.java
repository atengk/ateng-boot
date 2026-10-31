package io.github.atengk.boot.job.xxl.context;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.AsyncContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XXL-Job 异步上下文传播 SPI 扩展单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class XxlJobContextSnapshotProviderTest {

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
    }

    @Test
    @DisplayName("测试 XxlJobContext 通过 AsyncContext.wrap() 在异步线程池中精准传播并执行后安全还原")
    void testXxlJobContextAsyncPropagation() throws Exception {
        // 创建独立线程池并预热，模拟受管线程池常驻空闲工作线程初始状态
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {}).get(1, TimeUnit.SECONDS);

            // 1. 父线程初始化 XxlJobContext
            XxlJobContext parentContext = new XxlJobContext(901L, "param-test", 9001L, System.currentTimeMillis(), "test.log", 0, 1);
            XxlJobContext.setXxlJobContext(parentContext);

            AtomicReference<XxlJobContext> capturedInWorker = new AtomicReference<>();
            AtomicReference<XxlJobContext> workerAfterRun = new AtomicReference<>();

            Runnable wrappedTask = AsyncContext.wrap(() -> {
                // 在异步子任务内捕获当前线程持有的 XxlJobContext
                capturedInWorker.set(XxlJobContext.getXxlJobContext());
            });

            // 2. 模拟由受管线程池中的常驻工作线程执行
            Future<?> future = pool.submit(() -> {
                wrappedTask.run();
                // 验证任务完成后工作线程现场是否已被恢复（无残留）
                workerAfterRun.set(XxlJobContext.getXxlJobContext());
            });

            future.get(2, TimeUnit.SECONDS);

            // 3. 断言子线程成功继承父线程的 XxlJobContext
            assertThat(capturedInWorker.get()).isNotNull();
            assertThat(capturedInWorker.get().getJobId()).isEqualTo(901L);
            assertThat(capturedInWorker.get().getJobParam()).isEqualTo("param-test");

            // 4. 断言执行完毕后子线程已被彻底清空现场，杜绝线程池污染
            assertThat(workerAfterRun.get()).isNull();

            // 5. 断言父线程原生上下文完好无损
            assertThat(XxlJobContext.getXxlJobContext()).isSameAs(parentContext);
        } finally {
            pool.shutdownNow();
        }
    }

}
