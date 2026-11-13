package io.github.atengk.boot.sample.core.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 国密 HmacSM3 与恒定时间验签响应视图对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignAndVerifyVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 国密 HmacSM3 十六进制小写签名 (64 位)
     */
    private String hmacSm3Hex;

    /**
     * 国密 HmacSM3 Base64 编码签名
     */
    private String hmacSm3Base64;

    /**
     * 恒定时间十六进制验签结果
     */
    private boolean sm3HexVerified;

    /**
     * 恒定时间 Base64 验签结果
     */
    private boolean sm3Base64Verified;

    /**
     * 载荷被篡改后是否成功拦截
     */
    private boolean sm3TamperBlocked;

    /**
     * 标准 HMAC-SHA256 十六进制签名对照
     */
    private String hmacSha256Hex;

    /**
     * 原始载荷的国密 SM3 杂凑摘要
     */
    private String sm3DigestHex;
}
