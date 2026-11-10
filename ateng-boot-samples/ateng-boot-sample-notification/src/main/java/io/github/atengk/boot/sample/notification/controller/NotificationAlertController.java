package io.github.atengk.boot.sample.notification.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.notification.dto.AlertStormSuppressRequest;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import io.github.atengk.boot.sample.notification.service.OrderAlertScenarioService;
import io.github.atengk.boot.sample.notification.vo.AlertStormSummaryVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协同通知异常告警切面与三阶防风暴聚合控制器
 * 提供声明式 @NotifyAlert 异常拦截、敏感字段脱敏及 AlertStormSuppressor 三阶抗洪演示接口
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/notification/alert")
@RequiredArgsConstructor
public class NotificationAlertController {

    private final OrderAlertScenarioService alertScenarioService;

    /**
     * 触发模拟订单处理业务异常，演示 @NotifyAlert 切面捕获、Top 10 调用栈提取与入参敏感脱敏
     *
     * @param request 告警触发请求传输对象
     * @return 业务结果响应（抛出异常时由全局异常处理器拦截并包装返回）
     */
    @PostMapping("/trigger")
    public Result<String> triggerAlert(@Valid @RequestBody AlertTriggerRequest request) {
        log.info("协同通知: 触发声明式 @NotifyAlert 异常告警拦截演练 [orderId={}, errorType={}]",
                request.getOrderId(), request.getErrorType());
        String outcome = alertScenarioService.processOrder(request);
        return Result.success(outcome);
    }

    /**
     * 高并发多线程模拟瞬时故障风暴，演示三阶抑制机制
     * （首发即时推送红色紧急卡片、窗口期原子计数静默拦截、周期末补发橙色汇总聚合卡片）
     *
     * @param request 告警风暴模拟请求传输对象
     * @return 告警风暴三阶抑制执行结果视图
     */
    @PostMapping("/storm-suppress")
    public Result<AlertStormSummaryVO> simulateStormSuppress(@Valid @RequestBody(required = false) AlertStormSuppressRequest request) {
        if (request == null) {
            request = new AlertStormSuppressRequest();
        }
        log.info("协同通知: 触发告警风暴三阶抑制演练 [orderId={}, count={}]",
                request.getOrderId(), request.getCount());
        AlertStormSummaryVO summary = alertScenarioService.simulateStorm(request);
        return Result.success(summary);
    }
}
