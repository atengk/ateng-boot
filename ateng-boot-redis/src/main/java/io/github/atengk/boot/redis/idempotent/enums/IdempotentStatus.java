package io.github.atengk.boot.redis.idempotent.enums;

import org.springframework.util.StringUtils;

/**
 * 业务级分布式防重幂等状态机枚举
 * 定义三态生命周期状态，支持从占位执行到终态流转
 *
 * @author Ateng
 * @since 2026-10-27
 */
public enum IdempotentStatus {

    /**
     * 执行中（占位抢占成功，业务闭包正在执行）
     */
    TRYING,

    /**
     * 执行成功（业务闭包正常返回，保留幂等占位凭据防重）
     */
    SUCCESS,

    /**
     * 执行失败（业务闭包抛出异常，当 deleteOnFailure=false 时持久化失败终态）
     */
    FAILED;

    /**
     * 安全由字符串反序列化解析为枚举
     *
     * @param status 状态字符串
     * @return 状态枚举，若未匹配则返回 null
     */
    public static IdempotentStatus fromString(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        for (IdempotentStatus value : values()) {
            if (value.name().equalsIgnoreCase(status.trim())) {
                return value;
            }
        }
        return null;
    }
}
