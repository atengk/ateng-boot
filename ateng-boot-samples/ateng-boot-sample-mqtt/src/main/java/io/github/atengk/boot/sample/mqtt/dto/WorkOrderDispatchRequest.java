package io.github.atengk.boot.sample.mqtt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 边缘工单指令下发请求 DTO（用于演示下游消费侧 @Valid 模型校验失败直入死信队列）
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderDispatchRequest implements Serializable {

    /**
     * 下发源网关逻辑标识（Controller 第一道防线强制非空白）
     */
    @NotBlank(message = "网关标识 gatewayId 不能为空")
    private String gatewayId;

    /**
     * 工单编号（网关接入层故意放行，留待下游消费侧 @Valid 拦截演示）
     */
    private String orderNo;

    /**
     * 目标设定温度（网关接入层故意放行负值，留待下游消费侧 @Valid 拦截演示）
     */
    private Integer targetTemp;
}
