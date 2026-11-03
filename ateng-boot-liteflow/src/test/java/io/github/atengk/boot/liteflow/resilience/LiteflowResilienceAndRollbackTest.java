package io.github.atengk.boot.liteflow.resilience;

import io.github.atengk.boot.liteflow.component.BaseNodeComponent;
import io.github.atengk.boot.liteflow.core.LiteflowTemplate;
import io.github.atengk.boot.liteflow.exception.LiteflowExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 并行超时隔离熔断、优雅降级与 Saga 逆序反向补偿安全网集成测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
@SpringBootTest(classes = LiteflowResilienceAndRollbackTest.TestConfig.class, properties = {
        "liteflow.rule-source=classpath:liteflow/resilience-flow.el.xml",
        "liteflow.enable=true"
})
@DisplayName("LiteFlow 韧性防卫与 Saga 回滚安全网测试")
class LiteflowResilienceAndRollbackTest {

    @Autowired
    private LiteflowTemplate liteflowTemplate;

    @BeforeEach
    void setUp() {
        ResilienceTestContext.SLOW_NODE_INTERRUPTED.set(false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({
            OrderCreateNode.class,
            InventoryDeductNode.class,
            PaymentFailNode.class,
            FlakyRollbackNode.class,
            FailingNode.class,
            DegradeNode.class,
            BrandNewFallbackNode.class,
            FollowUpNode.class,
            FastNode.class,
            SlowHangNode.class
    })
    static class TestConfig {
    }

    /**
     * 业务韧性测试上下文
     */
    public static class ResilienceTestContext {
        public static final AtomicBoolean SLOW_NODE_INTERRUPTED = new AtomicBoolean(false);

        private final List<String> processLogs = new ArrayList<>();
        private final List<String> rollbackLogs = new ArrayList<>();
        private boolean fallbackApplied = false;
        private String fallbackData;
        private boolean secondaryErrorCaptured = false;

        public List<String> getProcessLogs() {
            return processLogs;
        }

        public List<String> getRollbackLogs() {
            return rollbackLogs;
        }

        public boolean isFallbackApplied() {
            return fallbackApplied;
        }

        public void setFallbackApplied(boolean fallbackApplied) {
            this.fallbackApplied = fallbackApplied;
        }

        public String getFallbackData() {
            return fallbackData;
        }

        public void setFallbackData(String fallbackData) {
            this.fallbackData = fallbackData;
        }

        public boolean isSecondaryErrorCaptured() {
            return secondaryErrorCaptured;
        }

        public void setSecondaryErrorCaptured(boolean secondaryErrorCaptured) {
            this.secondaryErrorCaptured = secondaryErrorCaptured;
        }
    }

    // ==================== 1. Saga 逆序反向补偿组件 ====================

    @Component("orderCreateNode")
    public static class OrderCreateNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            context.getProcessLogs().add("order_created");
        }

        @Override
        public void rollback(ResilienceTestContext context) {
            context.getRollbackLogs().add("order_cancelled");
        }
    }

