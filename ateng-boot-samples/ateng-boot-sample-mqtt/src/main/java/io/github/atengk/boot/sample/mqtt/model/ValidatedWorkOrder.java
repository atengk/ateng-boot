package io.github.atengk.boot.sample.mqtt.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 受消费端 @Valid 强约束校验的设备工单载荷模型
 *
 * @author Ateng
 * @since 2026-11-18
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ValidatedWorkOrder implements Serializable {

    @NotBlank(message = "工单编号 orderNo 不能为空")
    private String orderNo;

    @Min(value = 0, message = "目标温度 targetTemp 不能为负数")
    private int targetTemp;

    private String gatewayId;
}
