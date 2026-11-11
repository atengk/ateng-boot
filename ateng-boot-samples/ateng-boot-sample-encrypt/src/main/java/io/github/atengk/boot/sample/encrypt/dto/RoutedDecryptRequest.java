package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 多 Key 别名路由密文解密请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoutedDecryptRequest {

    /**
     * 携带路由标签的密文标记（形如 ENC[redis-cluster](...)）
     */
    @NotBlank(message = "携带路由标签的密文字符串不能为空")
    private String token;
}
