package io.github.atengk.boot.mybatisplus.properties;

/**
 * 数据变更 Diff 审计拦截器配置属性
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class AuditChangeProperties {

    /**
     * 是否启用数据变更 Diff 审计拦截器（默认 false，按需开启）
     */
    private boolean enabled = false;

    /**
     * 单次更新最大 Diff 计算行数阈值（默认 100 行），超限时自动熔断降级为摘要记录防 OOM
     */
    private int maxDiffRows = 100;

    /**
     * 是否在事务提交后异步调度投递审计记录（默认 true）
     */
    private boolean async = true;

    /**
     * 是否发布 Spring DataChangeEvent 事件（默认 true）
     */
    private boolean publishEvent = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxDiffRows() {
        return maxDiffRows;
    }

    public void setMaxDiffRows(int maxDiffRows) {
        this.maxDiffRows = maxDiffRows;
    }

    public boolean isAsync() {
        return async;
    }

    public void setAsync(boolean async) {
        this.async = async;
    }

    public boolean isPublishEvent() {
        return publishEvent;
    }

    public void setPublishEvent(boolean publishEvent) {
        this.publishEvent = publishEvent;
    }
}
