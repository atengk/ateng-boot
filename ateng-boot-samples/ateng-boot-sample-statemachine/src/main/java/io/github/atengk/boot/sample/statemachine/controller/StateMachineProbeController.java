package io.github.atengk.boot.sample.statemachine.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.dto.OrderProbeRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.service.OrderLifecycleScenarioService;
import io.github.atengk.boot.sample.statemachine.service.OrderProbeScenarioService;
import io.github.atengk.boot.sample.statemachine.vo.OrderButtonActionVO;
import io.github.atengk.boot.sample.statemachine.vo.OrderTransitionVO;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 状态机只读预检探测与动态按钮权限控制器
 * <p>
 * 提供零副作用只读预检门面 (canFire / verifyTransition / getAvailableEvents)，
 * 强保证 0 次 Action 执行与 0 次数据库写操作，为前端控制台提供实时的动态按钮权限与流程试算推演。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/statemachine/probe")
@RequiredArgsConstructor
public class StateMachineProbeController {

    private final OrderProbeScenarioService probeService;
    private final OrderLifecycleScenarioService lifecycleService;

    /**
     * 1. 探测指定事件是否允许流转（零副作用纯只读预检，卫语句异常安全降级容错）
     *
     * @param request 预检入参
     * @return true 若满足条件允许跃迁
     */
    @PostMapping("/can-fire")
    public Result<Boolean> canFire(@Valid @RequestBody OrderProbeRequest request) {
        log.info("[ProbeController] 探测事件可行性: orderId={}, event={}", request.getOrderId(), request.getEvent());
        boolean allowed = probeService.canFire(request.getOrderId(), request.getEvent(), null);
        return Result.success(allowed);
    }

    /**
     * 2. 预检推演目标状态（试算目标状态与卫语句，绝对不执行 Action 与 DB 修改）
     *
     * @param request 预检入参
     * @return 预检推演结果视图对象
     */
    @PostMapping("/verify")
    public Result<OrderTransitionVO> verifyTransition(@Valid @RequestBody OrderProbeRequest request) {
        log.info("[ProbeController] 试算推演目标状态: orderId={}, event={}", request.getOrderId(), request.getEvent());
        TransitionResult<OrderState> result = probeService.verifyTransition(request.getOrderId(), request.getEvent());
        return Result.success(OrderTransitionVO.fromResult(request.getOrderId(), result));
    }

    /**
     * 3. 反查当前订单状态下所有合法可用事件集合
     *
     * @param orderId 订单 ID
     * @return 可用事件列表
     */
    @GetMapping("/{orderId}/available-events")
    public Result<List<OrderEvent>> getAvailableEvents(@PathVariable Long orderId) {
        log.info("[ProbeController] 反查订单可用事件: orderId={}", orderId);
        List<OrderEvent> events = probeService.getAvailableEvents(orderId, null);
        return Result.success(events);
    }

    /**
     * 4. 评估控制台动态操作按钮权限视图（前端灰化按钮支撑）
     *
     * @param orderId 订单 ID
     * @return 按钮操作权限视图对象
     */
    @GetMapping("/{orderId}/button-actions")
    public Result<OrderButtonActionVO> evaluateButtonPermissions(@PathVariable Long orderId) {
        log.info("[ProbeController] 评估订单操作按钮权限: orderId={}", orderId);
        OrderDO order = lifecycleService.getOrder(orderId);
        List<OrderEvent> availableEvents = probeService.getAvailableEvents(orderId, null);
        Map<String, Boolean> permissions = probeService.evaluateButtonPermissions(orderId);

        OrderButtonActionVO vo = OrderButtonActionVO.builder()
                .orderId(orderId)
                .status(order.getStatus())
                .availableEvents(availableEvents)
                .buttonPermissions(permissions)
                .build();
        return Result.success(vo);
    }
}
