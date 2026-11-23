package io.github.atengk.boot.realtime.constant;

import org.springframework.web.socket.CloseStatus;

/**
 * 实时通信标准扩展关闭状态码契约常量定义
 * <p>
 * 遵循 RFC 6455 规范，4000-4999 区间由应用层框架专有定义，1000-2999 区间遵循标准 WebSocket 规范。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
public final class RealtimeCloseStatus {

    private RealtimeCloseStatus() {
    }

    /**
     * 正常关闭 (1000)
     */
    public static final CloseStatus NORMAL = CloseStatus.NORMAL;

    /**
     * 服务端重启 / 优雅停机排空 (1012)
     * <p>
     * 客户端收到此状态码时，应执行带随机抖动（Jitter）的指数退避重连，避免重连雪崩。
     * </p>
     */
    public static final CloseStatus SERVICE_RESTART = new CloseStatus(1012, "Service Restart");

    /**
     * 握手鉴权失败或 Token 无效 (4001)
     * <p>
     * 客户端收到此状态码时，严禁死循环自动重连，应跳转登录页或重新获取有效凭据。
     * </p>
     */
    public static final CloseStatus AUTH_FAILED = new CloseStatus(4001, "Authentication Failed");

    /**
     * 用户被管理员踢下线或单端登录被挤下线 (4002)
     * <p>
     * 客户端收到此状态码时，应弹出提示并不再自动重连。
     * </p>
     */
    public static final CloseStatus USER_KICKED_OUT = new CloseStatus(4002, "User Kicked Out");

    /**
     * 单用户连接数超过配额上限 (4003)
     */
    public static final CloseStatus MAX_CONNECTIONS_EXCEEDED = new CloseStatus(4003, "Max Connections Exceeded");

    /**
     * 空闲超时排空假死连接 (4004)
     */
    public static final CloseStatus IDLE_TIMEOUT = new CloseStatus(4004, "Idle Timeout");

    /**
     * 上行流量超限熔断排空 (4005)
     * <p>
     * 客户端发送频率持续严重超出服务端限流阈值，服务端主动断开长连接以防范 DoS 攻击。
     * </p>
     */
    public static final CloseStatus RATE_LIMIT_EXCEEDED = new CloseStatus(4005, "Rate Limit Exceeded");
}
