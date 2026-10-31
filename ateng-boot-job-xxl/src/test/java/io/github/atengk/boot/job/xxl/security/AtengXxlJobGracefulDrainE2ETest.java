package io.github.atengk.boot.job.xxl.security;

import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.openapi.executor.dto.TriggerRequest;
import com.xxl.job.core.thread.JobThread;
import io.github.atengk.boot.job.xxl.executor.AtengXxlJobSpringExecutor;
import io.github.atengk.boot.job.xxl.mock.MockXxlJobAdminServer;
import io.github.atengk.boot.job.xxl.properties.XxlJobProperties;
import io.github.atengk.boot.job.xxl.resolver.XxlJobPortResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XXL-Job 三阶平滑停机排空状态机 (Graceful Shutdown) 端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengXxlJobGracefulDrainE2ETest {

    private MockXxlJobAdminServer mockAdmin;
    private AtengXxlJobSpringExecutor executor;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        mockAdmin = new MockXxlJobAdminServer();
        mockAdmin.start();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (executor != null && executor.isRunning()) {
            executor.stop();
        }
        if (mockAdmin != null) {
            mockAdmin.close();
        }
    }

    @Test
    @DisplayName("测试三阶平滑停机状态机: 主动注销 -> 任务排空 -> 资源安全销毁")
    void testThreeStageGracefulDrain() throws Exception {
        int port = XxlJobPortResolver.allocateRandomPort();
        XxlJobProperties props = new XxlJobProperties();
        props.setAdminAddresses(mockAdmin.getAddress());
        props.setAppname("drain-test-app");
        props.setAccessToken("drain-secret-token");
        props.setPort(port);
        props.setIp("127.0.0.1");
        props.setDrainTimeout(Duration.ofSeconds(5));

        executor = new AtengXxlJobSpringExecutor(props);
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAppname(props.getAppname());
        executor.setAccessToken(props.getAccessToken());
        executor.setPort(props.getPort());
        executor.setIp(props.getIp());
        executor.start();

        assertTrue(executor.isRunning(), "执行器应当成功启动");

        // 模拟一个运行耗时约 600ms 的业务任务
        AtomicBoolean jobStarted = new AtomicBoolean(false);
        AtomicBoolean jobCompleted = new AtomicBoolean(false);

        IJobHandler slowJobHandler = new IJobHandler() {
            @Override
            public void execute() throws Exception {
                jobStarted.set(true);
                TimeUnit.MILLISECONDS.sleep(600);
                jobCompleted.set(true);
            }
        };

        int jobId = 101;
        JobThread jobThread = executor.registJobThread(jobId, slowJobHandler, "test-init");

        // 触发任务执行
        TriggerRequest triggerParam = new TriggerRequest();
        triggerParam.setJobId(jobId);
        triggerParam.setExecutorHandler("slowJobHandler");
        triggerParam.setExecutorParams("test-param");
        triggerParam.setExecutorTimeout(0);
        triggerParam.setLogId(1001L);
        triggerParam.setLogDateTime(System.currentTimeMillis());
        triggerParam.setGlueType("BEAN");
        triggerParam.setBroadcastIndex(0);
        triggerParam.setBroadcastTotal(1);

        jobThread.pushTriggerQueue(triggerParam);

        // 等待任务确认开始运行
        long waitStartDeadline = System.currentTimeMillis() + 3000;
        while (!jobStarted.get() && System.currentTimeMillis() < waitStartDeadline) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(jobStarted.get(), "任务应当已进入运行中状态");

        // 此时触发三阶平滑停机
        CountDownLatch shutdownCallbackLatch = new CountDownLatch(1);
        new Thread(() -> executor.stop(shutdownCallbackLatch::countDown)).start();

        // 阶段一验证：排空中拒绝接收新的 /run 请求
        TimeUnit.MILLISECONDS.sleep(100);
        HttpRequest runRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/run"))
                .header("Content-Type", "application/json")
                .header("XXL-JOB-ACCESS-TOKEN", "drain-secret-token")
                .header("XXL-JOB-APPNAME", "drain-test-app")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> runResponse = httpClient.send(runRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, runResponse.statusCode(), "停机排空期入站 /run 请求应当被拒收 (HTTP 503)");

        // 阶段二验证：停机排空机制正常等待慢任务执行完成，未被粗暴中断
        boolean shutdownFinished = shutdownCallbackLatch.await(8, TimeUnit.SECONDS);
        assertTrue(shutdownFinished, "三阶停机应当在超时时限内完成");
        assertTrue(jobCompleted.get(), "运行中的慢任务应当在停机排空期平滑执行完毕");

        // 阶段一调度中心注销验证：Mock Admin 必须捕获到 registryRemove 请求
        boolean receivedRegistryRemove = mockAdmin.getRecordedRequests().stream()
                .anyMatch(r -> r.uri().endsWith("/registryRemove"));
        assertTrue(receivedRegistryRemove, "执行器停机前必须主动向调度中心发送 registryRemove 注销自身");

        // 阶段三验证：执行器已安全停机
        assertFalse(executor.isRunning(), "停机完成后 isRunning 应当为 false");
    }

}
