package io.github.atengk.boot.datasource.tenant;

import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import org.springframework.util.StringUtils;

/**
 * 默认租户数据源解析器
 * 默认规则：prefix + tenantId（例如配置前缀为 "tenant_" 则租户 "1001" 映射为 "tenant_1001"）
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DefaultTenantDataSourceProvider implements TenantDataSourceProvider {

    private final DatasourceProperties.MultiTenancyProperties properties;

    public DefaultTenantDataSourceProvider(DatasourceProperties datasourceProperties) {
        this.properties = datasourceProperties != null ? datasourceProperties.getMultiTenancy() : new DatasourceProperties.MultiTenancyProperties();
    }

    @Override
    public String resolveDataSourceKey(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return null;
        }
        String prefix = properties.getDataSourcePrefix();
        return (prefix != null ? prefix : "") + tenantId;
    }
}
