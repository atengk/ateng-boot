package io.github.atengk.boot.sample.mybatisplus.service;

/**
 * 多数据源上下文隔离与协同守卫业务场景服务契约
 * 演示在业务执行切源或跨数据源操作前，依托 DynamicDatasourceGuard 开展防御性上下文防卫，
 * 防御活跃事务假切源、数据权限跨库穿透与显式上下文污染
 *
 * @author Ateng
 * @since 2026-10-28
 */
public interface DynamicDatasourceGuardScenarioService {

    /**
     * 在 Spring 活跃事务内部执行上下文隔离校验（演示假切源防卫）
     *
     * @param action 事务内部回调动作
     */
    void executeInTransaction(Runnable action);

    /**
     * 在数据权限超级管理员激活状态下执行上下文隔离校验（演示权限穿透防卫）
     *
     * @param action 回调动作
     */
    void executeWithSuperAdminPermission(Runnable action);

    /**
     * 在数据权限忽略状态下执行上下文隔离校验（演示权限穿透防卫）
     *
     * @param action 回调动作
     */
    void executeWithIgnorePermission(Runnable action);

    /**
     * 在指定租户上下文中执行隔离校验（演示租户一致性防卫）
     *
     * @param tenantId         租户 ID
     * @param expectedTenantId 期望租户 ID
     */
    void validateTenantContext(String tenantId, String expectedTenantId);

    /**
     * 校验当前线程干净上下文隔离性
     */
    void validateCleanContext();

    /**
     * 判断当前运行环境是否探测到多数据源特征
     *
     * @return true 表示检测到多数据源
     */
    boolean isMultiDataSourceDetected();

    /**
     * 按演练模式执行多数据源上下文隔离自检与异常阻断验证（供 Web 控制器调用）
     *
     * @param mode             演练模式（clean / active-tx / super-admin / ignore-permission / tenant-check）
     * @param currentTenantId  当前租户 ID（tenant-check 模式下使用）
     * @param expectedTenantId 期望租户 ID（tenant-check 模式下使用）
     * @return 校验通过时的诊断信息字典；阻断时转译抛出携带原始 IllegalStateException 因果链的 BusinessException
     */
    java.util.Map<String, Object> verifyGuardByMode(String mode, String currentTenantId, String expectedTenantId);
}
