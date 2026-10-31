package io.github.atengk.boot.datasource.tenant;

import java.util.Collections;
import java.util.Map;

/**
 * 物理分库租户数据源解析器 SPI
 *
 * @author Ateng
 * @since 2026-10-24
 */
@FunctionalInterface
public interface TenantDataSourceProvider {

    /**
     * 根据租户标识解析出对应的数据源 Key
     *
     * @param tenantId 租户标识
     * @return 目标物理数据源 Key，若返回 null 或空字符串则视为未匹配到专属数据源
     */
    String resolveDataSourceKey(String tenantId);

    /**
     * 根据租户标识解析出对应的主库数据源 Key
     * 默认缺省回退至 {@link #resolveDataSourceKey(String)}
     *
     * @param tenantId 租户标识
     * @return 目标租户主库数据源 Key
     * @since 2026-10-31
     */
    default String resolveMasterDataSourceKey(String tenantId) {
        return resolveDataSourceKey(tenantId);
    }

    /**
     * 根据租户标识解析出对应的从库数据源或从库分组 Key
     * 默认缺省返回 null（表示未显式配置专属从库，走约定探测或无从库）
     *
     * @param tenantId 租户标识
     * @return 目标租户从库数据源 Key，返回 null 表示未配置专属从库
     * @since 2026-10-31
     */
    default String resolveSlaveDataSourceKey(String tenantId) {
        return null;
    }

    /**
     * 获取所有已知或活跃的租户与数据源映射（可选扩展）
     * 默认返回空 Map
     *
     * @return 租户与物理数据源 Key 映射表
     */
    default Map<String, String> getTenantDataSourceMap() {
        return Collections.emptyMap();
    }
}
