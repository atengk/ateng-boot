package io.github.atengk.boot.job.snail.retry;

import com.aizuda.snailjob.client.core.intercepter.RetrySiteSnapshot;
import com.aizuda.snailjob.common.core.model.IdempotentIdContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.annotation.AtengSnailRetry;
import io.github.atengk.boot.job.snail.autoconfigure.AtengSnailJobAutoConfiguration;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.retry.aspect.SnailRetryContextAspect;
import io.github.atengk.boot.job.snail.retry.context.SnailRetryContextRegistry;
import io.github.atengk.boot.job.snail.retry.generator.ArgsHashIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.generator.SpelIdempotentIdGenerator;
import io.github.atengk.boot.job.snail.retry.template.SnailRetryTemplate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SnailJob 分布式重试四维现场保真、SpEL 幂等与 SnailRetryTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@SpringBootTest(classes = {
        AtengSnailJobAutoConfiguration.class,
        SnailRetryAspectAndTemplateTest.TestConfig.class
})
class SnailRetryAspectAndTemplateTest {

    @Autowired
    private MockRetryService mockRetryService;

    @Autowired
    private SnailRetryTemplate retryTemplate;

    @Autowired
    private SpelIdempotentIdGenerator spelIdempotentIdGenerator;

    @Autowired
    private ArgsHashIdempotentIdGenerator argsHashIdempotentIdGenerator;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
        SnailRetryContextRegistry.clear();
        RetrySiteSnapshot.removeAll();
    }

    @Test
    @DisplayName("测试远程重试跨时间/跨节点保真还原 MDC traceId、User 与 TenantId，并在 finally 恢复现场")
    void testRemoteRetryContextRestorationAndCleanup() {
        // 1. 模拟业务首次调用，具备四维上下文
        MDC.put(SnailRetryContextAspect.TRACE_ID_KEY, "trace-orig-1001");
        UserContextHolder.set("operator-user-1");
        TenantContextHolder.setTenantId("tenant-finance-01");

        OrderCommand order = new OrderCommand("ORDER_20261031_001", "item_a", 100);

        // 首次调用（正常注册快照）
        String res = mockRetryService.payOrder(order);
        assertThat(res).isEqualTo("success: ORDER_20261031_001");

        // 2. 模拟远程重试触发：处于另外的工作线程（初始上下文完全为空）
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();

        // 设置当前处于 SnailJob 远程重试阶段
        RetrySiteSnapshot.setStage(RetrySiteSnapshot.EnumStage.REMOTE.getStage());

        try {
            // 执行远程重试
            String retryRes = mockRetryService.payOrder(order);
            assertThat(retryRes).isEqualTo("success: ORDER_20261031_001");
        } finally {
            RetrySiteSnapshot.removeAll();
        }

        // 验证执行结束后工作线程现场已被严格还原清理，绝无线程池复用脏读
        assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isNull();
        assertThat(UserContextHolder.getExplicit()).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试 SpelIdempotentIdGenerator 正确通过 SpEL 表达式提取业务唯一幂等键")
    void testSpelIdempotentIdGeneration() throws Exception {
        OrderCommand order = new OrderCommand("ORDER_SN_8888", "phone", 1);
        IdempotentIdContext context = new IdempotentIdContext(
                "orderPayScene",
                MockRetryService.class.getName(),
                new Object[]{order},
                null,
                "payOrder"
        );

        String idempotentId = spelIdempotentIdGenerator.idGenerate(context);
        assertThat(idempotentId).isEqualTo("orderPayScene_ORDER_SN_8888");
    }

    @Test
    @DisplayName("测试 ArgsHashIdempotentIdGenerator 生成确定性 64 位 SHA-256 散列值")
    void testArgsHashIdempotentIdGeneration() throws Exception {
        IdempotentIdContext context = new IdempotentIdContext(
                "stockDeductScene",
                "com.example.StockService",
                new Object[]{"PROD_001", 10},
                "{\"prod\":\"PROD_001\",\"qty\":10}",
                "deduct"
        );

        String hash1 = argsHashIdempotentIdGenerator.idGenerate(context);
        String hash2 = argsHashIdempotentIdGenerator.idGenerate(context);

        assertThat(hash1).isNotNull().hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 编程式执行成功返回结果")
    void testRetryTemplateExecuteSuccess() {
        AtomicInteger count = new AtomicInteger(0);

        String result = retryTemplate.execute("queryScene", () -> {
            if (count.incrementAndGet() < 2) {
                throw new RuntimeException("临时网络抖动");
            }
            return "SUCCESS_DATA";
        });

        assertThat(result).isEqualTo("SUCCESS_DATA");
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 耗尽重试后转译为 BusinessException 并上报")
    void testRetryTemplateExhaustedThrowsBusinessException() {
        AtomicInteger count = new AtomicInteger(0);

        assertThatThrownBy(() -> retryTemplate.executeWithIdempotent("failScene", "IDEM_999", () -> {
            count.incrementAndGet();
            throw new IllegalStateException("下游服务不可用");
        }))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("业务重试耗尽");

        assertThat(count.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("测试 SnailRetryTemplate 手动上报方法在无外部服务时平滑保底返回")
    void testReportManualRetryFallback() {
        boolean reported = retryTemplate.reportManualRetry(
                "manualScene",
                "com.example.PaymentService",
                new Object[]{"PAY_123"},
                "IDEM_PAY_123",
                "BIZ_NO_999"
        );

        assertThat(reported).isTrue();
    }

    @TestConfiguration
    @EnableAspectJAutoProxy
    static class TestConfig {
        @Bean
        public MockRetryService mockRetryService() {
            return new MockRetryService();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderCommand {
        private String orderNo;
        private String item;
        private Integer price;
    }

    @Service
    public static class MockRetryService {

        @AtengSnailRetry(scene = "orderPayScene", idempotentSpel = "#order.orderNo", tenantMode = JobTenantMode.PARAM)
        public String payOrder(OrderCommand order) {
            // 如果处于远程重试阶段，断言四维上下文已被正确还原
            if (RetrySiteSnapshot.getStage() != null
                    && RetrySiteSnapshot.getStage() == RetrySiteSnapshot.EnumStage.REMOTE.getStage()) {
                assertThat(MDC.get(SnailRetryContextAspect.TRACE_ID_KEY)).isEqualTo("trace-orig-1001");
                assertThat(UserContextHolder.getExplicit()).isEqualTo("operator-user-1");
                assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-finance-01");
            }
            return "success: " + order.getOrderNo();
        }
    }
}