    @Component("inventoryDeductNode")
    public static class InventoryDeductNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            context.getProcessLogs().add("inventory_deducted");
        }

        @Override
        public void rollback(ResilienceTestContext context) {
            context.getRollbackLogs().add("inventory_restored");
        }
    }

    @Component("paymentFailNode")
    public static class PaymentFailNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            throw new IllegalStateException("扣款失败: 账户可用余额不足");
        }
    }

    // ==================== 2. 回滚次生异常防御组件 ====================

    @Component("flakyRollbackNode")
    public static class FlakyRollbackNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            context.getProcessLogs().add("flaky_done");
        }

        @Override
        public void rollback(ResilienceTestContext context) {
            // 模拟回滚自身因网络抖动抛出异常
            throw new RuntimeException("回滚外部系统网络超时");
        }

        @Override
        protected void onRollbackError(Throwable throwable, ResilienceTestContext context) {
            context.setSecondaryErrorCaptured(true);
        }
    }

    @Component("failingNode")
    public static class FailingNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            throw new IllegalStateException("下游节点抛出业务异常");
        }
    }

    // ==================== 3. 优雅就地降级组件 ====================

    @Component("degradeNode")
    public static class DegradeNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            throw new IllegalStateException("推荐引擎RPC调用超时");
        }

        @Override
        public ResilienceTestContext onError(Exception e, ResilienceTestContext context) {
            // 就地降级填充保底默认商品，不重新抛出异常
            context.setFallbackApplied(true);
            context.setFallbackData("HOT_SALE_FALLBACK");
            return context;
        }
    }

    @Component("brandNewFallbackNode")
    public static class BrandNewFallbackNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            throw new IllegalStateException("模拟核心计算失败触发全新对象降级");
        }

        @Override
        public ResilienceTestContext onError(Exception e, ResilienceTestContext context) {
            ResilienceTestContext brandNew = new ResilienceTestContext();
            brandNew.setFallbackApplied(true);
            brandNew.setFallbackData("BRAND_NEW_FALLBACK_INSTANCE");
            return brandNew;
        }
    }

    @Component("followUpNode")
    public static class FollowUpNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            context.getProcessLogs().add("followUp:" + context.getFallbackData());
        }
    }

    // ==================== 4. 并行超时熔断与协同中断组件 ====================

    @Component("fastNode")
    public static class FastNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            context.getProcessLogs().add("fast_done");
        }
    }

    @Component("slowHangNode")
    public static class SlowHangNode extends BaseNodeComponent<ResilienceTestContext> {
        @Override
        public void process(ResilienceTestContext context) {
            try {
                // 模拟耗时 5 秒的长任务，预期在 300ms 超时后被中断
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                ResilienceTestContext.SLOW_NODE_INTERRUPTED.set(true);
                Thread.currentThread().interrupt();
            }
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("测试下游节点异常时已成功节点触发 Saga 逆序反向补偿")
    void testSagaReverseRollback() {
        ResilienceTestContext context = new ResilienceTestContext();

        assertThatThrownBy(() -> liteflowTemplate.executeWithContext("testSagaReverseRollback", context))
                .isInstanceOf(LiteflowExecutionException.class)
                .hasMessageContaining("testSagaReverseRollback");

        // 验证正向执行顺序
        assertThat(context.getProcessLogs()).containsExactly("order_created", "inventory_deducted");
        // 验证逆序补偿顺序：先回滚库存，再回滚订单
        assertThat(context.getRollbackLogs()).containsExactly("inventory_restored", "order_cancelled");
    }

    @Test
    @DisplayName("测试回滚补偿自身抛出次生异常时被安全拦截与审计，杜绝事务悬挂")
    void testRollbackSecondaryExceptionSafety() {
        ResilienceTestContext context = new ResilienceTestContext();

        assertThatThrownBy(() -> liteflowTemplate.executeWithContext("testRollbackSecondaryExceptionSafety", context))
                .isInstanceOf(LiteflowExecutionException.class);

        assertThat(context.getProcessLogs()).containsExactly("flaky_done");
        assertThat(context.isSecondaryErrorCaptured()).isTrue();
    }

    @Test
    @DisplayName("测试节点异常触发 onError 就地优雅降级，主流水线不中断继续执行")
    void testInPlaceGracefulDegradation() {
        ResilienceTestContext context = new ResilienceTestContext();

        // 正常返回，不抛出异常
        ResilienceTestContext result = liteflowTemplate.executeWithContext("testInPlaceGracefulDegradation", context);

        assertThat(result).isSameAs(context);
        assertThat(context.isFallbackApplied()).isTrue();
        assertThat(context.getFallbackData()).isEqualTo("HOT_SALE_FALLBACK");
        // 验证后续节点顺利接收降级保底数据并完成执行
        assertThat(context.getProcessLogs()).containsExactly("followUp:HOT_SALE_FALLBACK");
    }

    @Test
    @DisplayName("测试 onError 返回全新上下文实例时自动同步保底属性至槽位上下文")
    void testNewInstanceGracefulDegradation() {
        ResilienceTestContext context = new ResilienceTestContext();

        ResilienceTestContext result = liteflowTemplate.executeElWithContext(
                "THEN(brandNewFallbackNode, followUpNode)", context);

        assertThat(result).isSameAs(context);
        assertThat(context.isFallbackApplied()).isTrue();
        assertThat(context.getFallbackData()).isEqualTo("BRAND_NEW_FALLBACK_INSTANCE");
        assertThat(context.getProcessLogs()).containsExactly("followUp:BRAND_NEW_FALLBACK_INSTANCE");
    }

    @Test
    @DisplayName("测试并行编排硬超时熔断，并协同向长任务子线程发送中断信号")
    void testParallelTimeoutAndInterruption() throws InterruptedException {
        ResilienceTestContext context = new ResilienceTestContext();

        long startTime = System.currentTimeMillis();

        assertThatThrownBy(() -> liteflowTemplate.executeWithContext("testParallelTimeoutAndInterruption", context))
                .isInstanceOf(LiteflowExecutionException.class);

        long duration = System.currentTimeMillis() - startTime;
        // 预期在 300ms 超时熔断，总耗时应远低于 5000ms
        assertThat(duration).isLessThan(3000);

        // 等待短暂片刻以确保子线程接收到中断信号并完成标志位设置
        int attempts = 0;
        while (!ResilienceTestContext.SLOW_NODE_INTERRUPTED.get() && attempts++ < 20) {
            Thread.sleep(50);
        }

        assertThat(ResilienceTestContext.SLOW_NODE_INTERRUPTED.get())
                .as("超时熔断后应协同向 slowHangNode 子线程发出中断信号")
                .isTrue();
    }
}
