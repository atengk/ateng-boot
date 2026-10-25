package io.github.atengk.boot.tcp.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一标准 TCP 领域通信数据包
 * <p>
 * 封装业务命令字（command）、全双工关联流水号（requestId）与安全载荷（payload）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
@Builder
public class TcpPacket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务命令字 / 操作指令码（如 "0x0100"、"HEARTBEAT"、"LOGIN" 等）
     */
    private String command;

    /**
     * 消息关联流水号（用于全双工请求-响应 RPC 闭环匹配与链路追踪，可为空）
     */
    private String requestId;

    /**
     * 报文安全数据载荷
     */
    @Builder.Default
    private TcpPayload payload = TcpPayload.empty();

    /**
     * 扩展报文头字典（可选的元数据属性）
     */
    @Builder.Default
    private Map<String, Object> headers = new LinkedHashMap<>();

    public TcpPacket() {
        this.payload = TcpPayload.empty();
        this.headers = new LinkedHashMap<>();
    }

    public TcpPacket(String command, String requestId, TcpPayload payload, Map<String, Object> headers) {
        this.command = command;
        this.requestId = requestId;
        this.payload = payload != null ? payload : TcpPayload.empty();
        this.headers = headers != null ? headers : new LinkedHashMap<>();
    }

    /**
     * 快速构建仅包含载荷的通用数据包
     *
     * @param payload 载荷
     * @return TcpPacket 实例
     */
    public static TcpPacket of(TcpPayload payload) {
        return of(null, null, payload);
    }

    /**
     * 快速构建命令字与载荷数据包
     *
     * @param command 业务命令字
     * @param payload 载荷
     * @return TcpPacket 实例
     */
    public static TcpPacket of(String command, TcpPayload payload) {
        return of(command, null, payload);
    }

    /**
     * 快速构建完整命令字、关联号与载荷数据包
     *
     * @param command   业务命令字
     * @param requestId 消息关联流水号
     * @param payload   载荷
     * @return TcpPacket 实例
     */
    public static TcpPacket of(String command, String requestId, TcpPayload payload) {
        return TcpPacket.builder()
                .command(command)
                .requestId(requestId)
                .payload(payload)
                .build();
    }

    /**
     * 设置自定义标头键值
     *
     * @param key   标头键名
     * @param value 标头数值
     * @return this
     */
    public TcpPacket setHeader(String key, Object value) {
        if (this.headers == null) {
            this.headers = new LinkedHashMap<>();
        }
        this.headers.put(key, value);
        return this;
    }

    /**
     * 获取标头数值
     *
     * @param key 标头键名
     * @return 标头数值
     */
    public Object getHeader(String key) {
        return this.headers != null ? this.headers.get(key) : null;
    }

    /**
     * 获取指定类型的标头数值
     *
     * @param key         标头键名
     * @param targetClass 目标类型
     * @param <T>         泛型对象
     * @return 强类型标头数值
     */
    @SuppressWarnings("unchecked")
    public <T> T getHeader(String key, Class<T> targetClass) {
        Object val = getHeader(key);
        if (val == null) {
            return null;
        }
        if (targetClass.isInstance(val)) {
            return (T) val;
        }
        return null;
    }

    /**
     * 是否包含特定标头
     *
     * @param key 标头键名
     * @return 是否包含
     */
    public boolean hasHeader(String key) {
        return this.headers != null && this.headers.containsKey(key);
    }

    @Override
    public String toString() {
        return "TcpPacket[cmd=" + command + ", reqId=" + requestId + ", payload=" + payload + "]";
    }
}
