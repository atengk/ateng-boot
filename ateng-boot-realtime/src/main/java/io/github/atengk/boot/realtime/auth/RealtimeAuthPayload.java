package io.github.atengk.boot.realtime.auth;

import io.github.atengk.boot.realtime.model.SessionKey;
import lombok.Builder;
import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 实时通信握手身份认证解析载荷
 * <p>
 * 封装由认证提供者（如 Sa-Token）解析出的用户标识、设备类型、租户标识及扩展属性。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
@Builder
public class RealtimeAuthPayload implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 认证通过的用户唯一标识
     */
    private final String userId;

    /**
     * 客户端设备端类型（如 PC, APP, WEB，默认: default）
     */
    @Builder.Default
    private final String deviceType = SessionKey.DEFAULT_DEVICE_TYPE;

    /**
     * 多租户标识（可空）
     */
    private final Long tenantId;

    /**
     * 是否为匿名访客
     */
    private final boolean anonymous;

    /**
     * 扩展身份属性映射
     */
    @Builder.Default
    private final Map<String, Object> extraAttributes = Collections.emptyMap();

    /**
     * 构建认证通过的用户载荷
     *
     * @param userId 用户标识
     * @return 身份载荷
     */
    public static RealtimeAuthPayload of(Object userId) {
        return of(userId, SessionKey.DEFAULT_DEVICE_TYPE, null);
    }

    /**
     * 构建认证通过的用户载荷（完整属性）
     *
     * @param userId     用户标识
     * @param deviceType 设备端类型
     * @param tenantId   多租户标识
     * @return 身份载荷
     */
    public static RealtimeAuthPayload of(Object userId, String deviceType, Long tenantId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return RealtimeAuthPayload.builder()
                .userId(String.valueOf(userId))
                .deviceType((deviceType != null && !deviceType.isBlank()) ? deviceType.trim() : SessionKey.DEFAULT_DEVICE_TYPE)
                .tenantId(tenantId)
                .anonymous(false)
                .extraAttributes(Collections.emptyMap())
                .build();
    }

    /**
     * 构建匿名访客身份载荷
     *
     * @param clientId 客户端会话唯一标识
     * @return 匿名身份载荷
     */
    public static RealtimeAuthPayload anonymous(String clientId) {
        String actualClientId = (clientId != null && !clientId.isBlank()) ? clientId.trim() : "anon";
        String anonymousUserId = SessionKey.ANONYMOUS_USER_PREFIX + actualClientId;
        return RealtimeAuthPayload.builder()
                .userId(anonymousUserId)
                .deviceType(SessionKey.DEFAULT_DEVICE_TYPE)
                .tenantId(null)
                .anonymous(true)
                .extraAttributes(Collections.emptyMap())
                .build();
    }
}
