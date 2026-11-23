package io.github.atengk.boot.job.xxl.thread;

import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.openapi.executor.dto.TriggerRequest;
import io.github.atengk.boot.job.xxl.timeout.AdminTimeoutRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengJobThread 受管调度线程单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengJobThreadTest {

    @AfterEach
    void tearDown() {
        AdminTimeoutRegistry.clear();
    }

    @Test
    @DisplayName("测试 pushTriggerQueue 拦截：成功剥离官方超时至 AdminTimeoutRegistry 并置 0 规避野线程")
    void testPushTriggerQueueInterceptsTimeout() {
        IJobHandler mockHandler = new IJobHandler() {
            @Override
            public void execute() {
            }
        };

        AtengJobThread thread = new AtengJobThread(100, mockHandler);

        TriggerRequest request = new TriggerRequest();
        request.setJobId(100);
        request.setLogId(99901L);
        request.setExecutorTimeout(30); // 官方下发 30 秒超时

        thread.pushTriggerQueue(request);

        // 验证超时参数已被置为 0，防止 JobThread 底层创建 new Thread(futureTask)
        assertThat(request.getExecutorTimeout()).isEqualTo(0);

        // 验证超时时间已被转存至本地受管注册表
        assertThat(AdminTimeoutRegistry.get(99901L)).isEqualTo(30);

        thread.toStop("test finished");
        thread.interrupt();
    }

}
