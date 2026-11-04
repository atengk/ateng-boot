package io.github.atengk.boot.milvus.tenant;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Milvus 多租户安全隔离辅助器
 * 负责在检索、过滤与数据写入时提供防御性括号表达式织入与租户标识自动兜底填充
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusTenantHelper {

    public static final String DEFAULT_TENANT_FIELD = "tenant_id";

    private MilvusTenantHelper() {
    }

    /**
     * 为给定的原始查询表达式安全织入多租户过滤条件 (防御性括号包裹)
     *
     * @param originalFilter   原始过滤表达式 (可为空)
     * @param tenantFieldName 租户字段名称 (为空时默认为 "tenant_id")
     * @param ignoreTenant    是否显式忽略租户隔离
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveTenantFilter(String originalFilter, String tenantFieldName, boolean ignoreTenant) {
        if (ignoreTenant || TenantContextHolder.isIgnoreTenant()) {
            return originalFilter;
        }

        String tenantId = TenantContextHolder.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            return originalFilter;
        }

        String field = StringUtils.hasText(tenantFieldName) ? tenantFieldName.trim() : DEFAULT_TENANT_FIELD;
        String tenantCondition = String.format("%s == '%s'", field, tenantId.trim());

        if (!StringUtils.hasText(originalFilter)) {
            return tenantCondition;
        }

        return String.format("(%s) and (%s)", originalFilter.trim(), tenantCondition);
    }

    /**
     * 为字典数据行自动补全缺失的租户标识字段
     *
     * @param record          数据字典行
     * @param tenantFieldName 租户字段名
     * @param ignoreTenant    是否显式忽略
     */
    public static void fillRecordTenantField(Map<String, Object> record, String tenantFieldName, boolean ignoreTenant) {
        if (record == null || ignoreTenant || TenantContextHolder.isIgnoreTenant()) {
            return;
        }

        String tenantId = TenantContextHolder.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            return;
        }

        String field = StringUtils.hasText(tenantFieldName) ? tenantFieldName.trim() : DEFAULT_TENANT_FIELD;
        if (!record.containsKey(field) || record.get(field) == null) {
            record.put(field, tenantId.trim());
        }
    }

    /**
     * 为字典数据行自动补全缺失的租户标识字段 (默认租户字段 tenant_id)
     *
     * @param record 数据字典行
     */
    public static void fillRecordTenantField(Map<String, Object> record) {
        fillRecordTenantField(record, DEFAULT_TENANT_FIELD, false);
    }

    /**
     * 兼容既有调用：为字典数据行自动补全缺失的租户标识字段
     *
     * @param record          数据字典行
     * @param tenantFieldName 租户字段名
     * @param ignoreTenant    是否显式忽略
     */
    public static void fillTenantField(Map<String, Object> record, String tenantFieldName, boolean ignoreTenant) {
        fillRecordTenantField(record, tenantFieldName, ignoreTenant);
    }

    /**
     * 为实体对象自动补全缺失的租户标识字段
     *
     * @param entity       实体对象
     * @param metadata     实体元数据
     * @param ignoreTenant 是否显式忽略
     */
    public static void fillEntityTenantField(Object entity, MilvusEntityMetadata metadata, boolean ignoreTenant) {
        if (entity == null || metadata == null || ignoreTenant || TenantContextHolder.isIgnoreTenant()) {
            return;
        }

        String tenantId = TenantContextHolder.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            return;
        }

        MilvusEntityMetadata.FieldMetadata partitionKey = metadata.getPartitionKeyField();
        if (partitionKey != null) {
            Object currentVal = partitionKey.getFieldValue(entity);
            if (currentVal == null) {
                if (partitionKey.getField().getType() == Long.class || partitionKey.getField().getType() == long.class) {
                    Long tenantIdLong = TenantContextHolder.getTenantIdAsLong();
                    if (tenantIdLong != null) {
                        partitionKey.setFieldValue(entity, tenantIdLong);
                    }
                } else {
                    partitionKey.setFieldValue(entity, tenantId.trim());
                }
            }
        }
    }

    /**
     * 为实体对象自动补全缺失的租户标识字段
     *
     * @param entity   实体对象
     * @param metadata 实体元数据
     */
    public static void fillEntityTenantField(Object entity, MilvusEntityMetadata metadata) {
        fillEntityTenantField(entity, metadata, false);
    }
}
