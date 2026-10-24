package io.github.atengk.boot.datasource.exception;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.datasource.enums.DatasourceResultCode;
import lombok.Getter;

/**
 * 租户物理数据源未找到异常
 * 当处于 STRICT 严格模式下，租户未分配独立物理数据库时抛出
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Getter
public class TenantDataSourceNotFoundException extends BusinessException {

    private final String tenantId;
    private final String expectedDataSourceKey;

    public TenantDataSourceNotFoundException(String tenantId, String expectedDataSourceKey) {
        super(DatasourceResultCode.TENANT_DATASOURCE_NOT_FOUND,
                "未找到租户 [" + tenantId + "] 对应的物理数据源 [" + expectedDataSourceKey + "]，拒绝访问！");
        this.tenantId = tenantId;
        this.expectedDataSourceKey = expectedDataSourceKey;
    }
}
