package io.github.atengk.boot.social.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 已绑定第三方社交账号视图模型
 * <p>
 * 支撑个人中心展示已绑定的第三方社交账号详情（如应用标识、平台来源、跨端 openId/unionId、第三方账号画像及绑定时间戳）。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SocialBoundAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 社交应用唯一标识（如 wechat-mp, github-main 等）
     */
    private String appKey;

    /**
     * 社交平台来源（如 GITHUB, WECHAT_OPEN, WECHAT_MP, QQ 等）
     */
    private String source;

    /**
     * 第三方应用级用户唯一标识（如微信 openid、GitHub user id 等）
     */
    private String openId;

    /**
     * 第三方开发者平台级用户唯一标识（如微信 unionid）
     */
    private String unionId;

    /**
     * 第三方平台用户名 / 账号
     */
    private String username;

    /**
     * 第三方平台用户昵称
     */
    private String nickname;

    /**
     * 用户头像地址
     */
    private String avatar;

    /**
     * 账号绑定时间戳
     */
    private Instant boundAt;
}
