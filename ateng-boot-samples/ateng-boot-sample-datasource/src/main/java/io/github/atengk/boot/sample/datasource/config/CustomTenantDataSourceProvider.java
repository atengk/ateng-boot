package io.github.atengk.boot.sample.datasource.config;

import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 自定义租户数据源解析器扩展样例
 * <p>
 * 演示通过实现 TenantDataSourceProvider SPI 接管租户数据源解析逻辑。
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Component
@ConditionalOnProperty(prefix = "ateng.boot.datasource.multi-tenancy", name = "custom-provider-enabled", havingValue = "true")
public class CustomTenantDataSourceProvider implements TenantDataSourceProvider {

    private final Map<String, String> tenantMapping = Map.of(
            "VIP_1001", "tenant_1001",
            "VIP_1002", "tenant_1002"
    );

    @Override
    public String resolveDataSourceKey(String tenantId) {
        return tenantMapping.getOrDefault(tenantId, "tenant_" + tenantId);
    }

    @Override
    public Map<String, String> getTenantDataSourceMap() {
        return tenantMapping;
    }
}
