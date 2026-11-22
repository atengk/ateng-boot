package io.github.atengk.boot.satoken.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 多租户复合 LoginId 拆解结果（不可变值对象）
 * <p>
 * 用于安全解构 Sa-Token 原生 {@code loginId} 字符串，精准分离多租户归属标识与业务侧原始 {@code userId}，
 * 避免 {@code tenantId} 或 {@code userId} 本身含分隔符 {@code _} 时发生截断或串租户误判。
 * </p>
 *
 * @param tenantId   解析出的归属租户标识（无租户归属时为 {@code null}）
 * @param userId     剥离租户前缀后的业务原始用户标识（入参为空或空白时为 {@code null}）
 * @param rawLoginId 原始完整 LoginId 字符串（入参为空或空白时为 {@code null}）
 * @author Ateng
 * @since 2026-11-23
 */
public record ParsedLoginId(String tenantId, String userId, String rawLoginId) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 判断当前拆解结果是否携带有效的归属租户标识
     *
     * @return true 表示存在非空归属租户
     */
    public boolean hasTenant() {
        return tenantId != null && !tenantId.isBlank();
    }
}
