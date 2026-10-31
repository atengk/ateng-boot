package io.github.atengk.boot.datasource.loader;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 外部动态数据源差量同步结果快照
 * <p>
 * 封装智能三向差量比对（Smart Diff）后的变更结果，包括新增、注销、热重载以及跳过的核心数据源统计。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 新增挂载成功的数据源标识列表
     */
    @Builder.Default
    private List<String> addedKeys = Collections.emptyList();

    /**
     * 优雅注销排空的数据源标识列表
     */
    @Builder.Default
    private List<String> removedKeys = Collections.emptyList();

    /**
     * 热重载切流的数据源标识列表
     */
    @Builder.Default
    private List<String> reloadedKeys = Collections.emptyList();

    /**
     * 受保护白名单拦截或跳过的数据源标识列表
     */
    @Builder.Default
    private List<String> skippedKeys = Collections.emptyList();

    /**
     * 获取新增数据源数量
     *
     * @return 数量
     */
    public int getAddedCount() {
        return addedKeys != null ? addedKeys.size() : 0;
    }

    /**
     * 获取注销数据源数量
     *
     * @return 数量
     */
    public int getRemovedCount() {
        return removedKeys != null ? removedKeys.size() : 0;
    }

    /**
     * 获取热重载数据源数量
     *
     * @return 数量
     */
    public int getReloadedCount() {
        return reloadedKeys != null ? reloadedKeys.size() : 0;
    }

    /**
     * 获取跳过数据源数量
     *
     * @return 数量
     */
    public int getSkippedCount() {
        return skippedKeys != null ? skippedKeys.size() : 0;
    }

    /**
     * 判断本次同步是否存在任何拓扑或配置实质变更（增、删、改）
     *
     * @return true 表示存在变更；false 表示无任何变更
     */
    public boolean hasChanges() {
        return getAddedCount() > 0 || getRemovedCount() > 0 || getReloadedCount() > 0;
    }

    /**
     * 创建空结果实例
     *
     * @return 空同步结果
     */
    public static SyncResult empty() {
        return SyncResult.builder().build();
    }
}
