package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.mybatisplus.entity.Identifiable;
import io.github.atengk.boot.mybatisplus.entity.LogicDeletable;
import io.github.atengk.boot.mybatisplus.entity.TenantAware;
import org.springframework.core.GenericTypeResolver;
import org.springframework.util.ReflectionUtils;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久层正交能力接口与主键策略元数据解析助手
 * <p>
 * 统一为 {@link BatchUtil} 与 {@code DefaultMetaObjectHandler} 提供带缓存的类型解析与防御性默认值装配，
 * 避免反射遍历与多租户类型校验逻辑在各组件间重复散落。
 *
 * @author Ateng
 * @since 2026-11-20
 */
public final class EntityContractHelper {

    private static final String FIELD_TENANT_ID = "tenantId";
    private static final String FIELD_DELETED = "deleted";

    private static final Map<Class<?>, ContractMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    private EntityContractHelper() {
    }

    /**
     * 为实现 {@link Identifiable} 接口的实体自愈主键值
     * <p>
     * 仅当主键当前为 {@code null} 且 {@code @TableId} 策略非 {@link IdType#AUTO} 与 {@link IdType#INPUT} 时分配：
     * {@link IdType#ASSIGN_UUID} 分配无连字符 32 位 UUID，其余支持 {@code Long} 雪花 ID 或 {@code String} 雪花/UUID。
     *
     * @param entity 实现 {@link Identifiable} 的实体实例
     */
    @SuppressWarnings("unchecked")
    public static void autoAssignId(Identifiable<?> entity) {
        if (entity == null || entity.getId() != null) {
            return;
        }
        ContractMetadata metadata = getMetadata(entity.getClass());
        if (!metadata.shouldAutoAssignId()) {
            return;
        }
        Serializable generatedId = generateIdValue(metadata.idType, metadata.idValueType);
        if (generatedId != null) {
            ((Identifiable<Serializable>) entity).setId(generatedId);
        }
    }

    /**
     * 按配置策略为普通 POJO（未实现 {@link Identifiable}）反射自愈 {@code @TableId} 字段
     *
     * @param entity 实体对象
     */
    public static void autoAssignCustomTableId(Object entity) {
        if (entity == null) {
            return;
        }
        ContractMetadata metadata = getMetadata(entity.getClass());
        if (metadata.idField == null || !metadata.shouldAutoAssignId()) {
            return;
        }
        ReflectionUtils.makeAccessible(metadata.idField);
        Object current = ReflectionUtils.getField(metadata.idField, entity);
        if (current != null) {
            return;
        }
        Object generatedId = generateIdValue(metadata.idType, metadata.idValueType);
        if (generatedId != null) {
            ReflectionUtils.setField(metadata.idField, entity, generatedId);
        }
    }

