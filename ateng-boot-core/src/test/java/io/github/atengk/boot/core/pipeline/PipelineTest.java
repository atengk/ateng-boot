package io.github.atengk.boot.core.pipeline;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Pipeline} 强类型流水线与责任链原语单元测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@DisplayName("Pipeline 强类型管道编排原语测试")
class PipelineTest {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    static class CheckoutContext implements PipelineInterruptible {
        private String orderNo;
        private Long userId;
        private Integer stock;
        private Integer quantity;
        private Boolean riskPassed;
        private boolean interrupted;
        private String interruptReason;

        @Builder.Default
        private List<String> passedStages = new ArrayList<>();

        @Override
        public boolean isInterrupted() {
            return interrupted;
        }

        @Override
        public String getInterruptReason() {
            return interruptReason;
        }

        @Override
        public void interrupt(String reason) {
            this.interrupted = true;
            this.interruptReason = reason;
        }
    }

    @Test
    @DisplayName("所有阶段均通过时，应该返回成功并按顺序执行全部阶段")
    void shouldExecuteAllStagesSuccessfully() {
        Pipeline<CheckoutContext> pipeline = Pipeline.<CheckoutContext>builder()
                .addStage("参数校验", ctx -> {
                    ctx.getPassedStages().add("参数校验");
                    return ctx.getOrderNo() != null && ctx.getUserId() != null;
                })
                .addStage(PipelineStage.ofConsumer("风控检查", ctx -> {
                    ctx.getPassedStages().add("风控检查");
                    ctx.setRiskPassed(true);
                }))
                .addStage("库存校验", ctx -> {
                    ctx.getPassedStages().add("库存校验");
                    return ctx.getStock() >= ctx.getQuantity();
                })
                .build();

        CheckoutContext context = CheckoutContext.builder()
                .orderNo("ORD-001")
                .userId(100L)
                .stock(10)
                .quantity(2)
                .build();

        PipelineResult result = pipeline.execute(context);

        assertTrue(result.isSuccess());
        assertFalse(result.isTerminated());
        assertFalse(result.isFailed());
        assertNull(result.getTerminatedStageName());
        assertNull(result.getFailureCause());
        assertDoesNotThrow(() -> result.orThrow());

        assertEquals(List.of("参数校验", "风控检查", "库存校验"), context.getPassedStages());
        assertTrue(context.getRiskPassed());
    }

    @Test
    @DisplayName("某阶段返回 false 时应该短路中断并记录阶段名称，后续阶段不再执行")
    void shouldShortCircuitWhenStageReturnsFalse() {
        Pipeline<CheckoutContext> pipeline = Pipeline.<CheckoutContext>builder()
                .addStage("参数校验", ctx -> {
                    ctx.getPassedStages().add("参数校验");
                    return true;
                })
                .addStage("库存校验", ctx -> {
                    ctx.getPassedStages().add("库存校验");
                    return ctx.getStock() >= ctx.getQuantity(); // 5 < 10 -> false
                })
                .addStage("抵扣优惠券", ctx -> {
                    ctx.getPassedStages().add("抵扣优惠券");
                    return true;
                })
                .build();

        CheckoutContext context = CheckoutContext.builder()
                .orderNo("ORD-002")
                .userId(100L)
                .stock(5)
                .quantity(10)
                .build();

        PipelineResult result = pipeline.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.isTerminated());
        assertFalse(result.isFailed());
        assertEquals("库存校验", result.getTerminatedStageName());

        // 验证短路：抵扣优惠券阶段未被执行
        assertEquals(List.of("参数校验", "库存校验"), context.getPassedStages());

