package io.github.atengk.boot.datasource.tenant;

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
}
