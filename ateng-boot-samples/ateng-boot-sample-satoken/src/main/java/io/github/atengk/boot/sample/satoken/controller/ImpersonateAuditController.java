package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.listener.SampleSecurityEventListener;
import io.github.atengk.boot.sample.satoken.service.SaTokenSampleService;
import io.github.atengk.boot.sample.satoken.vo.ImpersonateStatusVO;
import io.github.atengk.boot.sample.satoken.vo.SecurityEventVO;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 身份模拟与全景安全审计事件控制器
 * 提供客服持久会话切换 (switchTo / endSwitch)、瞬态代码块模拟 (runAs) 客诉订单补偿及 Spring 原生安全事件流水查询端点
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/impersonate")
@RequiredArgsConstructor
public class ImpersonateAuditController {

    private final SaTokenSampleService sampleService;
    private final SampleSecurityEventListener securityEventListener;

    /**
     * 开启客服持久会话切换（模拟目标用户视角排查客诉）
     *
     * @param targetUserId 目标客户用户标识
     * @return 业务结果
     */
    @PostMapping("/switch")
    public Result<String> switchTo(@NotBlank(message = "目标用户标识不能为空") @RequestParam("targetUserId") String targetUserId) {
        LoginHelper.switchTo(targetUserId);
        return Result.success("会话已切换至目标用户: " + targetUserId);
    }

    /**
     * 结束会话模拟，恢复原始操作人真实身份
     *
     * @return 业务结果
     */
    @PostMapping("/end-switch")
    public Result<String> endSwitch() {
        LoginHelper.endSwitch();
        return Result.success("已结束会话模拟，恢复原始身份");
    }

    /**
     * 查询当前会话模拟状态与真实操作人
     *
     * @return 身份模拟状态视图对象
     */
    @GetMapping("/status")
    public Result<ImpersonateStatusVO> getImpersonateStatus() {
        ImpersonateStatusVO vo = ImpersonateStatusVO.builder()
                .isRunAs(LoginHelper.isRunAs())
                .currentUserId(LoginHelper.getUserId())
                .realUserId(LoginHelper.getRealUserId())
                .originalUserId(LoginHelper.getOriginalUserId())
                .build();
        return Result.success(vo);
    }

    /**
     * 基于 LoginHelper.runAs 函数式瞬态模拟执行客诉订单补偿
     * 栈式保护执行块内生效为目标用户，代码块执行完毕后在 finally 中确定性自动恢复原始身份
     *
     * @param targetUserId 目标补偿客诉用户
     * @param orderNo      补偿订单编号
     * @param amount       补偿金额
     * @return 订单持久化标识
     */
    @PostMapping("/compensate-order")
    public Result<Long> compensateOrder(
            @NotBlank(message = "目标用户标识不能为空") @RequestParam("targetUserId") String targetUserId,
            @NotBlank(message = "订单编号不能为空") @RequestParam("orderNo") String orderNo,
            @NotNull(message = "补偿金额不能为空") @DecimalMin("0.01") @RequestParam("amount") BigDecimal amount) {
        Long orderId = LoginHelper.runAs(targetUserId, () -> sampleService.createOrder(orderNo, amount));
        return Result.success(orderId);
    }

    /**
     * 查询系统安全审计事件流水（基于 Spring 原生 @EventListener 捕获）
     *
     * @return 审计事件列表
     */
    @GetMapping("/events")
    public Result<List<SecurityEventVO>> getSecurityEvents() {
        return Result.success(securityEventListener.getAllEventsList());
    }

    /**
     * 清空安全审计事件流水缓存
     *
     * @return 成功响应
     */
    @PostMapping("/events/clear")
    public Result<Void> clearSecurityEvents() {
        securityEventListener.clear();
        return Result.success();
    }
}
