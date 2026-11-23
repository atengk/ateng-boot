package io.github.atengk.boot.sample.satoken.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.satoken.dto.DefenseLoginRequestDTO;
import io.github.atengk.boot.sample.satoken.vo.AccountDefenseStatusVO;
import io.github.atengk.boot.sample.satoken.vo.IpDefenseStatusVO;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录防暴力破解策略与 IP 熔断治理控制器
 * 提供账号输错封禁、客户端 IP 自动嗅探熔断、实时锁定状态查询与管理员紧急解锁端点
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/satoken/defense")
@RequiredArgsConstructor
public class LoginDefenseController {

    private final LoginFailureLockService lockService;

    /**
     * 防爆破安全登录接口
     * 支持账号与客户端 IP 双维滑动重试统计，超过阈值触发 Fail-Fast 阻断
     *
     * @param request 登录请求传输对象
     * @return 签发的访问凭据 Token
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody DefenseLoginRequestDTO request) {
        String account = request.getAccount();
        String clientIp = lockService.resolveClientIp(request.getClientIp());
        String tenantId = request.getTenantId();
        String loginType = request.getLoginType();
        boolean scoped = hasText(tenantId) || hasText(loginType);

        // 1. 前置快速失败防卫：若账号已被锁定或客户端 IP 已被熔断直接 Fail-Fast 403 阻断
        if (scoped) {
            lockService.checkLocked(tenantId, loginType, account, clientIp);
        } else {
            lockService.checkLocked(account, clientIp);
        }

        // 2. 模拟密码校验逻辑 (约定正确密码为 123456)
        if (!"123456".equals(request.getPassword())) {
            int remain = scoped
                    ? lockService.recordFailure(tenantId, loginType, account, clientIp)
                    : lockService.recordFailure(account, clientIp);
            if (remain == 0) {
                throw new BusinessException(CommonResultCode.FORBIDDEN, "登录失败次数过多，已被锁定，请稍后再试");
            }
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误，剩余重试机会: " + remain + " 次");
        }

        // 3. 登录成功，对称清空账号与客户端 IP 失败计数器
        if (scoped) {
            lockService.clearAllFailure(tenantId, loginType, account, clientIp);
        } else {
            lockService.clearAllFailure(account, clientIp);
        }

        // 4. 签发登录凭据
        LoginUser loginUser = LoginUser.builder()
                .userId("user-" + account)
                .userAccount(account)
                .userName(account)
                .tenantId(hasText(tenantId) ? tenantId.trim() : null)
                .build();
        if (hasText(loginType)) {
            String targetType = loginType.trim();
            LoginHelper.login(targetType, loginUser);
            return Result.success(LoginHelper.getTokenValue(targetType));
        }
        LoginHelper.login(loginUser);
        return Result.success(LoginHelper.getTokenValue());
    }

    /**
     * 查询指定账号的防爆破锁定状态与剩余失败机会（支持可选租户与账号体系隔离维度）
     *
     * @param account   目标账号
     * @param tenantId  租户标识（可选）
     * @param loginType 账号体系标识（可选）
     * @return 账号防爆破状态
     */
    @GetMapping("/account-status")
    public Result<AccountDefenseStatusVO> getAccountStatus(
            @RequestParam("account") String account,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "loginType", required = false) String loginType) {
        boolean scoped = hasText(tenantId) || hasText(loginType);
        AccountDefenseStatusVO vo = AccountDefenseStatusVO.builder()
                .account(account)
                .locked(scoped ? lockService.isLocked(tenantId, loginType, account) : lockService.isLocked(account))
                .failureCount(scoped ? lockService.getFailureCount(tenantId, loginType, account) : lockService.getFailureCount(account))
                .remainRetryCount(scoped ? lockService.getRemainRetryCount(tenantId, loginType, account) : lockService.getRemainRetryCount(account))
                .lockTime(scoped ? lockService.getLockTime(tenantId, loginType, account) : lockService.getLockTime(account))
                .build();
        return Result.success(vo);
    }

    /**
     * 查询指定客户端 IP（或当前上下文嗅探 IP）的熔断状态
     *
     * @param clientIp 显式客户端 IP（可选）
     * @return IP 防爆破熔断状态
     */
    @GetMapping("/ip-status")
    public Result<IpDefenseStatusVO> getIpStatus(@RequestParam(value = "clientIp", required = false) String clientIp) {
        String resolvedIp = lockService.resolveClientIp(clientIp);
        IpDefenseStatusVO vo = IpDefenseStatusVO.builder()
                .clientIp(resolvedIp)
                .locked(lockService.isIpLocked(resolvedIp))
                .failureCount(lockService.getIpFailureCount(resolvedIp))
                .remainRetryCount(lockService.getIpRemainRetryCount(resolvedIp))
                .lockTime(lockService.getIpLockTime(resolvedIp))
                .whiteListed(lockService.isIpWhiteListed(resolvedIp))
                .build();
        return Result.success(vo);
    }

    /**
     * 管理员人工紧急解锁账号（支持可选租户与账号体系隔离维度）
     *
     * @param account   目标账号
     * @param tenantId  租户标识（可选）
     * @param loginType 账号体系标识（可选）
     * @return 业务结果
     */
    @PostMapping("/unlock-account")
    public Result<String> unlockAccount(
            @RequestParam("account") String account,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "loginType", required = false) String loginType) {
        if (hasText(tenantId) || hasText(loginType)) {
            lockService.clearFailure(tenantId, loginType, account);
            String scopedSubject = lockService.buildScopedAccountKey(tenantId, loginType, account);
            String targetType = hasText(loginType) ? loginType.trim() : "login";
            if (scopedSubject != null) {
                LoginHelper.untieDisableByLoginType(targetType, scopedSubject);
            }
        } else {
            lockService.clearFailure(account);
            LoginHelper.untieDisable(account);
        }
        return Result.success("账号已解锁: " + account);
    }

    /**
     * 管理员人工解除客户端 IP 临时熔断
     *
     * @param clientIp 目标客户端 IP
     * @return 业务结果
     */
    @PostMapping("/unlock-ip")
    public Result<String> unlockIp(@RequestParam("clientIp") String clientIp) {
        lockService.unlockIp(clientIp);
        lockService.clearIpFailure(clientIp);
        return Result.success("客户端 IP 已解除熔断: " + clientIp);
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }
}
