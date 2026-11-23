package io.github.atengk.boot.job.local.tenant;

import io.github.atengk.boot.core.context.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 默认保底租户发现服务实现
 * <p>
 * 当业务容器未注册自定义 {@link TenantProvider} 时自动生效，
 * 优先从当前上下文提取租户 ID，若无则回退至 "default" 默认租户并记录 WARN 日志引导，杜绝应用启动或调度崩溃。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
public class DefaultTenantProvider implements TenantProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultTenantProvider.class);

    @Override
    public List<String> getTenantIds() {
        String currentTenant = TenantContextHolder.getTenantId();
        if (currentTenant != null && !currentTenant.isBlank()) {
            return List.of(currentTenant);
        }
        log.warn("No custom TenantProvider bean registered and current TenantContext is empty. Falling back to default tenant ['default'].");
        return List.of("default");
    }
}
