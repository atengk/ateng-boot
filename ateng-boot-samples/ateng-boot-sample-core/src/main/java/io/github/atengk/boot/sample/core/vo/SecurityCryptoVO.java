package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 国密 SM4 密钥生成与带 AAD 认证 AES-GCM 加解密响应视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityCryptoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * CryptoUtil.generateSm4Key() 生成的 SM4 密钥字节长度（固定 16 字节）
     */
    private Integer sm4KeyByteLength;

    /**
     * 国密 SM4/CBC Base64 密文
     */
    private String sm4CbcCiphertext;

    /**
     * 国密 SM4/CBC 解密还原明文
     */
    private String sm4CbcDecrypted;

    /**
     * 带 AAD 认证的 AES/GCM Base64 密文
     */
    private String gcmCiphertextWithAad;

    /**
     * 带 AAD 认证的 AES/GCM 解密还原明文
     */
    private String gcmDecryptedWithAad;

    /**
     * 篡改 AAD 时是否成功触发带原始 cause 的 BusinessException 认证拦截
     */
    private boolean aadTamperBlocked;
}
