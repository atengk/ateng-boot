package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.DynamicQueryIgnore;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态查询与排序安全转译引擎门面
 * 基于实体元数据纳秒级白名单缓存将结构化请求安全编译为 MyBatis-Plus QueryWrapper，落实六重安全防卫以杜绝 SQL 注入与未授权数据探测
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public final class DynamicQueryHelper {

    /**
     * 全局动态查询引擎启用开关（默认开启）
     */
    private static volatile boolean enabled = true;

    /**
     * 实体元数据解析缓存（ConcurrentHashMap 纳秒级查找）
     */
    private static final Map<Class<?>, EntityMetaInfo> ENTITY_META_CACHE = new ConcurrentHashMap<>();

    /**
     * 启发式默认敏感属性黑名单（全小写）
     */
    private static final Set<String> HEURISTIC_SENSITIVE_PROPERTIES = Set.of(
            "password", "secret", "secretkey", "salt", "token", "accesstoken", "refreshtoken"
    );

    private DynamicQueryHelper() {
    }

    /**
     * 设置动态查询转译引擎全局开关
     *
     * @param isEnabled true 为启用，false 为禁用
     */
    public static void setEnabled(boolean isEnabled) {
        enabled = isEnabled;
    }

    /**
     * 获取动态查询转译引擎全局开关状态
     *
     * @return true 若已启用；否则 false
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 清空实体元数据反射缓存（用于类重新加载或单元测试隔离）
     */
    public static void clearCache() {
        ENTITY_META_CACHE.clear();
    }

    /**
     * 基于结构化动态查询请求与实体类型构建安全 QueryWrapper
     *
     * @param request     动态查询请求模型（承载 conditions 与 sorts）
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(DynamicQueryRequest request, Class<T> entityClass) {
        ensureEnabled();
        Objects.requireNonNull(entityClass, "实体类型 entityClass 不能为 null");

        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (request == null) {
            return wrapper;
        }

        EntityMetaInfo metaInfo = getOrCreateEntityMeta(entityClass);

        // 1. 过滤并应用动态条件
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            for (QueryCondition condition : request.getConditions()) {
                if (condition == null) {
                    continue;
                }
                String field = condition.getField();
                if (field == null || field.trim().isEmpty() || !SortItem.isValidProperty(field.trim())) {
                    log.warn("动态查询检测到非法字段语法或潜在注入字符，已安全忽略: {}", field);
                    continue;
                }
                String normalizedField = field.trim().toLowerCase(Locale.ROOT);
                if (metaInfo.isIgnored(normalizedField)) {
                    log.warn("动态查询字段命中安全黑名单或敏感标记，已安全忽略: {}", field);
                    continue;
                }
                FieldMetaInfo fieldMeta = metaInfo.getField(normalizedField);
                if (fieldMeta == null) {
                    log.warn("动态查询字段未在目标实体白名单中声明，已安全忽略: {}", field);
                    continue;
                }
                applyCondition(wrapper, fieldMeta.getColumn(), condition.getOperator(), condition.getValue(), condition.getValues());
            }
        }

        // 2. 过滤并应用排序规则
        applySorts(wrapper, request, metaInfo);

        return wrapper;
    }

    /**
     * 基于声明了 @QueryField 注解的查询 DTO 对象与实体类型构建安全 QueryWrapper
     *
     * @param dto         业务查询 DTO（支持包含 @QueryField 注解或普通字段）
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Object dto, Class<T> entityClass) {
        ensureEnabled();
        Objects.requireNonNull(entityClass, "实体类型 entityClass 不能为 null");

        if (dto instanceof DynamicQueryRequest request) {
            return buildWrapper(request, entityClass);
        }

        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (dto == null) {
            return wrapper;
        }

        EntityMetaInfo metaInfo = getOrCreateEntityMeta(entityClass);
        List<Field> fields = getAllDeclaredFields(dto.getClass());

        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.getDeclaringClass() == PageQuery.class) {
                continue;
            }
            QueryField queryField = field.getAnnotation(QueryField.class);
            String property = (queryField != null && !queryField.property().trim().isEmpty())
                    ? queryField.property().trim()
                    : field.getName();

            if (!SortItem.isValidProperty(property)) {
                continue;
            }

            Object value;
            try {
                field.setAccessible(true);
                value = field.get(dto);
            } catch (IllegalAccessException e) {
                log.warn("反射读取 DTO 属性失败: {}", field.getName(), e);
                continue;
            }

            boolean ignoreNull = (queryField == null) || queryField.ignoreNull();
            if (ignoreNull && isValueEmpty(value)) {
                continue;
            }

            String normalizedField = property.toLowerCase(Locale.ROOT);
            if (metaInfo.isIgnored(normalizedField)) {
                log.warn("DTO 查询属性命中敏感黑名单，已安全忽略: {}", property);
                continue;
            }
            FieldMetaInfo fieldMeta = metaInfo.getField(normalizedField);
            if (fieldMeta == null) {
                log.warn("DTO 查询属性未在目标实体白名单中，已安全忽略: {}", property);
                continue;
            }

            QueryOperator operator = (queryField != null) ? queryField.operator() : QueryOperator.EQ;
            List<Object> values = null;
            if (value instanceof Collection<?> col) {
                values = new ArrayList<>(col);
            }
            applyCondition(wrapper, fieldMeta.getColumn(), operator, value, values);
        }

        // 若 DTO 继承了 PageQuery，自动处理其排序规则
        if (dto instanceof PageQuery pageQuery) {
            applySorts(wrapper, pageQuery, metaInfo);
        }

        return wrapper;
    }

    /**
     * 校验全局引擎开关是否已启用
     */
    private static void ensureEnabled() {
        if (!enabled) {
            throw new BusinessException("动态查询引擎已全局禁用 (ateng.boot.mybatis-plus.dynamic-query.enabled=false)");
        }
    }

    /**
     * 将受控操作符条件安全追加至 QueryWrapper
     */
    private static <T> void applyCondition(QueryWrapper<T> wrapper, String column, QueryOperator operator, Object value, List<Object> values) {
        if (operator == null) {
            operator = QueryOperator.EQ;
        }
        switch (operator) {
            case EQ -> {
                if (value != null) wrapper.eq(column, value);
            }
            case NE -> {
                if (value != null) wrapper.ne(column, value);
            }
            case GT -> {
                if (value != null) wrapper.gt(column, value);
            }
            case GE -> {
                if (value != null) wrapper.ge(column, value);
            }
            case LT -> {
                if (value != null) wrapper.lt(column, value);
            }
            case LE -> {
                if (value != null) wrapper.le(column, value);
            }
            case LIKE -> {
                if (value != null && !value.toString().trim().isEmpty()) {
                    wrapper.like(column, value.toString().trim());
                }
            }
            case LIKE_LEFT -> {
                if (value != null && !value.toString().trim().isEmpty()) {
                    wrapper.likeLeft(column, value.toString().trim());
                }
            }
            case LIKE_RIGHT -> {
                if (value != null && !value.toString().trim().isEmpty()) {
                    wrapper.likeRight(column, value.toString().trim());
                }
            }
            case IN -> {
                if (values != null && !values.isEmpty()) {
                    wrapper.in(column, values);
                } else if (value instanceof Collection<?> col && !col.isEmpty()) {
                    wrapper.in(column, col);
                }
            }
            case NOT_IN -> {
                if (values != null && !values.isEmpty()) {
                    wrapper.notIn(column, values);
                } else if (value instanceof Collection<?> col && !col.isEmpty()) {
                    wrapper.notIn(column, col);
                }
            }
            case BETWEEN -> {
                if (values != null && values.size() >= 2) {
                    wrapper.between(column, values.get(0), values.get(1));
                }
            }
            case IS_NULL -> wrapper.isNull(column);
            case IS_NOT_NULL -> wrapper.isNotNull(column);
        }
    }

    /**
     * 将 PageQuery 中携带的 sorts 列表安全校验后装配至 QueryWrapper
     */
    private static <T> void applySorts(QueryWrapper<T> wrapper, PageQuery pageQuery, EntityMetaInfo metaInfo) {
        if (pageQuery.getSorts() == null || pageQuery.getSorts().isEmpty()) {
            return;
        }
        for (SortItem sort : pageQuery.getSorts()) {
            if (sort == null || sort.getProperty() == null) {
                continue;
            }
            String prop = sort.getProperty().trim();
            if (!SortItem.isValidProperty(prop)) {
                continue;
            }
            String normalized = prop.toLowerCase(Locale.ROOT);
            if (metaInfo.isIgnored(normalized)) {
                log.warn("排序属性命中敏感黑名单，已安全忽略: {}", prop);
                continue;
            }
            FieldMetaInfo fieldMeta = metaInfo.getField(normalized);
            if (fieldMeta == null) {
                log.warn("排序属性未在实体白名单中声明，已安全忽略: {}", prop);
                continue;
            }
            wrapper.orderBy(true, sort.isAsc(), fieldMeta.getColumn());
        }
    }

    /**
     * 判断对象值是否为空（null、空字符串或空集合）
     */
    private static boolean isValueEmpty(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof CharSequence cs) {
            return cs.toString().trim().isEmpty();
        }
        if (value instanceof Collection<?> col) {
            return col.isEmpty();
        }
        if (value.getClass().isArray()) {
            return ((Object[]) value).length == 0;
        }
        return false;
    }

    /**
     * 递归获取类及其父类所有声明的字段
     */
    private static List<Field> getAllDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            fields.addAll(Arrays.asList(current.getDeclaredFields()));
            current = current.getSuperclass();
        }
        return fields;
    }

    /**
     * 获取或构建目标实体的元数据缓存
     */
    private static EntityMetaInfo getOrCreateEntityMeta(Class<?> entityClass) {
        return ENTITY_META_CACHE.computeIfAbsent(entityClass, DynamicQueryHelper::resolveEntityMeta);
    }

    /**
     * 解析实体类型的白名单与黑名单元数据
     */
    private static EntityMetaInfo resolveEntityMeta(Class<?> entityClass) {
        Map<String, FieldMetaInfo> propertyMap = new ConcurrentHashMap<>();
        Set<String> ignoredProperties = new HashSet<>();

        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        List<Field> allFields = getAllDeclaredFields(entityClass);

        // 1. 扫描黑名单注解与非物理字段
        for (Field field : allFields) {
            String propName = field.getName().toLowerCase(Locale.ROOT);

            // 标注 @DynamicQueryIgnore
            if (field.isAnnotationPresent(DynamicQueryIgnore.class)) {
                ignoredProperties.add(propName);
            }
            // 标注 @FieldEncrypt 存储加密字段
            if (field.isAnnotationPresent(FieldEncrypt.class)) {
                ignoredProperties.add(propName);
            }
            // 标注 @TableField(exist = false)
            TableField tf = field.getAnnotation(TableField.class);
            if (tf != null && !tf.exist()) {
                ignoredProperties.add(propName);
            }
            // 启发式敏感字段拦截
            if (HEURISTIC_SENSITIVE_PROPERTIES.contains(propName)) {
                ignoredProperties.add(propName);
            }
        }

        // 2. 扫描逻辑删除字段
        if (tableInfo != null && tableInfo.isLogicDelete() && tableInfo.getLogicDeleteFieldInfo() != null) {
            ignoredProperties.add(tableInfo.getLogicDeleteFieldInfo().getProperty().toLowerCase(Locale.ROOT));
            ignoredProperties.add(tableInfo.getLogicDeleteFieldInfo().getColumn().toLowerCase(Locale.ROOT));
        }

        // 3. 构建实体物理字段白名单
        if (tableInfo != null) {
            // 主键
            if (tableInfo.getKeyProperty() != null && tableInfo.getKeyColumn() != null) {
                String keyProp = tableInfo.getKeyProperty();
                String keyCol = tableInfo.getKeyColumn();
                FieldMetaInfo meta = new FieldMetaInfo(keyProp, keyCol, tableInfo.getKeyType());
                registerFieldMeta(propertyMap, keyProp, keyCol, meta);
            }
            // 普通列
            if (tableInfo.getFieldList() != null) {
                for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
                    String prop = fieldInfo.getProperty();
                    String col = fieldInfo.getColumn();
                    FieldMetaInfo meta = new FieldMetaInfo(prop, col, fieldInfo.getPropertyType());
                    registerFieldMeta(propertyMap, prop, col, meta);
                }
            }
        } else {
            // 未初始化 TableInfo 时（如轻量级单测），依托反射构建白名单
            for (Field field : allFields) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                String prop = field.getName();
                String col = com.baomidou.mybatisplus.core.toolkit.StringUtils.camelToUnderline(prop);
                TableField tf = field.getAnnotation(TableField.class);
                if (tf != null && !tf.value().trim().isEmpty()) {
                    col = tf.value().trim();
                }
                FieldMetaInfo meta = new FieldMetaInfo(prop, col, field.getType());
                registerFieldMeta(propertyMap, prop, col, meta);
            }
        }

        return new EntityMetaInfo(propertyMap, ignoredProperties);
    }

    private static void registerFieldMeta(Map<String, FieldMetaInfo> map, String prop, String col, FieldMetaInfo meta) {
        if (prop != null) {
            map.put(prop.toLowerCase(Locale.ROOT), meta);
        }
        if (col != null) {
            map.put(col.toLowerCase(Locale.ROOT), meta);
        }
    }

    /**
     * 字段元数据
     */
    private static class FieldMetaInfo {
        private final String property;
        private final String column;
        private final Class<?> fieldType;

        public FieldMetaInfo(String property, String column, Class<?> fieldType) {
            this.property = property;
            this.column = column;
            this.fieldType = fieldType;
        }

        public String getProperty() {
            return property;
        }

        public String getColumn() {
            return column;
        }

        public Class<?> getFieldType() {
            return fieldType;
        }
    }

    /**
     * 实体元数据聚合对象
     */
    private static class EntityMetaInfo {
        private final Map<String, FieldMetaInfo> propertyMap;
        private final Set<String> ignoredProperties;

        public EntityMetaInfo(Map<String, FieldMetaInfo> propertyMap, Set<String> ignoredProperties) {
            this.propertyMap = propertyMap;
            this.ignoredProperties = ignoredProperties;
        }

        public boolean isIgnored(String normalizedProp) {
            return ignoredProperties.contains(normalizedProp);
        }

        public FieldMetaInfo getField(String normalizedProp) {
            return propertyMap.get(normalizedProp);
        }
    }
}
