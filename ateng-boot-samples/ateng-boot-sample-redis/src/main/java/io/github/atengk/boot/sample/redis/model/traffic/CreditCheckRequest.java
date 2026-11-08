package io.github.atengk.boot.sample.redis.model.traffic;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 外部征信/AI 推理在途并发治理请求对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreditCheckRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "身份证号不能为空")
    private String idCard;

    @NotBlank(message = "客户姓名不能为空")
    private String customerName;
}
