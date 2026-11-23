package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.sample.smoke.enums.SmokeRecordStatusEnum;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 批量校验请求明细 DTO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeBatchItemRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 业务名称
     */
    @NotBlank(message = "业务名称不能为空")
    private String title;

    /**
     * 状态编码（受 @InEnum 强类型校验守护）
     */
    @NotBlank(message = "状态编码不能为空")
    @InEnum(value = SmokeRecordStatusEnum.class, message = "状态编码必须为 DRAFT 或 PUBLISHED")
    private String status;

    /**
     * 联系人手机号
     */
    @NotBlank(message = "手机号不能为空")
    private String mobile;
}
