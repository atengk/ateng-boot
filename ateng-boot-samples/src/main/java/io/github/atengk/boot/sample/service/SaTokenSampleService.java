package io.github.atengk.boot.sample.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Sa-Token Service 层业务服务
 * 验证 SaAroundAnnotationPointcutAdvisor 覆盖能力及持久层业务调度
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Service
@RequiredArgsConstructor
public class SaTokenSampleService {

    private final TenantOrderMapper orderMapper;

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
        orderMapper.insert(order);
        return order.getId();
    }

    /**
     * 校验 Service 层角色注解拦截
     *
     * @return 业务结果
     */
    @SaCheckRole("super_admin")
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
}
