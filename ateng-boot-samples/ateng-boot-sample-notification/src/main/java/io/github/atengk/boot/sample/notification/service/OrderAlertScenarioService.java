package io.github.atengk.boot.sample.notification.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.annotation.NotifyAlert;
import io.github.atengk.boot.sample.notification.dto.AlertStormSuppressRequest;
import io.github.atengk.boot.sample.notification.dto.AlertTriggerRequest;
import io.github.atengk.boot.sample.notification.vo.AlertStormSummaryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 演示声明式 @NotifyAlert 异常告警拦截的业务服务
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderAlertScenarioService {

    private final AlertStormSuppressor alertStormSuppressor;
    private final ObjectProvider<OrderAlertScenarioService> selfProvider;

    /**
     * 业务订单履约处理方法，挂载声明式告警切面
     * 自动捕获未捕获异常、脱敏记录 orderId 与 password、提取 MDC traceId 并执行防风暴聚合
     *
     * @param request 告警触发请求传输对象
     * @return 履约成功响应文本
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

    /**
     * 模拟高并发故障风暴业务调用，挂载短周期 (2 秒) 的 @NotifyAlert 切面，
     * 便于快速演练三阶抑制：首发即时推送、并发静默拦截、周期末聚合通报
     *
     * @param request 告警风暴请求传输对象
     * @return 业务响应（总是抛出瞬时熔断异常）
     */
    @NotifyAlert(
            channel = "ops-alert",
            title = "故障风暴瞬时熔断异常",
            suppressWindow = 2,
            includeArgs = true
    )
    public String triggerStormOrder(AlertStormSuppressRequest request) {
        log.info("Processing storm order: orderId={}", request.getOrderId());
        throw new BusinessException("下游支付链路发生瞬时风暴熔断，故障码: STORM-503");
    }

    /**
     * 模拟高并发多线程故障风暴调用：
     * 并发触发相同异常，验证 AlertStormSuppressor 的“首发即时通报 + 窗口期原子计数拦截 + 周期末聚合通报”机制
     *
     * @param request 告警风暴请求传输对象
     * @return 统计摘要视图对象
     */
    public AlertStormSummaryVO simulateStorm(AlertStormSuppressRequest request) {
        int count = (request.getCount() != null && request.getCount() > 0) ? request.getCount() : 10;
        OrderAlertScenarioService self = selfProvider.getIfAvailable(() -> this);

        AtomicInteger totalAttempts = new AtomicInteger(0);
        AtomicReference<Throwable> capturedThrowable = new AtomicReference<>();

        // 并发多线程模拟瞬时故障风暴
        List<CompletableFuture<Void>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final int index = i;
            futures.add(CompletableFuture.runAsync(() -> {
                totalAttempts.incrementAndGet();
                try {
                    AlertStormSuppressRequest itemReq = new AlertStormSuppressRequest();
                    itemReq.setOrderId(request.getOrderId() + "-" + index);
                    itemReq.setPassword(request.getPassword());
                    itemReq.setCount(request.getCount());
                    self.triggerStormOrder(itemReq);
                } catch (Throwable ex) {
                    capturedThrowable.compareAndSet(null, ex);
                }
            }));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        Method stormMethod;
        try {
            stormMethod = OrderAlertScenarioService.class.getMethod("triggerStormOrder", AlertStormSuppressRequest.class);
        } catch (NoSuchMethodException e) {
            stormMethod = null;
        }

        Throwable ex = capturedThrowable.get();
        String fingerprint = (stormMethod != null && ex != null)
                ? AlertStormSuppressor.computeFingerprint(stormMethod, ex)
                : "unknown";

        AlertStormSuppressor.SuppressRecord record = alertStormSuppressor.getRecord(fingerprint);
        int suppressed = (record != null) ? record.getSuppressedCount() : Math.max(0, count - 1);

        return AlertStormSummaryVO.builder()
                .totalAttempts(totalAttempts.get())
                .initialDispatched(1)
                .suppressedCount(suppressed)
                .fingerprint(fingerprint)
                .suppressWindowSeconds(2L)
                .message("已成功模拟 " + count + " 次瞬时高并发异常：首发即时推送 1 次紧急红色卡片，窗口期内原子计数静默拦截 " + suppressed + " 次，2 秒窗口期满后将自动补发 1 次橙色聚合卡片")
                .build();
    }
}