        // 验证 orThrow 快速失败抛出 BusinessException
        BusinessException ex = assertThrows(BusinessException.class, result::orThrow);
        assertEquals(CommonResultCode.BAD_REQUEST.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("库存校验"));
    }

    @Test
    @DisplayName("上下文显式触发 interrupt 时应该短路中断并记录中断原因")
    void shouldShortCircuitWhenContextIsInterrupted() {
        Pipeline<CheckoutContext> pipeline = Pipeline.<CheckoutContext>builder()
                .addStage("风控黑名单排查", ctx -> {
                    if (Long.valueOf(999L).equals(ctx.getUserId())) {
                        ctx.interrupt("用户命中高危风控黑名单");
                        return false;
                    }
                    return true;
                })
                .addStage("扣减积分", ctx -> {
                    ctx.getPassedStages().add("扣减积分");
                    return true;
                })
                .build();

        CheckoutContext context = CheckoutContext.builder()
                .userId(999L)
                .build();

        PipelineResult result = pipeline.execute(context);

        assertTrue(result.isTerminated());
        assertEquals("风控黑名单排查", result.getTerminatedStageName());
        assertEquals("用户命中高危风控黑名单", result.getInterruptReason());

        BusinessException ex = assertThrows(BusinessException.class, result::orThrow);
        assertEquals("用户命中高危风控黑名单", ex.getMessage());
        assertTrue(context.getPassedStages().isEmpty());
    }

    @Test
    @DisplayName("阶段抛出异常时应该捕获并记录 failureCause，支持自定义错误处理器")
    void shouldHandleExceptionThrownFromStage() {
        AtomicBoolean customHandlerInvoked = new AtomicBoolean(false);

        Pipeline<CheckoutContext> pipeline = Pipeline.<CheckoutContext>builder()
                .addStage("正常阶段", ctx -> true)
                .addStage("偶发崩溃阶段", ctx -> {
                    throw new IllegalStateException("第三方外部接口网络异常");
                })
                .setErrorHandler((stage, ctx, cause) -> {
                    customHandlerInvoked.set(true);
                    assertEquals("偶发崩溃阶段", stage.getName());
                })
                .build();

        CheckoutContext context = CheckoutContext.builder().build();
        PipelineResult result = pipeline.execute(context);

        assertFalse(result.isSuccess());
        assertTrue(result.isFailed());
        assertEquals("偶发崩溃阶段", result.getTerminatedStageName());
        assertInstanceOf(IllegalStateException.class, result.getFailureCause());
        assertTrue(customHandlerInvoked.get());

        // orThrow 抛出 BusinessException 并保留原始 cause
        BusinessException be = assertThrows(BusinessException.class, result::orThrow);
        assertEquals(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), be.getCode());
        assertInstanceOf(IllegalStateException.class, be.getCause());
    }

    @Test
    @DisplayName("应该支持通过 callback 回调接收执行结果")
    void shouldInvokeCallbackOnExecutionCompleted() {
        AtomicBoolean callbackTriggered = new AtomicBoolean(false);

        Pipeline<String> pipeline = Pipeline.<String>builder()
                .addStage("打印检查", str -> str.length() > 3)
                .build();

        PipelineResult result = pipeline.execute("Hello", res -> {
            callbackTriggered.set(true);
            assertTrue(res.isSuccess());
        });

        assertTrue(callbackTriggered.get());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("边界测试：空阶段管道应该直接返回成功；null 上下文应该抛出 NPE 前置卫语句拦截")
    void shouldHandleEdgeCases() {
        Pipeline<Object> emptyPipeline = Pipeline.builder().build();
        assertTrue(emptyPipeline.execute(new Object()).isSuccess());

        assertThrows(NullPointerException.class, () -> emptyPipeline.execute(null));
        assertThrows(NullPointerException.class, () -> Pipeline.builder().addStage((PipelineStage<Object>) null));
    }

    @Test
    @DisplayName("验证 orThrow 支持流式链式调用并返回当前结果对象")
    void shouldSupportFluentChainingWithOrThrow() {
        Pipeline<String> pipeline = Pipeline.<String>builder()
                .addStage("长度检查", str -> str.length() > 2)
                .build();

        PipelineResult result = pipeline.execute("Antigravity").orThrow();
        assertNotNull(result);
        assertTrue(result.isSuccess());

        PipelineResult customResult = pipeline.execute("Antigravity").orThrow(r -> new RuntimeException("Error"));
        assertNotNull(customResult);
        assertTrue(customResult.isSuccess());
    }

    @Test
    @DisplayName("Lambda 阶段未显式指定名称时应该保底返回 AnonymousStage")
    void shouldDefaultToAnonymousStageForLambda() {
        Pipeline<String> pipeline = Pipeline.<String>builder()
                .addStage(str -> false)
                .build();

        PipelineResult result = pipeline.execute("test");
        assertFalse(result.isSuccess());
        assertTrue(result.isTerminated());
        assertEquals("AnonymousStage", result.getTerminatedStageName());
    }

    @Test
    @DisplayName("当 errorHandler 抛出次生异常时应该将其挂载为 suppressed exception 而非吞咽")
    void shouldAttachSuppressedExceptionWhenErrorHandlerThrows() {
        Pipeline<String> pipeline = Pipeline.<String>builder()
                .addStage("主阶段", str -> {
                    throw new IllegalStateException("原始业务异常");
                })
                .setErrorHandler((stage, ctx, cause) -> {
                    throw new RuntimeException("错误处理器内部次生异常");
                })
                .build();

        PipelineResult result = pipeline.execute("test");
        assertTrue(result.isFailed());
        Throwable cause = result.getFailureCause();
        assertInstanceOf(IllegalStateException.class, cause);
        assertEquals("原始业务异常", cause.getMessage());
        assertEquals(1, cause.getSuppressed().length);
        assertInstanceOf(RuntimeException.class, cause.getSuppressed()[0]);
        assertEquals("错误处理器内部次生异常", cause.getSuppressed()[0].getMessage());
    }
}
