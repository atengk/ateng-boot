package io.github.atengk.boot.social.model;

import io.github.atengk.boot.core.enums.IBaseEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 社交登录认证结果状态枚举
 * <p>
 * 用于标识用户在完成第三方社交平台认证后的本地账号流转状态，实现 {@link IBaseEnum} 强类型通用枚举契约。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
@AllArgsConstructor
public enum SocialAuthState implements IBaseEnum<String> {

    /**
     * 认证成功且已关联本地用户，已正常签发登录会话
     */
    LOGGED_IN("LOGGED_IN", "已认证并签发会话"),

    /**
     * 第三方认证通过但尚未关联本地用户，已签发两阶段绑定票据，需引导绑定或注册
     */
    REQUIRE_BIND("REQUIRE_BIND", "待两阶段绑定");

    private final String code;
    private final String label;

    public String getDescription() {
        return label;
    }
}
