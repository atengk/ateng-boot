package io.github.atengk.boot.satoken.constant;

/**
 * Sa-Token 鉴权生态全局通用常量定义
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class SaTokenConstant {

    private SaTokenConstant() {
    }

    /**
     * 会话中存储 LoginUser 载荷的默认 Key
     */
    public static final String LOGIN_USER_KEY = "login_user";

    /**
     * 多租户复合 LoginId 默认分隔符
     */
    public static final String TENANT_DELIMITER = "_";

    /**
     * 内部微服务 Same-Token 请求头名称
     */
    public static final String SAME_TOKEN_HEADER = "SAME_TOKEN";

    /**
     * 常用登录设备端类型常量
     */
    public static final String DEVICE_PC = "PC";
    public static final String DEVICE_APP = "APP";
    public static final String DEVICE_MINI_PROGRAM = "MINI_PROGRAM";
    public static final String DEVICE_H5 = "H5";
}
