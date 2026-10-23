package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import io.github.atengk.boot.sample.notification.service.OrderAlertScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 声明式异常告警切面与防风暴抑制场景演示控制器
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/alert")
@RequiredArgsConstructor
public class AlertDemoController {

    private final OrderAlertScenarioService alertScenarioService;

    /**
     * 触发模拟订单处理业务，演示 @NotifyAlert 切面捕获与防告警风暴三阶抑制
     */
    @PostMapping("/trigger")
    public Result<String> triggerAlert(@Valid @RequestBody AlertTriggerRequest request) {
        String outcome = alertScenarioService.processOrder(request);
        return Result.success(outcome);
    }
}
