package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 环境属性单值解密查询请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyDecryptRequest {

    /**
     * 待查询或解密的环境属性键名
     */
    @NotBlank(message = "属性键名不能为空")
    private String propertyName;
}
