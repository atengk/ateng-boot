package io.github.atengk.boot.sample.datasource.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 零明文凭据密文探测与瞬态解密校验展示视图对象
 * <p>
 * 严守零明文安全不变量：仅返回算法识别、校验状态与固定脱敏摘要，绝不透出原始明文密码。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordCipherVerifyResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否命中受管密文格式（enc:sm4: / enc:aes:）
     */
    private boolean supported;

    /**
     * 瞬态解密校验是否成功
     */
    private boolean verified;

    /**
     * 识别的加密算法类型（SM4 / AES / PLAIN）
     */
    private String algorithm;

    /**
     * 脱敏摘要占位符（绝不包含原始明文）
     */
    private String maskedDigest;

    /**
     * 校验结果说明
     */
    private String message;
}
