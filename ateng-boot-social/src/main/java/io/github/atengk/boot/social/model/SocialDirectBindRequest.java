package io.github.atengk.boot.social.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 个人中心已登录态直接绑定第三方社交账号请求契约
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialDirectBindRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前已登录的本地系统用户唯一标识
     */
    @NotBlank(message = "本地用户标识 userId 不能为空")
    private String userId;

    /**
     * 第三方平台返回的授权凭据 code（如微信小程序 js_code、OAuth2 回调换码 code 等）
     */
    @NotBlank(message = "授权凭证 code 不能为空")
    private String code;

    /**
     * 防伪状态码 State（可选，Web 重定向场景用于防伪校验与业务上下文恢复）
     */
    private String state;
}
