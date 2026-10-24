package io.github.atengk.boot.datasource.readwrite;

import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;

/**
 * 写后读主防延迟全局协调管理器
 * 统一聚合 REQUEST（单请求作用域）与 WINDOW（时间窗口作用域）防延迟调度策略
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class ReadAfterWriteManager {

    private final DatasourceProperties.ReadAfterWriteProperties properties;
    private final WriteOccurredResolver writeOccurredResolver;

    public ReadAfterWriteManager(DatasourceProperties.ReadAfterWriteProperties properties,
                                 WriteOccurredResolver writeOccurredResolver) {
        this.properties = properties != null ? properties : new DatasourceProperties.ReadAfterWriteProperties();
        this.writeOccurredResolver = writeOccurredResolver != null ? writeOccurredResolver : new DefaultWriteOccurredResolver();
    }

    /**
     * 记录当前上下文发生了一次写操作
     */
    public void markWriteOccurred() {
        if (!properties.isEnabled()) {
            return;
        }
        if (properties.getScope() == ReadAfterWriteScope.REQUEST) {
            ReadAfterWriteContextHolder.markWriteOccurred();
        } else if (properties.getScope() == ReadAfterWriteScope.WINDOW) {
            writeOccurredResolver.markWrite(properties.getWindowTtl());
        }
    }

    /**
     * 判断当前上下文是否命中写后读主防延迟条件
     *
     * @return true 表示命中防延迟，后续查询必须强制路由主库；false 表示正常走读写分离
     */
    public boolean isWriteOccurred() {
        if (!properties.isEnabled()) {
            return false;
        }
        if (properties.getScope() == ReadAfterWriteScope.REQUEST) {
            return ReadAfterWriteContextHolder.isWriteOccurred();
        } else if (properties.getScope() == ReadAfterWriteScope.WINDOW) {
            return writeOccurredResolver.isWriteOccurred(properties.getWindowTtl());
        }
        return false;
    }

    /**
     * 清理当前上下文防延迟状态
     */
    public void clear() {
        ReadAfterWriteContextHolder.clear();
        writeOccurredResolver.clear();
    }
}
