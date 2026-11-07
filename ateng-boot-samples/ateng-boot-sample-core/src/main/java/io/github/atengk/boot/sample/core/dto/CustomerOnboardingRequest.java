package io.github.atengk.boot.sample.core.dto;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.core.validation.ValidationGroups;
import io.github.atengk.boot.sample.core.enums.OrderStatusEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 客户批量入驻请求传输模型
 * 演示 JSR-303 分层校验、@InEnum 枚举合法性防卫与 ValidList 级联校验
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerOnboardingRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户姓名
     */
    @NotBlank(message = "客户姓名不能为空", groups = {ValidationGroups.Create.class, ValidationGroups.Update.class})
    private String name;

    /**
     * 客户联系手机
     */
    @NotBlank(message = "联系手机不能为空", groups = {ValidationGroups.Create.class, ValidationGroups.Update.class})
    private String phone;

    /**
     * 身份证号码（可选，用于编程式 ValidatorUtil 校验）
     */
    private String idCard;

    /**
     * 统一社会信用代码（企业客户使用）
     */
    private String socialCreditCode;

    /**
     * 初始业务状态码（第一道防线：@InEnum 强类型枚举值拦截）
     */
    @NotNull(message = "初始业务状态编码不能为空")
    @InEnum(value = OrderStatusEnum.class, message = "初始业务状态编码不合法")
    private Integer statusCode;
}
