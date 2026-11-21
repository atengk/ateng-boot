package io.github.atengk.boot.admin.silence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.time.Instant;
import java.util.Locale;

/**
 * 运维变更动态维护静默窗口规则模型
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SilenceRule implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 静默规则唯一标识
     */
    private String id;

    /**
     * 服务名匹配模式（支持精确匹配或通配符，如 order-*）
     */
    private String servicePattern;

    /**
     * 维护静默操作原因说明
     */
    private String reason;

    /**
     * 创建人或操作账号标识
     */
    private String operator;

    /**
     * 规则创建时间
     */
    private Instant createdAt;

    /**
     * 规则到期失效时间（为空表示永不过期）
     */
    private Instant expiresAt;

    /**
     * 判断当前静默规则在指定时间点是否已过期
     *
     * @param now 当前参考时间
     * @return 已过期返回 {@code true}
     */
    public boolean isExpired(Instant now) {
        if (expiresAt == null || now == null) {
            return false;
        }
        return !now.isBefore(expiresAt);
    }

    /**
     * 判断当前未过期规则是否匹配目标服务名
     *
     * @param serviceName 目标服务名
     * @param now         当前参考时间
     * @return 规则有效且匹配服务名时返回 {@code true}
     */
    public boolean matches(String serviceName, Instant now) {
        if (!StringUtils.hasText(servicePattern) || !StringUtils.hasText(serviceName) || isExpired(now)) {
            return false;
        }
        String pattern = servicePattern.trim().toLowerCase(Locale.ROOT);
        String target = serviceName.trim().toLowerCase(Locale.ROOT);
        return pattern.equals(target) || PatternMatchUtils.simpleMatch(pattern, target);
    }
}
