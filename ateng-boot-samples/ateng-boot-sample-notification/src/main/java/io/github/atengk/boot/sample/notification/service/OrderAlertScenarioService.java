package io.github.atengk.boot.sample.notification.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.annotation.NotifyAlert;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeoutException;

/**
 * 演示声明式 @NotifyAlert 异常告警拦截的业务服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Service
public class OrderAlertScenarioService {

    /**
     * 业务订单履约处理方法，挂载声明式告警切面
     * 自动捕获未捕获异常、脱敏记录 orderId 与 password、提取 MDC traceId 并执行防风暴聚合
     */
    @NotifyAlert(
            channel = "ops-alert",
            title = "订单履约业务未捕获异常",
            suppressWindow = 10,
            includeArgs = true,
            ignoreExceptions = {IllegalArgumentException.class}
    )
    public String processOrder(AlertTriggerRequest request) {
        log.info("Processing order: orderId={}", request.getOrderId());

        if ("npe".equalsIgnoreCase(request.getErrorType())) {
            throw new NullPointerException("订单数据不存在，支付渠道回调发生空指针异常！");
        } else if ("biz".equalsIgnoreCase(request.getErrorType())) {
            throw new BusinessException("账户可用额度不足，订单结算失败");
        } else if ("timeout".equalsIgnoreCase(request.getErrorType())) {
            throw new RuntimeException("下游支付网关通信超时", new TimeoutException("Connection timed out after 3000ms"));
        } else if ("ignore".equalsIgnoreCase(request.getErrorType())) {
            throw new IllegalArgumentException("参数格式非法，属于预期内异常，不应触发机器人告警");
        }

        return "订单 " + request.getOrderId() + " 履约成功";
    }
}
