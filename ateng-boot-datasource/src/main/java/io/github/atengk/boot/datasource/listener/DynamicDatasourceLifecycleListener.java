package io.github.atengk.boot.datasource.listener;

import javax.sql.DataSource;

/**
 * 动态数据源生命周期感知监听器 SPI
 * <p>
 * 用于在数据源动态注册挂载、注销摘除时接收全生命周期事件通知，支持监控指标绑定、外部缓存联动与审计。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public interface DynamicDatasourceLifecycleListener {

    /**
     * 当数据源已成功完成初始化预检并挂载到动态路由表后触发
     *
     * @param dsKey      数据源标识
     * @param dataSource 物理数据源实例
     */
    default void onDataSourceAdded(String dsKey, DataSource dataSource) {
    }

    /**
     * 当数据源已从动态路由表摘除（并完成优雅排空与物理关闭）后触发
     *
     * @param dsKey 数据源标识
     */
    default void onDataSourceRemoved(String dsKey) {
    }
}
