package io.github.atengk.boot.sample.core;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.pipeline.PipelineResult;
import io.github.atengk.boot.sample.core.service.CorePipelineScenarioService;
import io.github.atengk.boot.sample.core.service.CorePipelineScenarioService.OrderCheckoutContext;
import io.github.atengk.boot.sample.core.service.CorePipelineScenarioService.OrderItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Core 强类型业务管道提单流水线端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
class CorePipelineScenarioServiceTest {

    @Autowired
    private CorePipelineScenarioService pipelineScenarioService;

    @Test
    @DisplayName("提单前置校验全部通过：正常流转所有阶段并计算优惠抵扣")
    void testCheckoutPipelineSuccess() {
        OrderCheckoutContext context = OrderCheckoutContext.builder()
                .orderNo("ORD-20261027-001")
                .userId(10086L)
                .orderAmount(BigDecimal.valueOf(150.00))
                .items(List.of(
                        OrderItem.builder().skuCode("SKU-APPLE").quantity(2).availableStock(10).build(),
                        OrderItem.builder().skuCode("SKU-BANANA").quantity(1).availableStock(5).build()
                ))
                .build();

        PipelineResult result = pipelineScenarioService.processCheckout(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isTerminated()).isFalse();
        assertThat(result.getTerminatedStageName()).isNull();
        assertThat(result.getFailureCause()).isNull();

        // 验证执行轨迹与业务上下文状态突变
        assertThat(context.getAuditTrail()).containsExactly("基础参数检查", "风控合规校验", "库存充足性检查", "营销优惠计算");
        assertThat(context.isRiskPassed()).isTrue();
        assertThat(context.isInventoryReserved()).isTrue();
        assertThat(context.getDiscountAmount()).isEqualByComparingTo(BigDecimal.valueOf(20));
    }

    @Test
    @DisplayName("提单库存不足短路阻断：后续优惠阶段跳过，并抛出受管业务异常")
    void testCheckoutPipelineInventoryShortCircuit() {
        OrderCheckoutContext context = OrderCheckoutContext.builder()
                .orderNo("ORD-20261027-002")
                .userId(10086L)
                .orderAmount(BigDecimal.valueOf(200.00))
                .items(List.of(
                        // 购买 10 件但库存仅剩 3 件，触发短路
                        OrderItem.builder().skuCode("SKU-PHONE").quantity(10).availableStock(3).build()
                ))
                .build();

        PipelineResult result = pipelineScenarioService.processCheckout(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getTerminatedStageName()).isEqualTo("库存充足性检查");
        assertThat(result.getInterruptReason()).contains("商品 [SKU-PHONE] 库存不足");

        // 验证短路阻断：营销优惠计算阶段未被执行
        assertThat(context.getAuditTrail()).containsExactly("基础参数检查", "风控合规校验", "库存充足性检查");
        assertThat(context.isInventoryReserved()).isFalse();
        assertThat(context.getDiscountAmount()).isNull();

        // 验证 orThrow 快速失败转译为标准 BusinessException
        assertThatThrownBy(result::orThrow)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("库存不足")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("用户命中风控黑名单：在风控阶段主动触发 interrupt 短路阻断")
    void testCheckoutPipelineRiskInterrupted() {
        OrderCheckoutContext context = OrderCheckoutContext.builder()
                .orderNo("ORD-20261027-003")
                .userId(9999L) // 黑名单用户
                .orderAmount(BigDecimal.valueOf(50.00))
                .items(List.of(OrderItem.builder().skuCode("SKU-NORMAL").quantity(1).availableStock(10).build()))
                .build();

        PipelineResult result = pipelineScenarioService.processCheckout(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getTerminatedStageName()).isEqualTo("风控合规校验");
        assertThat(result.getInterruptReason()).isEqualTo("用户命中欺诈风险黑名单，禁止下单");

        // 验证短路阻断：库存与优惠均未被执行
        assertThat(context.getAuditTrail()).containsExactly("基础参数检查", "风控合规校验");
        assertThat(context.isRiskPassed()).isFalse();

        assertThatThrownBy(result::orThrow)
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户命中欺诈风险黑名单，禁止下单");
    }

    @Test
    @DisplayName("基础参数非法：第一阶段立即短路并快速拦截")
    void testCheckoutPipelineInvalidParams() {
        OrderCheckoutContext context = OrderCheckoutContext.builder()
                .orderNo(null) // 缺失订单号
                .userId(10086L)
                .orderAmount(BigDecimal.ZERO)
                .build();

        PipelineResult result = pipelineScenarioService.processCheckout(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isTerminated()).isTrue();
        assertThat(result.getTerminatedStageName()).isEqualTo("基础参数检查");
        assertThat(result.getInterruptReason()).isEqualTo("订单基础参数或金额非法");

        assertThat(context.getAuditTrail()).containsExactly("基础参数检查");
    }
}
