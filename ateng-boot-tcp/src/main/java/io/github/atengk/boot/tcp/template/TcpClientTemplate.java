package io.github.atengk.boot.tcp.template;

import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.tcp.client.TcpClient;
import io.github.atengk.boot.tcp.correlation.TcpRequestCorrelationManager;
import io.github.atengk.boot.tcp.exception.TcpException;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.tcp.model.TcpPacket;
import io.github.atengk.boot.tcp.model.TcpPayload;
import io.github.atengk.boot.tcp.properties.TcpClientProperties;
import io.github.atengk.boot.tcp.session.TcpSession;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * TCP 客户端核心操作门面
 * <p>
 * 提供长连接单向主动发信、全双工“请求-响应 (Request-Response)”RPC 等待、自愈重连与连接状态探活。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class TcpClientTemplate {

    private static final Logger log = LoggerFactory.getLogger(TcpClientTemplate.class);

    private final TcpClient client;
    private final TcpClientProperties properties;
    private final TcpRequestCorrelationManager correlationManager;

    public TcpClientTemplate(TcpClient client, TcpClientProperties properties) {
        this(client, properties, client != null ? client.getCorrelationManager() : null);
    }

    public TcpClientTemplate(TcpClient client,
                             TcpClientProperties properties,
                             TcpRequestCorrelationManager correlationManager) {
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.properties = properties != null ? properties : client.getProperties();
        this.correlationManager = correlationManager != null ? correlationManager : client.getCorrelationManager();
    }

    /**
     * 单向向服务端发送通信载荷
     *
     * @param payload 报文载荷（支持 TcpPacket, TcpPayload, byte[], String 或 POJO）
     */
    public void send(Object payload) {
        if (!client.isConnected()) {
            throw new IllegalStateException("TCP 客户端尚未建立活跃连接，无法发送消息");
        }
        Object msg = wrapPayload(payload);
        client.getChannel().writeAndFlush(msg);
    }

    /**
     * 发起全双工同步请求并等待对端应答（使用配置中的默认 requestTimeout）
     *
     * @param payload 请求报文
     * @return 对端响应报文
     */
    public TcpPacket request(Object payload) {
        Duration timeout = properties != null && properties.getRequestTimeout() != null
                ? properties.getRequestTimeout()
                : Duration.ofSeconds(10);
        return request(payload, timeout);
    }

    /**
     * 发起全双工同步请求并在指定超时窗口内等待对端应答
     *
     * @param payload 请求报文
     * @param timeout 超时时长
     * @return 对端响应报文
     */
    public TcpPacket request(Object payload, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为空");
        CompletableFuture<TcpPacket> future = requestAsync(payload, timeout);
        try {
            return future.get(timeout.toMillis() + 1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new TcpException("TCP RPC 请求等待中断: " + ie.getMessage(), ie);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
            if (cause instanceof TcpException te) {
                throw te;
            }
            throw new TcpException("TCP RPC 请求执行异常: " + cause.getMessage(), cause);
        } catch (TimeoutException te) {
            throw new TcpTimeoutException("TCP 请求调用超时: timeout=" + timeout, te);
        }
    }

    /**
     * 发起全双工同步请求并等待强类型对端应答（使用默认 requestTimeout）
     *
     * @param payload      请求报文
     * @param responseType 响应目标类型
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T request(Object payload, Class<T> responseType) {
        return request(payload, responseType, null);
    }

    /**
     * 发起全双工同步请求并在指定超时窗口内等待强类型对端应答
     *
     * @param payload      请求报文
     * @param responseType 响应目标类型
     * @param timeout      超时时长
     * @param <T>          响应强类型
     * @return 对端响应对象
     */
    public <T> T request(Object payload, Class<T> responseType, Duration timeout) {
        Objects.requireNonNull(responseType, "responseType 不能为空");
        TcpPacket responsePacket = timeout != null ? request(payload, timeout) : request(payload);
        if (responsePacket == null || responsePacket.getPayload() == null) {
            return null;
        }
        if (TcpPacket.class.equals(responseType)) {
            return responseType.cast(responsePacket);
        }
        if (TcpPayload.class.equals(responseType)) {
            return responseType.cast(responsePacket.getPayload());
        }
        if (byte[].class.equals(responseType)) {
            return responseType.cast(responsePacket.getPayload().asBytes());
        }
        if (String.class.equals(responseType)) {
            String str = responsePacket.getPayload().asString();
            if (str.endsWith("\r\n")) {
                str = str.substring(0, str.length() - 2);
            } else if (str.endsWith("\n")) {
                str = str.substring(0, str.length() - 1);
            }
            if (str.startsWith("{") && str.contains("\"data\"")) {
                try {
                    com.fasterxml.jackson.databind.JsonNode node = JsonUtil.parseObject(str, com.fasterxml.jackson.databind.JsonNode.class);
                    if (node != null && node.has("data")) {
                        return (T) node.get("data").asText();
                    }
                } catch (Exception ignored) {
                }
            }
            return (T) str;
        }
        return responsePacket.getPayload().asJson(responseType);
    }

    /**
     * 发起全双工异步 RPC 请求（使用默认 requestTimeout）
     *
     * @param payload 请求报文
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestAsync(Object payload) {
        Duration timeout = properties != null && properties.getRequestTimeout() != null
                ? properties.getRequestTimeout()
                : Duration.ofSeconds(10);
        return requestAsync(payload, timeout);
    }

    /**
     * 发起全双工异步 RPC 请求并在指定超时窗口内等待响应
     *
     * @param payload 请求报文
     * @param timeout 超时时长
     * @return 响应 Future
     */
    public CompletableFuture<TcpPacket> requestAsync(Object payload, Duration timeout) {
        if (!client.isConnected()) {
            throw new IllegalStateException("TCP 客户端尚未建立活跃连接，无法发送请求");
        }
        if (correlationManager == null) {
            throw new IllegalStateException("未配置 TcpRequestCorrelationManager，无法发起全双工 RPC 请求");
        }

        TcpPacket packet = toTcpPacket(payload);
        String requestId = packet.getRequestId();

        CompletableFuture<TcpPacket> future = correlationManager.register(requestId, timeout);

        client.getChannel().writeAndFlush(packet).addListener(f -> {
            if (!f.isSuccess()) {
                correlationManager.completeExceptionally(requestId, f.cause());
            }
        });

        return future;
    }

    /**
     * 当前客户端是否已连接
     *
     * @return true 表示已连接
     */
    public boolean isConnected() {
        return client.isConnected();
    }

    /**
     * 手动触发客户端重连
     */
    public void reconnect() {
        client.reconnect();
    }

    /**
     * 主动断开当前客户端长连接
     */
    public void disconnect() {
        client.disconnect();
    }

    /**
     * 获取底层 Netty 通道实例
     *
     * @return 通道实例
     */
    public Channel getChannel() {
        return client.getChannel();
    }

    /**
     * 获取客户端领域会话
     *
     * @return 会话实体
     */
    public TcpSession getSession() {
        return client.getSession();
    }

    /**
     * 获取底层客户端引擎实例
     *
     * @return 客户端引擎
     */
    public TcpClient getClient() {
        return client;
    }

    /**
     * 获取相关性管理器实例
     *
     * @return 相关性管理器
     */
    public TcpRequestCorrelationManager getCorrelationManager() {
        return correlationManager;
    }

    private TcpPacket toTcpPacket(Object payload) {
        if (payload instanceof TcpPacket packet) {
            if (packet.getRequestId() == null || packet.getRequestId().isBlank()) {
                packet.setRequestId(IdUtil.fastSimpleUUID());
            }
            if (packet.getPayload() == null || packet.getPayload().isEmpty()) {
                packet.setPayload(TcpPayload.fromJson(packet));
            }
            return packet;
        }
        String requestId = IdUtil.fastSimpleUUID();
        if (payload instanceof TcpPayload tp) {
            return TcpPacket.of(null, requestId, tp);
        }
        if (payload instanceof byte[] bytes) {
            return TcpPacket.of(null, requestId, TcpPayload.of(bytes));
        }
        if (payload instanceof String text) {
            return TcpPacket.of(null, requestId, TcpPayload.of(text));
        }
        return TcpPacket.of(null, requestId, TcpPayload.fromJson(payload));
    }

    private Object wrapPayload(Object payload) {
        if (payload == null) {
            return TcpPayload.empty();
        }
        if (payload instanceof TcpPacket || payload instanceof TcpPayload ||
                payload instanceof byte[] || payload instanceof String) {
            return payload;
        }
        return TcpPayload.fromJson(payload);
    }
}
