package io.github.atengk.boot.sample.satoken.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 路由鉴权、数据权限超管与租户上下文观测视图对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GovernanceContextVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前上下文用户标识
     */
    private String userId;

    /**
     * 当前上下文租户标识
     */
    private String tenantId;

    /**
     * 当前请求路由命中的账号体系标识
     */
    private String routeLoginType;

    /**
     * 当前线程是否已桥接数据权限超级管理员标记
     */
    private boolean dataPermissionSuperAdmin;

    /**
     * 业务观测标签
     */
    private String scene;
}
