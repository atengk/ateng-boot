package io.github.atengk.boot.realtime.model;

import lombok.Getter;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * 用户端点会话唯一路由标识对象
 * <p>
 * 由用户标识（userId）、设备类型（deviceType）与客户端会话标识（clientId）构成的确定性三维坐标，
 * 完美支持同一用户在多端（PC/Mobile）或同设备多浏览器标签页（Multi-Tab）的并发独立路由。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Getter
public final class SessionKey implements Serializable, Comparable<SessionKey> {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 默认设备类型标识
     */
    public static final String DEFAULT_DEVICE_TYPE = "default";

    /**
     * 匿名访客用户标识前缀
     */
    public static final String ANONYMOUS_USER_PREFIX = "anonymous:";

    /**
     * 归属用户唯一标识（转为字符串统一存储）
     */
    private final String userId;

    /**
     * 客户端设备类型（如 pc, mobile, web, app，默认: default）
     */
    private final String deviceType;

    /**
     * 客户端端点会话唯一编号（如 WebSocket SessionId 或 SSE clientId）
     */
    private final String clientId;

    /**
     * 私有构造函数，保证对象不可变性
     */
    private SessionKey(String userId, String deviceType, String clientId) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.deviceType = (deviceType != null && !deviceType.isBlank()) ? deviceType.trim() : DEFAULT_DEVICE_TYPE;
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null").trim();
    }

    /**
     * 构建三维会话路由坐标
     *
     * @param userId     用户标识
     * @param deviceType 设备类型
     * @param clientId   客户端会话编号
     * @return 会话坐标实例
     */
    public static SessionKey of(Object userId, String deviceType, String clientId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return new SessionKey(String.valueOf(userId), deviceType, clientId);
    }

    /**
     * 构建带有默认设备类型的会话路由坐标
     *
     * @param userId   用户标识
     * @param clientId 客户端会话编号
     * @return 会话坐标实例
     */
    public static SessionKey of(Object userId, String clientId) {
        return of(userId, DEFAULT_DEVICE_TYPE, clientId);
    }

    /**
     * 构建匿名访客会话路由坐标
     *
     * @param clientId 客户端会话编号
     * @return 匿名会话坐标实例
     */
    public static SessionKey anonymous(String clientId) {
        return of(ANONYMOUS_USER_PREFIX + clientId, DEFAULT_DEVICE_TYPE, clientId);
    }

    /**
     * 判断当前会话是否为匿名访客连接
     *
     * @return 是否匿名
     */
    public boolean isAnonymous() {
        return userId.startsWith(ANONYMOUS_USER_PREFIX);
    }

    /**
     * 格式化输出标准化唯一路由 Key（格式: userId:deviceType:clientId）
     *
     * @return 路由键字符串
     */
    public String format() {
        return userId + ":" + deviceType + ":" + clientId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        SessionKey that = (SessionKey) o;
        return Objects.equals(userId, that.userId)
                && Objects.equals(deviceType, that.deviceType)
                && Objects.equals(clientId, that.clientId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, deviceType, clientId);
    }

    @Override
    public String toString() {
        return format();
    }

    @Override
    public int compareTo(SessionKey other) {
        if (other == null) {
            return 1;
        }
        return this.format().compareTo(other.format());
    }
}
