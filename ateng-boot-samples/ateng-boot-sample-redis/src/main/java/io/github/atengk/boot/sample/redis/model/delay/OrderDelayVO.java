package io.github.atengk.boot.sample.redis.model.delay;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单延时任务处理结果视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDelayVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 订单编号
     */
    private String orderId;

    /**
     * 任务当前状态 (SCHEDULED, CONSUMED, POISON)
     */
    private String status;

    /**
     * 状态说明信息
     */
    private String message;

    /**
     * 预计或实际触发时间
     */
    private LocalDateTime triggerTime;
}
