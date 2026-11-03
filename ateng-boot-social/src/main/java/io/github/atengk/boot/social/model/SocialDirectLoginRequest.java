package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 社交凭证直传登录请求对象
 * <p>
 * 适用于微信小程序 (wx.login code2Session) 或 App 原生 SDK 授权码直传后端换码登录。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDirectLoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 授权凭证 code（小程序 js_code 或 App 授权凭据）
     */
    @NotBlank(message = "授权凭证 code 不能为空")
    private String code;

    /**
     * 客户端设备类型（如 "PC", "MOBILE", "MINI_PROGRAM"）
     */
    private String deviceType;

    /**
     * 业务登录类型/账号标识（多账号体系隔离）
     */
    private String loginType;
}
