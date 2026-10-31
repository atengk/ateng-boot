package io.github.atengk.boot.job.xxl.enums;

/**
 * XXL-Job 多租户隔离策略模式
 *
 * <p>标识分布式任务调度在执行期间的多租户隔离边界策略：
 * <ul>
 *     <li>{@link #IGNORE}：平台级全局任务，自动跳过行级租户过滤；</li>
 *     <li>{@link #PARAM}：租户级独立任务，自动从调度参数中提取 tenantId 并挂载至上下文；</li>
 *     <li>{@link #ITERATE}：租户级轮询任务，支持通过模板流式遍历有效租户独立沙箱执行。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public enum JobTenantMode {

    /**
     * 忽略租户过滤 (全表全局扫描，自动标记 IgnoreTenant)
     */
    IGNORE,

    /**
     * 参数提取模式 (自动从任务参数中解析 tenantId 并绑定到 TenantContextHolder)
     */
    PARAM,

    /**
     * 遍历轮询模式 (用于全租户轮询批处理)
     */
    ITERATE

}
