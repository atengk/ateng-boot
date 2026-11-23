package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 并发支付回调模拟执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderConcurrentSimulationVO {

    /**
     * 订单 ID
     */
    private Long orderId;

    /**
     * 并发请求并发数
     */
    private Integer concurrency;

    /**
     * 首次成功跃迁次数
     */
    private Integer firstSuccessCount;

    /**
     * 幂等放行次数
     */
    private Integer idempotentAcceptedCount;

    /**
     * CAS 乐观锁冲突阻断次数
     */
    private Integer casConflictBlockedCount;

    /**
     * 其他非预期异常次数
     */
    private Integer otherFailures;

    /**
     * 最终订单状态
     */
    private OrderState finalStatus;

    /**
     * 最终乐观锁版本号
     */
    private Integer finalVersion;

    /**
     * 评估结论
     */
    private String verdict;
}
