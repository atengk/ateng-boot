package io.github.atengk.boot.sample.mybatisplus.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * AST 细粒度数据权限过滤与超管/闭包短路放行对比视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class DataPermissionCompareVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前授权可访问的部门 ID 列表
     */
    private List<Long> accessibleDeptIds;

    /**
     * 当前本人操作人标识（可选）
     */
    private String selfOperator;

    /**
     * 受 @DataScope 过滤后的可见用户数
     */
    private int scopedCount;

    /**
     * 通过 DataPermissionContextHolder.runAsSuperAdmin 短路放行后的可见用户数
     */
    private int superAdminCount;

    /**
     * 通过 DataPermissionContextHolder.runWithoutPermission 闭包豁免后的可见用户数
     */
    private int withoutPermissionCount;

    /**
     * 受 @DataScope 过滤后的可见用户列表
     */
    private List<UserVO> scopedUsers;

    /**
     * 超级管理员短路放行后的全量用户列表
     */
    private List<UserVO> superAdminUsers;
}