    /**
     * 获取实体的 {@code @TableId} 主键值（优先走 {@link Identifiable} 接口快速路径）
     *
     * @param entity 实体对象
     * @return 主键值，无主键或未赋值时返回 {@code null}
     */
    public static Comparable<?> getEntityId(Object entity) {
        if (entity == null) {
            return null;
        }
        if (entity instanceof Identifiable<?> identifiable) {
            Object id = identifiable.getId();
            if (id instanceof Comparable<?> comparable) {
                return comparable;
            }
            return id != null ? id.toString() : null;
        }
        ContractMetadata metadata = getMetadata(entity.getClass());
        if (metadata.idField == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(metadata.idField);
        Object value = ReflectionUtils.getField(metadata.idField, entity);
        if (value instanceof Comparable<?> comparable) {
            return comparable;
        }
        return value != null ? value.toString() : null;
    }

    /**
     * 判断实体所属 {@code @TableId} 主键策略是否需要框架自动填充
     *
     * @param entityClass 实体类
     * @return 当策略非 {@link IdType#AUTO} 且非 {@link IdType#INPUT} 时返回 {@code true}
     */
    public static boolean shouldAutoAssignId(Class<?> entityClass) {
        if (entityClass == null) {
            return false;
        }
        return getMetadata(entityClass).shouldAutoAssignId();
    }

    /**
     * 若实现 {@link TenantAware} 的实体尚未显式设置租户标识，则从 {@link TenantContextHolder} 注入
     *
     * @param tenantAware 实现 {@link TenantAware} 的实体实例
     */
    @SuppressWarnings("unchecked")
    public static void fillTenantIdIfNull(TenantAware<?> tenantAware) {
        if (tenantAware == null || tenantAware.getTenantId() != null) {
            return;
        }
        String rawTenantId = TenantContextHolder.getTenantId();
        if (rawTenantId == null || rawTenantId.isBlank()) {
            return;
        }
        ContractMetadata metadata = getMetadata(tenantAware.getClass());
        Serializable converted = convertTenantId(metadata.tenantIdType, rawTenantId);
        if (converted != null) {
            ((TenantAware<Serializable>) tenantAware).setTenantId(converted);
        }
    }

    /**
     * 将字符串租户标识按目标字段类型解析并执行 Fail-Fast 校验
     *
     * @param targetType  目标字段类型（支持 {@code Long}/{@code long} 与 {@code String}）
     * @param rawTenantId 原始租户上下文字符串
     * @return 转换后的租户标识，或当上下文为空/类型不支持时返回 {@code null}
     * @throws IllegalArgumentException 当目标为 {@code Long} 类型但上下文非合法整数字符串时立即抛出
     */
    public static Serializable convertTenantId(Class<?> targetType, String rawTenantId) {
        if (targetType == null || rawTenantId == null || rawTenantId.isBlank()) {
            return null;
        }
        if (Long.class.equals(targetType) || long.class.equals(targetType)) {
            String trimmed = rawTenantId.trim();
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "当前上下文租户标识 [" + rawTenantId + "] 非合法 Long 数字，拒绝静默写入 null", ex);
            }
        }
        if (String.class.equals(targetType)) {
            return rawTenantId;
        }
        return null;
    }

    /**
     * 若实现 {@link LogicDeletable} 的实体逻辑删除字段为 {@code null}，则按泛型类型补齐未删除默认值
     *
     * @param logicDeletable 实现 {@link LogicDeletable} 的实体实例
     */
    @SuppressWarnings("unchecked")
    public static void fillLogicNotDeletedIfNull(LogicDeletable<?> logicDeletable) {
        if (logicDeletable == null || logicDeletable.getDeleted() != null) {
            return;
        }
        Serializable defaultVal = resolveDefaultNotDeletedValue(logicDeletable.getClass());
        if (defaultVal != null) {
            ((LogicDeletable<Serializable>) logicDeletable).setDeleted(defaultVal);
        }
    }

    /**
     * 根据 {@link LogicDeletable} 泛型或字段类型推算未删除占位默认值（{@code 0L} / {@code 0} / {@code "0"} / {@code false}）
     *
     * @param entityClass 实体类
     * @return 默认未删除标记值；无法推断时返回 {@code null}
     */
    public static Serializable resolveDefaultNotDeletedValue(Class<?> entityClass) {
        if (entityClass == null) {
            return null;
        }
        Class<?> deletedType = getMetadata(entityClass).deletedType;
        if (Long.class.equals(deletedType) || long.class.equals(deletedType)) {
            return 0L;
        }
        if (Integer.class.equals(deletedType) || int.class.equals(deletedType)) {
            return 0;
        }
        if (String.class.equals(deletedType)) {
            return "0";
        }
        if (Boolean.class.equals(deletedType) || boolean.class.equals(deletedType)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Serializable generateIdValue(IdType idType, Class<?> idValueType) {
        if (idValueType == null) {
            return null;
        }
        if (idType == IdType.ASSIGN_UUID) {
            return String.class.equals(idValueType) ? IdUtil.fastSimpleUUID() : null;
        }
        if (Long.class.equals(idValueType) || long.class.equals(idValueType)) {
            return IdUtil.getSnowflakeNextId();
        }
        if (String.class.equals(idValueType)) {
            return IdUtil.getSnowflakeNextIdStr();
        }
        return null;
    }

    private static ContractMetadata getMetadata(Class<?> clazz) {
        return METADATA_CACHE.computeIfAbsent(clazz, EntityContractHelper::resolveMetadata);
    }

    private static ContractMetadata resolveMetadata(Class<?> clazz) {
        Field idField = findTableIdField(clazz);
        IdType idType = IdType.NONE;
        if (idField != null) {
            TableId tableId = idField.getAnnotation(TableId.class);
            if (tableId != null) {
                idType = tableId.type();
            }
        }
        Class<?> idValueType = idField != null ? idField.getType() : null;
        if (idValueType == null && Identifiable.class.isAssignableFrom(clazz)) {
            idValueType = GenericTypeResolver.resolveTypeArgument(clazz, Identifiable.class);
        }

        Class<?> tenantIdType = null;
        if (TenantAware.class.isAssignableFrom(clazz)) {
            tenantIdType = GenericTypeResolver.resolveTypeArgument(clazz, TenantAware.class);
            if (tenantIdType == null) {
                Field tenantField = ReflectionUtils.findField(clazz, FIELD_TENANT_ID);
                tenantIdType = tenantField != null ? tenantField.getType() : null;
            }
        }

        Class<?> deletedType = null;
        if (LogicDeletable.class.isAssignableFrom(clazz)) {
            deletedType = GenericTypeResolver.resolveTypeArgument(clazz, LogicDeletable.class);
            if (deletedType == null) {
                Field deletedField = ReflectionUtils.findField(clazz, FIELD_DELETED);
                deletedType = deletedField != null ? deletedField.getType() : null;
            }
        }

        return new ContractMetadata(idField, idType, idValueType, tenantIdType, deletedType);
    }

    private static Field findTableIdField(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isAnnotationPresent(TableId.class)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private record ContractMetadata(
            Field idField,
            IdType idType,
            Class<?> idValueType,
            Class<?> tenantIdType,
            Class<?> deletedType) {

        boolean shouldAutoAssignId() {
            return idType != IdType.AUTO && idType != IdType.INPUT;
        }
    }
}
