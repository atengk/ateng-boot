package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicDatasourceGuardScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 多数据源上下文隔离与协同守卫业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Service
public class DynamicDatasourceGuardScenarioServiceImpl implements DynamicDatasourceGuardScenarioService {

    private final TransactionTemplate transactionTemplate;

    public DynamicDatasourceGuardScenarioServiceImpl(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void executeInTransaction(Runnable action) {
        transactionTemplate.execute(status -> {
            if (action != null) {
                action.run();
            } else {
                DynamicDatasourceGuard.validateContextIsolated();
            }
            return null;
        });
    }

    @Override
    public void executeWithSuperAdminPermission(Runnable action) {
        DataPermissionContextHolder.setSuperAdmin(true);
        try {
            if (action != null) {
                action.run();
            } else {
                DynamicDatasourceGuard.validateContextIsolated();
            }
        } finally {
            DataPermissionContextHolder.clear();
        }
    }

    @Override
    public void executeWithIgnorePermission(Runnable action) {
        DataPermissionContextHolder.setIgnore(true);
        try {
            if (action != null) {
                action.run();
            } else {
                DynamicDatasourceGuard.validateContextIsolated();
            }
        } finally {
            DataPermissionContextHolder.clear();
        }
    }

    @Override
    public void validateTenantContext(String tenantId, String expectedTenantId) {
        TenantContextHolder.setTenantId(tenantId);
        try {
            DynamicDatasourceGuard.validateContextIsolated(expectedTenantId);
        } finally {
            TenantContextHolder.clear();
        }
    }

    @Override
    public void validateCleanContext() {
        DynamicDatasourceGuard.validateContextIsolated();
    }

    @Override
    public boolean isMultiDataSourceDetected() {
        return DynamicDatasourceGuard.isMultiDataSourceDetected();
    }

    @Override
    public Map<String, Object> verifyGuardByMode(String mode, String currentTenantId, String expectedTenantId) {
        String normalizedMode = (mode != null && !mode.isBlank())
                ? mode.trim().toLowerCase(Locale.ROOT)
                : "clean";
        try {
            switch (normalizedMode) {
                case "active-tx" -> executeInTransaction(DynamicDatasourceGuard::validateContextIsolated);
                case "super-admin" -> executeWithSuperAdminPermission(null);
                case "ignore-permission" -> executeWithIgnorePermission(null);
                case "tenant-check" -> validateTenantContext(
                        currentTenantId != null ? currentTenantId : "TENANT_001",
                        expectedTenantId != null ? expectedTenantId : "TENANT_001"
                );
                default -> validateCleanContext();
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("mode", normalizedMode);
            result.put("contextIsolated", true);
            result.put("multiDataSourceDetected", isMultiDataSourceDetected());
            return result;
        } catch (IllegalStateException e) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, e.getMessage(), e);
        }
    }
}
