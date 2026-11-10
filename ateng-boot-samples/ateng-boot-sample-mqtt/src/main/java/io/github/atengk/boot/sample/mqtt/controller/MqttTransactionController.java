package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mqtt.dto.TxTelemetryRequest;
import io.github.atengk.boot.sample.mqtt.service.TransactionalTelemetryScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 事务感知协同发信与防脏读体验端控制器
 * 提供事务提交后发信（sendAfterCommit）与事务回滚取消发信体验
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/mqtt/transaction")
@RequiredArgsConstructor
public class MqttTransactionController {

    private final TransactionalTelemetryScenarioService transactionalScenarioService;

    /**
     * 事务感知遥测上报
     *
     * @param request 事务发信请求
     * @return 执行结果说明
     */
    @PostMapping("/record-telemetry")
    public Result<String> recordTelemetry(@Valid @RequestBody TxTelemetryRequest request) {
        log.info("[HTTP-TRANSACTION] 接收事务遥测发信请求: {}", request);
        if (request.isShouldRollback()) {
            transactionalScenarioService.recordTelemetryWithRollback(
                    request.getDeviceId(),
                    request.getTemp(),
                    request.getHumidity()
            );
            return Result.success("模拟事务回滚，发信钩子已安全取消，下游零脏读");
        } else {
            transactionalScenarioService.recordTelemetryWithCommit(
                    request.getDeviceId(),
                    request.getTemp(),
                    request.getHumidity()
            );
            return Result.success("事务已成功提交并触发 sendAfterCommit 投递");
        }
    }
}
