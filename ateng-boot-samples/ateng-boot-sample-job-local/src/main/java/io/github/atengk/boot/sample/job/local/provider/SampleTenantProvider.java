package io.github.atengk.boot.sample.job.local.provider;

import io.github.atengk.boot.job.local.tenant.TenantProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 示例多租户发现服务 SPI 实现，为多租户轮询调度提供有效租户标识列表
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class SampleTenantProvider implements TenantProvider {

    private static final List<String> DEMO_TENANTS = List.of("tenant-corp-a", "tenant-corp-b", "tenant-corp-c");

    @Override
    public List<String> getTenantIds() {
        return DEMO_TENANTS;
    }
}
