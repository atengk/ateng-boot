package io.github.atengk.boot.sample.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.springframework.stereotype.Service;

/**
 * Sa-Token Service 层 AOP 注解鉴权验证业务服务
 * 验证 SaAroundAnnotationPointcutAdvisor 能够穿透并覆盖 Controller 之外的业务服务层
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Service
public class SaTokenSampleService {

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
