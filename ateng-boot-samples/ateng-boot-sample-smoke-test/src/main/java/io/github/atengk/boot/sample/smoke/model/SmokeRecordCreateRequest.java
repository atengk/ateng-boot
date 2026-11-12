package io.github.atengk.boot.sample.smoke.model;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.sample.smoke.enums.SmokeRecordStatusEnum;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 冒烟记录创建请求 DTO
 *
 * @author Ateng
 * @since 2026-11-12
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SmokeRecordCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 记录标题
     */
    @NotBlank(message = "记录标题不能为空")
    private String title;

    /**
     * 记录状态
     */
    @NotBlank(message = "记录状态不能为空")
    @InEnum(value = SmokeRecordStatusEnum.class, message = "记录状态不合法")
    private String status;

    /**
     * 记录金额
     */
    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于 0")
    private BigDecimal amount;

    /**
     * 联系人手机号
     */
    @NotBlank(message = "手机号不能为空")
    private String mobile;
}
