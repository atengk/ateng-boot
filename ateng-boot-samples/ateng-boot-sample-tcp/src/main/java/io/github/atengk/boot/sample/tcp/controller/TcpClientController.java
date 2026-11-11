package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.tcp.exception.TcpTimeoutException;
import io.github.atengk.boot.sample.tcp.dto.ClientRpcRequest;
import io.github.atengk.boot.sample.tcp.dto.ClientSendRequest;
import io.github.atengk.boot.sample.tcp.service.TcpEndpointConsoleService;
import io.github.atengk.boot.sample.tcp.vo.TcpClientInfoVO;
import io.github.atengk.boot.sample.tcp.vo.TcpClientRpcVO;
import io.github.atengk.boot.sample.tcp.vo.TcpClientSendVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * TCP 多实例托管客户端体验控制器
 * 演示命名客户端（default / binary-client / tls-client）单向推流、同步/异步上行 RPC 以及断线手动重连/断开控制
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/tcp/client")
@RequiredArgsConstructor
public class TcpClientController {

    private final TcpEndpointConsoleService endpointConsoleService;

    /**
     * 查询所有已装配托管 TCP 客户端运行态快照
     *
     * @return 客户端只读视图列表
     */
    @GetMapping("/list")
    public Result<List<TcpClientInfoVO>> listClients() {
        return Result.success(endpointConsoleService.listClients());
    }

    /**
     * 通过指定命名客户端（如 {@code default} / {@code binary-client} / {@code tls-client}）单向推流发送报文
     *
     * @param request 客户端推流请求
     * @return 推流回执视图
     */
    @PostMapping("/send")
    public Result<TcpClientSendVO> send(@Valid @RequestBody ClientSendRequest request) {
        log.info("[HTTP-CLIENT] 客户端推流: clientName={}, type={}", request.getClientName(), request.getPayloadType());
        return Result.success(endpointConsoleService.sendByClient(request));
    }

    /**
     * 通过指定命名客户端向上行服务端发起同步阻塞 RPC 请求并等待应答
     *
     * @param request RPC 请求参数
     * @return 上行应答视图
     */
    @PostMapping("/request")
    public Result<TcpClientRpcVO> requestSync(@Valid @RequestBody ClientRpcRequest request) {
        log.info("[HTTP-CLIENT] 客户端同步 RPC: clientName={}, command={}", request.getClientName(), request.getCommand());
        return Result.success(endpointConsoleService.requestSync(request));
    }

    /**
     * 通过指定命名客户端向上行服务端发起异步非阻塞 RPC 请求
     *
     * @param request RPC 请求参数
     * @return 异步包装结果
     */
    @PostMapping("/request-async")
    public CompletableFuture<Result<TcpClientRpcVO>> requestAsync(@Valid @RequestBody ClientRpcRequest request) {
        log.info("[HTTP-CLIENT] 客户端异步 RPC: clientName={}, command={}", request.getClientName(), request.getCommand());
        return endpointConsoleService.requestAsync(request)
                .thenApply(Result::success)
                .exceptionally(ex -> {
                    Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
                    log.warn("[HTTP-CLIENT] 客户端异步 RPC 执行异常: clientName={}, command={}, error={}",
                            request.getClientName(), request.getCommand(), cause.getMessage());
                    if (cause instanceof BusinessException be) {
                        return Result.fail(be.getCode(), be.getMessage());
                    }
                    if (cause instanceof TimeoutException || cause instanceof TcpTimeoutException) {
                        return Result.fail(CommonResultCode.SERVICE_UNAVAILABLE, "异步 TCP RPC 等待对端应答超时");
                    }
                    return Result.fail(CommonResultCode.INTERNAL_SERVER_ERROR, "异步 TCP RPC 执行失败");
                });
    }

    /**
     * 触发指定客户端重新建立连接
     *
     * @param clientName 客户端实例名称
     * @return 重连后最新状态视图
     */
    @PostMapping("/reconnect")
    public Result<TcpClientInfoVO> reconnect(
            @NotBlank(message = "客户端名称 clientName 不能为空")
            @RequestParam(value = "clientName", defaultValue = "default") String clientName) {
        log.info("[HTTP-CLIENT] 手动重连客户端: {}", clientName);
        return Result.success(endpointConsoleService.reconnectClient(clientName));
    }

    /**
     * 主动断开指定客户端与远程服务端的长连接
     *
     * @param clientName 客户端实例名称
     * @return 断开后最新状态视图
     */
    @PostMapping("/disconnect")
    public Result<TcpClientInfoVO> disconnect(
            @NotBlank(message = "客户端名称 clientName 不能为空")
            @RequestParam(value = "clientName", defaultValue = "default") String clientName) {
        log.info("[HTTP-CLIENT] 手动断开客户端: {}", clientName);
        return Result.success(endpointConsoleService.disconnectClient(clientName));
    }
}
