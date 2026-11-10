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

    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

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
        Duration timeout = resolveTimeout(request);
        try {
            CommandResponse response = rpcScenarioService.sendControlSync(request.getAction(), request.getSpeed(), timeout);
            return Result.success(response);
        } catch (Exception e) {
            return handleRpcException("[HTTP-RPC] 同步 RPC 执行失败", e);
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
        Duration timeout = resolveTimeout(request);
        return rpcScenarioService.sendControlAsync(request.getAction(), request.getSpeed(), timeout)
                .thenApply(Result::success)
                .exceptionally(ex -> handleRpcException("[HTTP-RPC] 异步 RPC 执行失败", ex));
    }

    private Duration resolveTimeout(DeviceCommandRequest request) {
        int seconds = (request != null && request.getTimeoutSeconds() != null)
                ? request.getTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS;
        return Duration.ofSeconds(seconds);
    }

    private Result<CommandResponse> handleRpcException(String logPrefix, Throwable ex) {
        Throwable target = ex;
        while (target != null && !(target instanceof MqttTimeoutException) && !(target instanceof java.util.concurrent.TimeoutException)) {
            target = target.getCause();
        }
        if (target instanceof MqttTimeoutException || target instanceof java.util.concurrent.TimeoutException) {
            String msg = (target.getMessage() != null && !target.getMessage().isBlank())
                    ? target.getMessage()
                    : (ex != null && ex.getMessage() != null ? ex.getMessage() : "请求超时");
            log.warn("{}: 设备响应超时 - {}", logPrefix, msg);
            return Result.failure(CommonResultCode.SERVICE_UNAVAILABLE, "设备指令响应超时: " + msg);
        }
        log.error("{}: {}", logPrefix, ex != null ? ex.getMessage() : "未知异常", ex);
        return Result.failure("设备指令执行失败: " + (ex != null ? ex.getMessage() : "未知异常"));
    }
}
