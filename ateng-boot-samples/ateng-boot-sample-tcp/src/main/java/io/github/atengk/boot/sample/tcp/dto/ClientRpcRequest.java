package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * TCP 客户端向上行服务端发起同步或异步 RPC 请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientRpcRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 客户端注册实例名（如 default / tls-client）
     */
    @NotBlank(message = "客户端实例名 clientName 不能为空")
    @Builder.Default
    private String clientName = "default";

    /**
     * 路由命令字（如 HEARTBEAT / AUDIT_PING）
     */
    @NotBlank(message = "指令 command 不能为空")
    private String command;

    /**
     * 可选自定义关联流水号（留空时由客户端自动生成 UUID）
     */
    private String requestId;

    /**
     * 可选附加载荷键值参数
     */
    private Map<String, Object> payload;

    /**
     * 等待对端应答超时时间（毫秒）
     */
    @NotNull(message = "超时时间 timeoutMillis 不能为空")
    @Min(value = 100, message = "超时时间不能小于 100ms")
    @Max(value = 30000, message = "超时时间不能超过 30000ms")
    @Builder.Default
    private Long timeoutMillis = 3000L;

    /**
     * 是否使用异步 CompletableFuture 请求模式（requestAsync）
     */
    @Builder.Default
    private Boolean asyncMode = false;
}
