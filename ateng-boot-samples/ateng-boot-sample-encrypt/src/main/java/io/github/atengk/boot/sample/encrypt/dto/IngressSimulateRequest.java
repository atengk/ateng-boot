package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模拟配置中心热推送敏感明文请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngressSimulateRequest {

    /**
     * 敏感属性键名（如 custom.dynamic.password）
     */
    @NotBlank(message = "敏感属性键名 sensitiveKey 不能为空")
    private String sensitiveKey;

    /**
     * 未加密的裸明文值
     */
    @NotNull(message = "敏感明文值 plaintextValue 不能为空")
    private Object plaintextValue;
}
