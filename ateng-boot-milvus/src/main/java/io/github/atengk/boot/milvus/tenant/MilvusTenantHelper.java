package io.github.atengk.boot.milvus.tenant;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.milvus.model.MilvusEntityMetadata;
import io.milvus.v2.common.DataType;
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
     * @param isNumeric       租户字段是否为数值类型 (如 INT64/INT32)
     * @param ignoreTenant    是否显式忽略租户隔离
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveTenantFilter(String originalFilter, String tenantFieldName, boolean isNumeric, boolean ignoreTenant) {
        if (ignoreTenant || TenantContextHolder.isIgnoreTenant()) {
            return originalFilter;
        }

        String tenantId = TenantContextHolder.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            return originalFilter;
        }

        String field = StringUtils.hasText(tenantFieldName) ? tenantFieldName.trim() : DEFAULT_TENANT_FIELD;
        String tenantCondition;
        if (isNumeric) {
            tenantCondition = String.format("%s == %s", field, tenantId.trim());
        } else {
            tenantCondition = String.format("%s == '%s'", field, tenantId.trim().replace("'", "\\'"));
        }

        if (!StringUtils.hasText(originalFilter)) {
            return tenantCondition;
        }

        return String.format("(%s) and (%s)", originalFilter.trim(), tenantCondition);
    }

    /**
     * 为给定的原始查询表达式安全织入多租户过滤条件 (默认 VARCHAR 字符串类型)
     *
     * @param originalFilter   原始过滤表达式 (可为空)
     * @param tenantFieldName 租户字段名称 (为空时默认为 "tenant_id")
     * @param ignoreTenant    是否显式忽略租户隔离
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveTenantFilter(String originalFilter, String tenantFieldName, boolean ignoreTenant) {
        return weaveTenantFilter(originalFilter, tenantFieldName, false, ignoreTenant);
    }

    /**
     * 根据实体元数据安全织入多租户过滤条件 (自动判定租户字段名与数值/字符类型)
     *
     * @param originalFilter 原始过滤表达式 (可为空)
     * @param metadata       实体元数据 (可为空)
     * @param ignoreTenant   是否显式忽略租户隔离
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveEntityTenantFilter(String originalFilter, MilvusEntityMetadata metadata, boolean ignoreTenant) {
        return weaveEntityTenantFilter(originalFilter, metadata, DEFAULT_TENANT_FIELD, ignoreTenant);
    }

    /**
     * 根据实体元数据与全局默认租户列安全织入多租户过滤条件
     *
     * @param originalFilter     原始过滤表达式 (可为空)
     * @param metadata           实体元数据 (可为空)
     * @param defaultTenantField 全局默认租户列名称 (为空时默认 "tenant_id")
     * @param ignoreTenant       是否显式忽略租户隔离
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveEntityTenantFilter(String originalFilter,
                                                 MilvusEntityMetadata metadata,
                                                 String defaultTenantField,
                                                 boolean ignoreTenant) {
        if (metadata != null && metadata.getTenantField() != null) {
            MilvusEntityMetadata.FieldMetadata tenantField = metadata.getTenantField();
            boolean isNumeric = isNumericType(tenantField.getDataType());
            return weaveTenantFilter(originalFilter, tenantField.getFieldName(), isNumeric, ignoreTenant);
        }
        return weaveTenantFilter(originalFilter, resolveDefaultFieldName(defaultTenantField), false, ignoreTenant);
    }

    /**
     * 解析当前操作实际生效的租户隔离列名称
     *
     * @param metadata           实体元数据 (可为空)
     * @param explicitFieldName  显式指定的租户列名 (可为空)
     * @param defaultTenantField 全局默认租户列名 (可为空)
     * @return 生效的租户列名
     */
    public static String resolveEffectiveTenantField(MilvusEntityMetadata metadata,
                                                     String explicitFieldName,
                                                     String defaultTenantField) {
        if (StringUtils.hasText(explicitFieldName)) {
            return explicitFieldName.trim();
        }
        if (metadata != null && metadata.getTenantField() != null) {
            return metadata.getTenantField().getFieldName();
        }
        return resolveDefaultFieldName(defaultTenantField);
    }

    private static String resolveDefaultFieldName(String defaultTenantField) {
        return StringUtils.hasText(defaultTenantField) ? defaultTenantField.trim() : DEFAULT_TENANT_FIELD;
    }

    /**
     * 判断 Milvus 字段数据类型是否为数值类型 (决定过滤条件是否不加单引号)
     *
     * @param dt Milvus DataType
     * @return 是否为整数数值型
     */
    public static boolean isNumericType(DataType dt) {
        return dt == DataType.Int64 || dt == DataType.Int32 || dt == DataType.Int16 || dt == DataType.Int8;
    }

    /**
     * 根据实体元数据安全织入多租户过滤条件
     *
     * @param originalFilter 原始过滤表达式 (可为空)
     * @param metadata       实体元数据 (可为空)
     * @return 织入租户条件后的安全表达式
     */
    public static String weaveEntityTenantFilter(String originalFilter, MilvusEntityMetadata metadata) {
        return weaveEntityTenantFilter(originalFilter, metadata, false);
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

        MilvusEntityMetadata.FieldMetadata tenantField = metadata.getTenantField();
        if (tenantField != null) {
            Object currentVal = tenantField.getFieldValue(entity);
            if (currentVal == null) {
                Class<?> fieldType = tenantField.getField().getType();
                if (fieldType == Long.class || fieldType == long.class) {
                    Long tenantIdLong = TenantContextHolder.getTenantIdAsLong();
                    if (tenantIdLong != null) {
                        tenantField.setFieldValue(entity, tenantIdLong);
                    }
                } else if (fieldType == Integer.class || fieldType == int.class) {
                    Long tenantIdLong = TenantContextHolder.getTenantIdAsLong();
                    if (tenantIdLong != null) {
                        tenantField.setFieldValue(entity, tenantIdLong.intValue());
                    }
                } else {
                    tenantField.setFieldValue(entity, tenantId.trim());
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
