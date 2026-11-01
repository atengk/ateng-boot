package io.github.atengk.boot.datasource.loader;

import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.core.context.ContextCopyingTaskDecorator;
import io.github.atengk.boot.datasource.enums.DatasourceResultCode;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 外部动态数据源差量同步协调管理器 (Smart Three-Way Reconciliation Engine)
 * <p>
 * 对接 {@link DynamicDataSourceLoader} SPI，执行智能三向差量比对（Smart Diff）：
 * <ul>
 *     <li><b>新增项 (Additions)</b>：自动探活连通性与连接池并发预热，平滑挂载至运行路由表；</li>
 *     <li><b>缺失项 (Removals)</b>：识别在途动态数据源，执行优雅排空 (Drain) 并安全注销物理连接池；</li>
 *     <li><b>变更项 (Hot Reload)</b>：探测配置变更，采用“先建新池探活预热 -> 原子切流 -> 优雅排空注销旧池”无损平滑热重载；</li>
 *     <li><b>核心库绝对白名单防护</b>：{@code master}、{@code slave} 及系统 Primary 受刚性保护，严禁被外部同步误删或擅自替换。</li>
 * </ul>
 * 配合可选的受管调度器，支持在后台周期性自动化轮询同步。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class DynamicDataSourceSyncManager implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceSyncManager.class);

    private final DynamicDatasourceTemplate template;
    private final DatasourceProperties datasourceProperties;
    private final DynamicDataSourceLoader loader;
    private final ScheduledExecutorService scheduler;
    private final ThreadPoolTaskScheduler internalScheduler;
    private final ReentrantLock syncLock = new ReentrantLock();

    /**
     * 上一次成功同步的数据源配置快照（用于三向差量比对变更探测）
     */
    private final ConcurrentMap<String, DataSourceProperty> loadedProperties = new ConcurrentHashMap<>();

    private ScheduledFuture<?> autoSyncFuture;

    public DynamicDataSourceSyncManager(DynamicDatasourceTemplate template,
                                        DatasourceProperties datasourceProperties,
                                        DynamicDataSourceLoader loader) {
        this(template, datasourceProperties, loader, null);
    }

    public DynamicDataSourceSyncManager(DynamicDatasourceTemplate template,
                                        DatasourceProperties datasourceProperties,
                                        DynamicDataSourceLoader loader,
                                        ScheduledExecutorService scheduler) {
        this.template = Objects.requireNonNull(template, "dynamicDatasourceTemplate 不能为空");
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.loader = loader;

        DatasourceProperties.AutoSyncProperties autoSync = this.datasourceProperties.getDynamicLoad() != null
                ? this.datasourceProperties.getDynamicLoad().getAutoSync()
                : new DatasourceProperties.AutoSyncProperties();

        if (scheduler != null) {
            this.scheduler = scheduler;
            this.internalScheduler = null;
        } else if (autoSync != null && autoSync.isEnabled()) {
            ThreadPoolTaskScheduler taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("ateng-datasource-sync-");
            taskScheduler.setDaemon(true);
            taskScheduler.setWaitForTasksToCompleteOnShutdown(false);
            taskScheduler.initialize();
            this.internalScheduler = taskScheduler;
            this.scheduler = taskScheduler.getScheduledExecutor();
        } else {
            this.scheduler = null;
            this.internalScheduler = null;
        }

        startAutoSyncIfEnabled();
    }

    /**
     * 执行一次全量差量比对与受管同步 (Smart Diff)
     *
     * @return 差量同步结果快照
     */
    public SyncResult sync() {
        if (loader == null) {
            log.warn("ateng-boot-datasource: DynamicDataSourceLoader 未配置，跳过数据源差量同步");
            return SyncResult.empty();
        }

        syncLock.lock();
        try {
            Map<String, DataSourceProperty> incomingMap;
            try {
                incomingMap = loader.loadDataSources();
            } catch (Exception e) {
                throw new DataSourceSwitchException("加载外部数据源配置失败: " + e.getMessage(), e,
                        DatasourceResultCode.DATASOURCE_INIT_ERROR);
            }
            if (incomingMap == null) {
                incomingMap = Collections.emptyMap();
            }

            Set<String> liveKeys = template.getDataSourceKeys();
            Set<String> trackedKeys = new HashSet<>(loadedProperties.keySet());
            Set<String> incomingKeys = incomingMap.keySet();

            List<String> addedKeys = new ArrayList<>();
            List<String> removedKeys = new ArrayList<>();
            List<String> reloadedKeys = new ArrayList<>();
            List<String> skippedKeys = new ArrayList<>();

            // 1. 处理新增项 (Additions) 与变更项 (Hot Reload)
            for (Map.Entry<String, DataSourceProperty> entry : incomingMap.entrySet()) {
                String dsKey = entry.getKey();
                DataSourceProperty newProperty = entry.getValue();

                if (!StringUtils.hasText(dsKey) || newProperty == null) {
                    continue;
                }

                // 核心库绝对白名单防护：严禁外部同步覆盖或替换主从核心库
                if (isProtectedCoreKey(dsKey)) {
                    log.warn("ateng-boot-datasource: 外部数据源标识 [{}] 命中受保护核心库白名单，忽略外部变更", dsKey);
                    skippedKeys.add(dsKey);
                    continue;
                }

                if (!liveKeys.contains(dsKey)) {
                    // 新增项
                    try {
                        template.addDataSource(dsKey, newProperty);
                        loadedProperties.put(dsKey, newProperty);
                        addedKeys.add(dsKey);
                        log.info("ateng-boot-datasource: 差量同步新增数据源成功 [dsKey={}]", dsKey);
                    } catch (Exception e) {
                        log.error("ateng-boot-datasource: 差量同步新增数据源失败 [dsKey={}]: {}", dsKey, e.getMessage(), e);
                    }
                } else {
                    // 已在运行注册表中，比对配置是否发生实质变更
                    DataSourceProperty oldProperty = loadedProperties.get(dsKey);
                    if (isPropertyChanged(oldProperty, newProperty)) {
                        try {
                            template.reloadDataSource(dsKey, newProperty);
                            loadedProperties.put(dsKey, newProperty);
                            reloadedKeys.add(dsKey);
                            log.info("ateng-boot-datasource: 差量同步热重载数据源成功 [dsKey={}]", dsKey);
                        } catch (Exception e) {
                            log.error("ateng-boot-datasource: 差量同步热重载数据源失败 [dsKey={}]: {}", dsKey, e.getMessage(), e);
                        }
                    } else {
                        // 配置无变更，同步维护追踪表
                        loadedProperties.put(dsKey, newProperty);
                    }
                }
            }

            // 2. 处理缺失项 (Removals)
            for (String trackedKey : trackedKeys) {
                if (!incomingKeys.contains(trackedKey)) {
                    // 核心库防护防线
                    if (isProtectedCoreKey(trackedKey)) {
                        skippedKeys.add(trackedKey);
                        continue;
                    }
                    if (liveKeys.contains(trackedKey)) {
                        try {
                            template.removeDataSource(trackedKey, true);
                            removedKeys.add(trackedKey);
                            loadedProperties.remove(trackedKey);
                            log.info("ateng-boot-datasource: 差量同步优雅注销数据源成功 [dsKey={}]", trackedKey);
                        } catch (Exception e) {
                            log.error("ateng-boot-datasource: 差量同步优雅注销数据源失败 [dsKey={}]: {}", trackedKey, e.getMessage(), e);
                        }
                    } else {
                        // 运行注册表中已不存在，直接同步清理追踪状态
                        loadedProperties.remove(trackedKey);
                    }
                }
            }

            SyncResult result = SyncResult.builder()
                    .addedKeys(addedKeys)
                    .removedKeys(removedKeys)
                    .reloadedKeys(reloadedKeys)
                    .skippedKeys(skippedKeys)
                    .build();

            log.info("ateng-boot-datasource: 完成外部数据源差量同步 (新增: {}, 注销: {}, 热重载: {}, 核心跳过: {})",
                    result.getAddedCount(), result.getRemovedCount(), result.getReloadedCount(), result.getSkippedCount());
            return result;
        } finally {
            if (syncLock.isHeldByCurrentThread()) {
                syncLock.unlock();
            }
        }
    }

    /**
     * 判断指定数据源是否受核心白名单保护（严禁外部同步删除或擅自热重载）
     *
     * @param dsKey 数据源标识
     * @return true 表示受保护；false 表示允许受管同步
     */
    public boolean isProtectedCoreKey(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return false;
        }
        return template.isProtectedCoreKey(dsKey);
    }

    /**
     * 获取当前已被差量同步纳管的数据源标识映射表（只读快照）
     *
     * @return 纳管的数据源创建配置快照
     */
    public Map<String, DataSourceProperty> getLoadedProperties() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(loadedProperties));
    }

    /**
     * 重置或清除指定被纳管数据源的追踪记录
     *
     * @param dsKey 数据源标识
     */
    public void unregisterTrackedProperty(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return;
        }
        syncLock.lock();
        try {
            loadedProperties.remove(dsKey);
        } finally {
            if (syncLock.isHeldByCurrentThread()) {
                syncLock.unlock();
            }
        }
    }

    /**
     * 手动触发一次安全同步（不向外抛出异常，记录日志）
     */
    public void syncSafe() {
        try {
            sync();
        } catch (Exception e) {
            log.warn("ateng-boot-datasource: 自动轮询同步数据源异常: {}", e.getMessage(), e);
        }
    }

    @Override
    public void destroy() {
        syncLock.lock();
        try {
            if (autoSyncFuture != null && !autoSyncFuture.isCancelled()) {
                autoSyncFuture.cancel(false);
            }
            if (internalScheduler != null) {
                internalScheduler.destroy();
            }
            log.info("ateng-boot-datasource: DynamicDataSourceSyncManager 已安全销毁并停止同步轮询");
        } finally {
            if (syncLock.isHeldByCurrentThread()) {
                syncLock.unlock();
            }
        }
    }

    private void startAutoSyncIfEnabled() {
        DatasourceProperties.AutoSyncProperties autoSync = datasourceProperties.getDynamicLoad() != null
                ? datasourceProperties.getDynamicLoad().getAutoSync()
                : null;
        if (autoSync != null && autoSync.isEnabled() && loader != null && scheduler != null) {
            Duration interval = autoSync.getInterval() != null ? autoSync.getInterval() : Duration.ofMinutes(1);
            long intervalMillis = Math.max(100, interval.toMillis());
            Runnable decoratedTask = new ContextCopyingTaskDecorator().decorate(this::syncSafe);
            this.autoSyncFuture = scheduler.scheduleWithFixedDelay(
                    decoratedTask,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
            log.info("ateng-boot-datasource: 成功启动外部数据源自动差量同步轮询任务 (执行周期: {})", interval);
        }
    }

    private boolean isPropertyChanged(DataSourceProperty oldProp, DataSourceProperty newProp) {
        if (oldProp == null || newProp == null) {
            return true;
        }
        if (!Objects.equals(oldProp.getUrl(), newProp.getUrl())) {
            return true;
        }
        if (!Objects.equals(oldProp.getUsername(), newProp.getUsername())) {
            return true;
        }
        if (!Objects.equals(oldProp.getPassword(), newProp.getPassword())) {
            return true;
        }
        if (!Objects.equals(oldProp.getDriverClassName(), newProp.getDriverClassName())) {
            return true;
        }
        if (!Objects.equals(oldProp.getPoolName(), newProp.getPoolName())) {
            return true;
        }
        return !oldProp.equals(newProp);
    }
}
