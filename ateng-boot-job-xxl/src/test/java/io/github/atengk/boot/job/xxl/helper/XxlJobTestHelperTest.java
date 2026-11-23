package io.github.atengk.boot.job.xxl.helper;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.helper.XxlJobTestHelper.XxlJobTestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * XxlJobTestHelper 脱网调试辅助门面与 POJO 自动序列化测试
 *
 * @author Ateng
 * @since 2026-11-02
 */
@SpringBootTest(classes = XxlJobTestHelperTest.TestConfig.class)
class XxlJobTestHelperTest {

    @Autowired
    private MockPojoJobHandler pojoHandler;

    @Autowired
    private XxlJobTestHelper xxlJobTestHelper;

    @Autowired
    private ApplicationContext applicationContext;

    @AfterEach
    void tearDown() {
        pojoHandler.reset();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试 XxlJobTestHelper.trigger 传递 POJO 对象入参：自动序列化为 JSON 字符串并正确透传")
    void testTriggerWithPojoObject() {
        DemoOrderPojo order = new DemoOrderPojo(999L, "ORDER-POJO-888", 299);
        XxlJobTestResult result = XxlJobTestHelper.trigger("pojoJobHandler", order);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.handleCode()).isEqualTo(200);

        String captured = pojoHandler.getCapturedParam();
        assertThat(captured).contains("\"orderId\":999");
        assertThat(captured).contains("\"orderNo\":\"ORDER-POJO-888\"");
        assertThat(captured).contains("\"amount\":299");
    }

    @Test
    @DisplayName("测试 XxlJobTestHelper.trigger 传递分片与租户：正确透传参数并在目标环境中执行")
    void testTriggerWithPojoAndTenant() {
        DemoOrderPojo order = new DemoOrderPojo(1001L, "ORDER-TENANT", 500);
        XxlJobTestResult result = XxlJobTestHelper.trigger("pojoJobHandler", order, 1, 3, "tenant-test-666");

        assertThat(result.isSuccess()).isTrue();
        assertThat(pojoHandler.getCapturedTenant()).isEqualTo("tenant-test-666");
    }

    @Test
    @DisplayName("测试 XxlJobTestHelper.trigger 原生 String 入参保持不变")
    void testTriggerWithStringParam() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("pojoJobHandler", "raw-string-param");

        assertThat(result.isSuccess()).isTrue();
        assertThat(pojoHandler.getCapturedParam()).isEqualTo("raw-string-param");
    }

    @Test
    @DisplayName("测试 @AtengXxlJob 注解的 Handler 自动探测与触发")
    void testAtengXxlJobAnnotationDiscovery() {
        XxlJobTestResult result = XxlJobTestHelper.trigger("atengOnlyJob", "hello-ateng");

        assertThat(result.isSuccess()).isTrue();
        assertThat(pojoHandler.getCapturedParam()).isEqualTo("hello-ateng");
    }

    @Test
    @DisplayName("测试 XxlJobTestHelper.trigger 使用 ShardingContext 对象透传分片参数")
    void testTriggerWithShardingContext() {
        DemoOrderPojo order = new DemoOrderPojo(2001L, "ORDER-SHARDING", 888);
        io.github.atengk.boot.job.xxl.model.ShardingContext context = new io.github.atengk.boot.job.xxl.model.ShardingContext(2, 5);
        XxlJobTestResult result = XxlJobTestHelper.trigger("pojoJobHandler", order, context, "tenant-sharding-999");

        assertThat(result.isSuccess()).isTrue();
        assertThat(pojoHandler.getCapturedTenant()).isEqualTo("tenant-sharding-999");
        assertThat(pojoHandler.getCapturedParam()).contains("\"orderNo\":\"ORDER-SHARDING\"");
    }

    @Test
    @DisplayName("并发压测：高并发交错调用 setApplicationContext/clear/getHandler/trigger 无死锁无异常")
    void testConcurrentHelperScanClearAndTrigger() throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(12);
        pool.setMaxPoolSize(12);
        pool.setThreadNamePrefix("helper-stress-");
        pool.initialize();

        try {
            // 阶段一：高并发交错 setApplicationContext / getHandler / trigger（零锁撕裂与空窗）
            CountDownLatch phase1Gate = new CountDownLatch(1);
            List<CompletableFuture<Void>> phase1Futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                final int idx = i;
                phase1Futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        phase1Gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    if (idx % 3 == 0) {
                        xxlJobTestHelper.setApplicationContext(applicationContext);
                    } else if (idx % 3 == 1) {
                        assertThat(XxlJobTestHelper.getHandler("pojoJobHandler")).isNotNull();
                    } else {
                        XxlJobTestResult result = XxlJobTestHelper.trigger("atengOnlyJob", "stress-" + idx);
                        assertThat(result.isSuccess()).isTrue();
                    }
                }, pool));
            }
            phase1Gate.countDown();
            CompletableFuture.allOf(phase1Futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

            // 阶段二：高并发交错 clear / setApplicationContext / getHandler（无死锁、无异常）
            CountDownLatch phase2Gate = new CountDownLatch(1);
            List<CompletableFuture<Void>> phase2Futures = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                final int idx = i;
                phase2Futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        phase2Gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    if (idx % 3 == 0) {
                        XxlJobTestHelper.clear();
                    } else if (idx % 3 == 1) {
                        xxlJobTestHelper.setApplicationContext(applicationContext);
                    } else {
                        XxlJobTestHelper.getHandler("pojoJobHandler");
                    }
                }, pool));
            }
            phase2Gate.countDown();
            CompletableFuture.allOf(phase2Futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);
        } finally {
            xxlJobTestHelper.setApplicationContext(applicationContext);
            pool.shutdown();
        }
        assertThat(XxlJobTestHelper.trigger("pojoJobHandler", "after-stress").isSuccess()).isTrue();
    }

    public record DemoOrderPojo(Long orderId, String orderNo, Integer amount) {}

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        public XxlJobTestHelper xxlJobTestHelper() {
            return new XxlJobTestHelper();
        }

        @Bean
        public MockPojoJobHandler mockPojoJobHandler() {
            return new MockPojoJobHandler();
        }
    }

    @Component
    public static class MockPojoJobHandler {

        private final AtomicReference<String> capturedParam = new AtomicReference<>();
        private final AtomicReference<String> capturedTenant = new AtomicReference<>();

        @XxlJob("pojoJobHandler")
        public void execute() {
            capturedParam.set(XxlJobHelper.getJobParam());
            capturedTenant.set(TenantContextHolder.getTenantId());
            XxlJobHelper.handleSuccess("OK");
        }

        @AtengXxlJob("atengOnlyJob")
        public void executeAtengOnly() {
            capturedParam.set(XxlJobHelper.getJobParam());
            XxlJobHelper.handleSuccess("OK");
        }

        public String getCapturedParam() {
            return capturedParam.get();
        }

        public String getCapturedTenant() {
            return capturedTenant.get();
        }

        public void reset() {
            capturedParam.set(null);
            capturedTenant.set(null);
        }
    }

}
