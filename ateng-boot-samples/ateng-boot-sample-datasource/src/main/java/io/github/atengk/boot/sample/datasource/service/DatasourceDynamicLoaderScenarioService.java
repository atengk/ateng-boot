package io.github.atengk.boot.sample.datasource.service;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.datasource.loader.DynamicDataSourceSyncManager;
import io.github.atengk.boot.datasource.loader.SyncResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 外部数据源动态加载与智能三向差量同步场景服务
 * <p>
 * 演示通过 DynamicDataSourceSyncManager 执行智能三向差量比对（Smart Diff）与主库绝对白名单防护。
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceDynamicLoaderScenarioService {

    private final DynamicDataSourceSyncManager syncManager;

    /**
     * 手动触发一次外部数据源差量同步 (Smart Diff)
     *
     * @return 差量同步结果快照
     */
    public SyncResult triggerSync() {
        log.info("[DynamicLoaderScenario] 手动触发外部动态数据源差量同步");
        return syncManager.sync();
    }

    /**
     * 校验指定数据源是否受不可删除的核心白名单保护
     *
     * @param dsKey 数据源键名
     * @return true 表示受保护
     */
    public boolean isCoreProtected(String dsKey) {
        return syncManager.isProtectedCoreKey(dsKey);
    }

    /**
     * 获取当前已被差量同步纳管的数据源配置快照
     *
     * @return 配置映射快照
     */
    public Map<String, DataSourceProperty> getLoadedProperties() {
        return syncManager.getLoadedProperties();
    }

    /**
     * 清理指定被纳管数据源追踪记录
     *
     * @param dsKey 数据源键名
     */
    public void unregisterTrackedProperty(String dsKey) {
        log.info("[DynamicLoaderScenario] 清除数据源纳管追踪快照: {}", dsKey);
        syncManager.unregisterTrackedProperty(dsKey);
    }
}
