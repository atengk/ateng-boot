package io.github.atengk.boot.realtime.auth;

import java.util.Map;

/**
 * 实时通信握手身份认证与凭据解析 SPI 扩展点
 * <p>
 * 接收自适应提取出的凭据 Token，执行登录态校验并返回对应的身份载荷；
 * 当返回 null 或抛出异常时，握手拦截器将判定鉴权失败并拒绝连接。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-21
 */
@FunctionalInterface
public interface RealtimeAuthHandshakeProvider {

    /**
     * 执行凭据认证与身份解析
     *
     * @param token 提取到的凭据令牌（可能为 null 或空）
     * @return 身份解析载荷（为 null 表示认证失败）
     */
    RealtimeAuthPayload authenticate(String token);

    /**
     * 执行凭据认证与身份解析（带上下文扩展）
     *
     * @param token      提取到的凭据令牌
     * @param attributes 握手扩展属性上下文
     * @return 身份解析载荷（为 null 表示认证失败）
     */
    default RealtimeAuthPayload authenticate(String token, Map<String, Object> attributes) {
        return authenticate(token);
    }
}
