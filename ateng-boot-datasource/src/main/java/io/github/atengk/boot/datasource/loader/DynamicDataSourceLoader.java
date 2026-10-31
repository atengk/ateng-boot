package io.github.atengk.boot.datasource.loader;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;

import java.util.Map;

/**
 * 外部动态数据源加载器扩展点 SPI
 * <p>
 * 提供从外部异构数据源（如配置中心 Nacos/Apollo、业务平台数据库、Kubernetes ConfigMap 等）拉取动态数据源配置的能力。
 * 配合 {@link DynamicDataSourceSyncManager} 执行智能三向差量比对（Smart Diff）与全生命周期同步。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@FunctionalInterface
public interface DynamicDataSourceLoader {

    /**
     * 加载当前全量动态数据源配置集合
     *
     * @return 数据源标识到数据源创建配置的映射表（永不为 null）
     */
    Map<String, DataSourceProperty> loadDataSources();
}
