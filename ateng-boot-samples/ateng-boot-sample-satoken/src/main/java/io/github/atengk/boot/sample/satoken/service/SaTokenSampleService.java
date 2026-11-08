package io.github.atengk.boot.sample.satoken.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sample.satoken.dto.LoginDefenseRequest;
import io.github.atengk.boot.sample.satoken.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.satoken.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.satoken.vo.DefenseStatusVO;
import io.github.atengk.boot.satoken.defense.LoginFailureLockService;
import io.github.atengk.boot.satoken.model.LoginUser;
import io.github.atengk.boot.satoken.util.LoginHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Sa-Token Service 层业务服务
 * 验证 SaAroundAnnotationPointcutAdvisor 覆盖能力及持久层业务调度与防爆破服务
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SaTokenSampleService {

    private final TenantOrderMapper orderMapper;
    private final ObjectProvider<LoginFailureLockService> loginDefenseServiceProvider;

    /**
     * 创建租户订单业务逻辑（触发持久层审计与多租户自动填充）
     *
     * @param orderNo 订单号
     * @param amount  订单金额
     * @return 订单持久化 ID
     */
    public Long createOrder(String orderNo, BigDecimal amount) {
        TenantOrderEntity order = new TenantOrderEntity();
        order.setOrderNo(orderNo);
        order.setAmount(amount);
        String tenantId = io.github.atengk.boot.core.context.TenantContextHolder.getTenantId();
        if (tenantId != null && !tenantId.isBlank()) {
            order.setTenantId(tenantId);
        }
        orderMapper.insert(order);
        return order.getId();
    }

    /**
     * 根据主键查询租户订单详情并转换为 VO（严格遵循零实体泄露原则）
     *
     * @param orderId 订单标识
     * @return 订单展示视图对象
     */
    public io.github.atengk.boot.sample.satoken.vo.TenantOrderVO getOrderById(Long orderId) {
        if (orderId == null) {
            return null;
        }
        TenantOrderEntity entity = orderMapper.selectById(orderId);
        if (entity == null) {
            return null;
        }
        return io.github.atengk.boot.sample.satoken.vo.TenantOrderVO.builder()
                .id(entity.getId())
                .orderNo(entity.getOrderNo())
                .amount(entity.getAmount())
                .tenantId(entity.getTenantId())
                .createBy(entity.getCreateBy())
                .createTime(entity.getCreateTime())
                .updateBy(entity.getUpdateBy())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    /**
     * 校验 Service 层角色注解拦截
     *
     * @return 业务结果
     */
    @SaCheckRole("finance_director")
    public String checkServiceRole() {
        return "Service 角色校验通过";
    }

    /**
     * 校验 Service 层权限码注解拦截
     *
     * @return 业务结果
     */
    @SaCheckPermission("order:cancel")
    public String checkServicePermission() {
        return "Service 权限校验通过";
    }

    /**
     * 带防暴力破解防护的业务登录逻辑
     *
     * @param request 登录传输对象
     * @return 签发的访问凭据 Token
     */
    public String loginWithDefense(LoginDefenseRequest request) {
        String username = request.getUsername();
        String password = request.getPassword();

        LoginFailureLockService lockService = loginDefenseServiceProvider.getIfAvailable();
        if (lockService != null) {
            // 前置快速失败防卫：若账号已被封禁锁定直接抛出 DisableServiceException
            lockService.checkLocked(username);
        }

        // 模拟密码校验（测试基线约定正确密码为 correct-pass-123）
        boolean passwordCorrect = "correct-pass-123".equals(password);
        if (!passwordCorrect) {
            if (lockService != null) {
                int remain = lockService.recordFailure(username);
                if (remain == 0) {
                    throw new BusinessException(CommonResultCode.FORBIDDEN, "登录失败次数过多，账号已被锁定，请稍后再试");
                }
                throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误，剩余重试机会: " + remain + " 次");
            }
            throw new BusinessException(CommonResultCode.UNAUTHORIZED, "账号或密码错误");
        }

        // 登录成功，对称清空失败计数器
        if (lockService != null) {
            lockService.clearFailure(username);
        }

        // 签发用户登录凭据
        LoginUser loginUser = LoginUser.builder()
                .userId("user-" + username)
                .userAccount(username)
                .userName(username)
                .build();
        LoginHelper.login(loginUser);
        return LoginHelper.getTokenValue();
    }

    /**
     * 查询账号防暴力破解安全状态
     *
     * @param username 账号名称
     * @return 状态展示对象
     */
    public DefenseStatusVO getDefenseStatus(String username) {
        return getDefenseStatus(username, null);
    }

    /**
     * 查询账号与 IP 双轨防暴力破解安全状态
     *
     * @param username 账号名称
     * @param clientIp 客户端 IP（可选，不传时自动嗅探）
     * @return 状态展示对象
     */
    public DefenseStatusVO getDefenseStatus(String username, String clientIp) {
        LoginFailureLockService lockService = loginDefenseServiceProvider.getIfAvailable();
        if (lockService != null) {
            String resolvedIp = lockService.resolveClientIp(clientIp);
            boolean ipLocked = resolvedIp != null && lockService.isIpLocked(resolvedIp);
            int ipFailCount = resolvedIp != null ? lockService.getIpFailureCount(resolvedIp) : 0;

            return DefenseStatusVO.builder()
                    .isLocked(lockService.isLocked(username))
                    .remainRetryCount(lockService.getRemainRetryCount(username))
                    .failureCount(lockService.getFailureCount(username))
                    .disableTime(lockService.getDisableTime(username))
                    .isIpLocked(ipLocked)
                    .ipFailureCount(ipFailCount)
                    .enabled(true)
                    .build();
        }
        return DefenseStatusVO.builder()
                .isLocked(false)
                .remainRetryCount(0)
                .failureCount(0)
                .disableTime(0L)
                .isIpLocked(false)
                .ipFailureCount(0)
                .enabled(false)
                .build();
    }
}
