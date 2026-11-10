package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.mqtt.exception.MqttTimeoutException;
import io.github.atengk.boot.sample.mqtt.dto.DeviceCommandRequest;
import io.github.atengk.boot.sample.mqtt.model.CommandResponse;
import io.github.atengk.boot.sample.mqtt.service.DeviceControlRpcScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * MQTT 5.0 双向 RPC 设备下行控制体验端控制器
 * 提供同步阻塞与异步非阻塞指令下发、CorrelationData 匹配及超时转译
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/mqtt/rpc")
@RequiredArgsConstructor
public class MqttRpcController {

    private final DeviceControlRpcScenarioService rpcScenarioService;

    /**
     * 同步阻塞 RPC：向设备下发控制指令并等待回执
     *
     * @param request 控制指令请求
     * @return 设备应答回执
     */
    @PostMapping("/control-sync")
    public Result<CommandResponse> sendControlSync(@Valid @RequestBody DeviceCommandRequest request) {
        log.info("[HTTP-RPC] 接收同步阻塞 RPC 控制指令: {}", request);
        Duration timeout = Duration.ofSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 5);
        try {
            CommandResponse response = rpcScenarioService.sendControlSync(request.getAction(), request.getSpeed(), timeout);
            return Result.success(response);
        } catch (MqttTimeoutException e) {
            log.warn("[HTTP-RPC] 同步 RPC 设备响应超时: {}", e.getMessage());
            return Result.failure(CommonResultCode.SERVICE_UNAVAILABLE, "设备指令响应超时: " + e.getMessage());
        } catch (Exception e) {
            log.error("[HTTP-RPC] 同步 RPC 执行失败: {}", e.getMessage(), e);
            return Result.failure("设备指令执行失败: " + e.getMessage());
        }
    }

    /**
     * 异步非阻塞 RPC：向设备下发控制指令并通过 CompletableFuture 异步返回
     *
     * @param request 控制指令请求
     * @return 异步设备应答回执
     */
    @PostMapping("/control-async")
    public CompletableFuture<Result<CommandResponse>> sendControlAsync(@Valid @RequestBody DeviceCommandRequest request) {
        log.info("[HTTP-RPC] 接收异步非阻塞 RPC 控制指令: {}", request);
        Duration timeout = Duration.ofSeconds(request.getTimeoutSeconds() != null ? request.getTimeoutSeconds() : 5);
        return rpcScenarioService.sendControlAsync(request.getAction(), request.getSpeed(), timeout)
                .thenApply(Result::success)
                .exceptionally(ex -> {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    if (cause instanceof MqttTimeoutException) {
                        log.warn("[HTTP-RPC] 异步 RPC 设备响应超时: {}", cause.getMessage());
                        return Result.failure(CommonResultCode.SERVICE_UNAVAILABLE, "设备指令响应超时: " + cause.getMessage());
                    }
                    log.error("[HTTP-RPC] 异步 RPC 执行异常: {}", cause.getMessage(), cause);
                    return Result.failure("设备指令异步执行失败: " + cause.getMessage());
                });
    }
}
