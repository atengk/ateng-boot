package io.github.atengk.boot.sample.encrypt.dto;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 配置文件就地加解密回写沙箱体验请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InPlaceRewriteRequest {

    /**
     * 待处理的原始配置文件文本内容
     */
    @NotBlank(message = "配置文件内容 content 不能为空")
    private String content;

    /**
     * 加解密主密钥
     */
    @NotBlank(message = "加密主密钥 password 不能为空")
    private String password;

    /**
     * 加密算法（默认 AES-GCM）
     */
    @Builder.Default
    private EncryptAlgorithm algorithm = EncryptAlgorithm.AES_GCM;
}
