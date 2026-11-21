package io.github.atengk.boot.admin.audit;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.util.EnumUtil;

import java.util.Locale;
import java.util.Optional;

/**
 * 控制台关键安全与运维操作审计动作类型枚举
 *
 * @author Ateng
 * @since 2026-11-21
 */
public enum AdminAuditAction implements IBaseEnum<String> {

    /**
     * 控制台登录成功
     */
    LOGIN_SUCCESS("LOGIN_SUCCESS", "控制台登录成功"),

    /**
     * 控制台登录认证失败
     */
    LOGIN_FAILURE("LOGIN_FAILURE", "控制台登录失败"),

    /**
     * 登录连续失败触发防爆破锁定
     */
    LOGIN_LOCKED("LOGIN_LOCKED", "登录触发防爆破锁定"),

    /**
     * 控制台安全退出登录
     */
    LOGOUT("LOGOUT", "控制台注销登出"),

    /**
     * 实例注册准入策略或 SSRF 防火墙拒绝
     */
    ADMISSION_REJECTED("ADMISSION_REJECTED", "实例准入拒绝"),

    /**
     * 控制台端点访问守卫拦截（高危端点、只读模式或越权角色）
     */
    ENDPOINT_BLOCKED("ENDPOINT_BLOCKED", "高危端点或越权拦截"),

    /**
     * 通过控制台代理对受管实例 Actuator 发起变更写操作
     */
    ACTUATOR_PROXY_MUTATION("ACTUATOR_PROXY_MUTATION", "Actuator 代理变更操作"),

    /**
     * 手动或受控实例下线注销
     */
    INSTANCE_DEREGISTERED("INSTANCE_DEREGISTERED", "实例下线注销"),

    /**
     * 创建运行时维护静默规则
     */
    SILENCE_CREATED("SILENCE_CREATED", "创建维护静默规则"),

    /**
     * 撤销运行时维护静默规则
     */
    SILENCE_REVOKED("SILENCE_REVOKED", "撤销维护静默规则");

    private final String code;
    private final String label;

    AdminAuditAction(String code, String label) {
        this.code = code;
        this.label = label;
    }

    @Override
    public String getCode() {
        return this.code;
    }

    @Override
    public String getLabel() {
        return this.label;
    }

    /**
     * 按动作编码（大小写不敏感）解析审计动作类型
     *
     * @param code 动作编码
     * @return 匹配的枚举实例，未匹配或空白返回 empty
     */
    public static Optional<AdminAuditAction> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return EnumUtil.fromCode(AdminAuditAction.class, code.trim().toUpperCase(Locale.ROOT));
    }
}
