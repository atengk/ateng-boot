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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 动态查询与排序安全转译引擎门面
 * 基于实体元数据纳秒级白名单缓存与多表 AST 投影解析字典将结构化请求安全编译为 MyBatis-Plus QueryWrapper，
 * 落实严格安全防卫以杜绝 SQL 注入与未授权数据探测，兼顾单表极速编译与多表联表别名自愈。
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

    /**
     * 安全属性名或列名白名单正则（允许纯属性名或带表别名的点分列名，如 "deptName" 或 "d.dept_name"，防御 SQL 注入）
     */
    private static final Pattern SAFE_PROPERTY_OR_COLUMN_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)?$");

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
        Objects.requireNonNull(entityClass, "实体类型 entityClass 不能为 null");
        return buildWrapper(request, entityClass, null);
    }

    /**
     * 基于结构化动态查询请求与投影映射字典构建安全 QueryWrapper（多表联表场景）
     *
     * @param request           动态查询请求模型（承载 conditions 与 sorts）
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典（属性名 -> 物理表别名/列名）
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(DynamicQueryRequest request, Map<String, String> projectionMapping) {
        return buildWrapper(request, null, projectionMapping);
    }

    /**
     * 基于结构化动态查询请求、结果类型与投影映射字典构建安全 QueryWrapper（全功能多表联表与逃生通道模式）
     *
     * @param request           动态查询请求模型（承载 conditions 与 sorts）
     * @param resultClass       目标结果类型（Entity 或 VO，支持通过 @QueryField 声明逃生通道）
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典（属性名 -> 物理表别名/列名）
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(DynamicQueryRequest request, Class<T> resultClass, Map<String, String> projectionMapping) {
        ensureEnabled();
        if (resultClass == null && (projectionMapping == null || projectionMapping.isEmpty())) {
            throw new IllegalArgumentException("动态查询必须提供结果类型 resultClass 或有效的投影映射字典 projectionMapping");
        }

        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (request == null) {
            return wrapper;
        }

        EntityMetaInfo metaInfo = (resultClass != null) ? getOrCreateEntityMeta(resultClass) : null;

        // 1. 过滤并应用动态条件（区分常规 AND 条件与 or_组号 作用域嵌套分组）
        if (request.getConditions() != null && !request.getConditions().isEmpty()) {
            List<ValidConditionItem> andItems = new ArrayList<>();
            Map<String, List<ValidConditionItem>> orGroupMap = new LinkedHashMap<>();

            for (QueryCondition condition : request.getConditions()) {
                if (condition == null) {
                    continue;
                }
                String field = condition.getField();
                if (field == null || field.trim().isEmpty() || !isValidFieldOrProperty(field.trim())) {
                    log.warn("动态查询检测到非法字段语法或潜在注入字符，已安全忽略: {}", field);
                    continue;
                }
                String column = resolveColumn(field.trim(), projectionMapping, metaInfo);
                if (column == null) {
                    continue;
                }
                Class<?> targetType = resolveFieldType(field.trim(), column, projectionMapping, metaInfo);

                ValidConditionItem item = new ValidConditionItem(column, condition, targetType);
                if (condition.getOrGroup() != null && !condition.getOrGroup().trim().isEmpty()) {
                    orGroupMap.computeIfAbsent(condition.getOrGroup().trim(), k -> new ArrayList<>()).add(item);
                } else {
                    andItems.add(item);
                }
            }

            // 应用常规 AND 顶层条件
            for (ValidConditionItem item : andItems) {
                applyCondition(wrapper, item.column(), item.condition().getOperator(),
                        item.condition().getValue(), item.condition().getValues(), item.targetType());
            }

            // 应用 or_组号 作用域嵌套 OR 逻辑块
            for (Map.Entry<String, List<ValidConditionItem>> groupEntry : orGroupMap.entrySet()) {
                List<ValidConditionItem> groupItems = groupEntry.getValue();
                if (groupItems.isEmpty()) {
                    continue;
                }
                wrapper.and(orWrapper -> {
                    boolean first = true;
                    for (ValidConditionItem groupItem : groupItems) {
                        if (!first) {
                            orWrapper.or();
                        }
                        applyCondition(orWrapper, groupItem.column(), groupItem.condition().getOperator(),
                                groupItem.condition().getValue(), groupItem.condition().getValues(), groupItem.targetType());
                        first = false;
                    }
                });
            }
        }

        // 2. 过滤并应用排序规则
        applySorts(wrapper, request, projectionMapping, metaInfo);

        return wrapper;
    }

    /**
     * 基于 URL 查询字符串与实体类型构建安全 QueryWrapper
     *
     * @param queryString URL 查询字符串（如 "name:like=admin&or_1:status=1&or_1:role=admin"）
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(String queryString, Class<T> entityClass) {
        return buildWrapper(queryString, entityClass, null);
    }

    /**
     * 基于 URL 查询字符串与投影映射字典构建安全 QueryWrapper（多表联表场景）
     *
     * @param queryString       URL 查询字符串
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(String queryString, Map<String, String> projectionMapping) {
        return buildWrapper(queryString, null, projectionMapping);
    }

    /**
     * 基于 URL 查询字符串、结果类型与投影映射字典构建安全 QueryWrapper
     *
     * @param queryString       URL 查询字符串
     * @param resultClass       目标结果类型
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(String queryString, Class<T> resultClass, Map<String, String> projectionMapping) {
        DynamicQueryRequest request = DynamicQueryParser.parse(queryString);
        return buildWrapper(request, resultClass, projectionMapping);
    }

    /**
     * 基于请求参数 Map 与实体类型构建安全 QueryWrapper
     *
     * @param parameterMap 请求参数 Map（支持 Map<String, String[]>、Map<String, List<String>> 或 Map<String, Object>）
     * @param entityClass  目标实体类型
     * @param <T>          实体泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Map<String, ?> parameterMap, Class<T> entityClass) {
        return buildWrapper(parameterMap, entityClass, null);
    }

    /**
     * 基于请求参数 Map 与投影映射字典构建安全 QueryWrapper（多表联表场景）
     *
     * @param parameterMap      请求参数 Map
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Map<String, ?> parameterMap, Map<String, String> projectionMapping) {
        return buildWrapper(parameterMap, null, projectionMapping);
    }

    /**
     * 基于请求参数 Map、结果类型与投影映射字典构建安全 QueryWrapper
     *
     * @param parameterMap      请求参数 Map
     * @param resultClass       目标结果类型
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Map<String, ?> parameterMap, Class<T> resultClass, Map<String, String> projectionMapping) {
        DynamicQueryRequest request = DynamicQueryParser.parse(parameterMap);
        return buildWrapper(request, resultClass, projectionMapping);
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
        return buildWrapper(dto, entityClass, null);
    }

    /**
     * 基于业务查询 DTO 对象与投影映射字典构建安全 QueryWrapper（多表联表场景）
     *
     * @param dto               业务查询 DTO
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Object dto, Map<String, String> projectionMapping) {
        return buildWrapper(dto, null, projectionMapping);
    }

    /**
     * 基于业务查询 DTO 对象、结果类型与投影映射字典构建安全 QueryWrapper（全功能多表联表模式）
     *
     * @param dto               业务查询 DTO
     * @param resultClass       目标结果类型（支持 VO @QueryField 逃生通道）
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param <T>               泛型
     * @return 装配完成且安全过滤后的 QueryWrapper 实例
     */
    public static <T> QueryWrapper<T> buildWrapper(Object dto, Class<T> resultClass, Map<String, String> projectionMapping) {
        ensureEnabled();
        if (resultClass == null && (projectionMapping == null || projectionMapping.isEmpty())) {
            throw new IllegalArgumentException("动态查询必须提供结果类型 resultClass 或有效的投影映射字典 projectionMapping");
        }

        if (dto instanceof DynamicQueryRequest request) {
            return buildWrapper(request, resultClass, projectionMapping);
        }

        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (dto == null) {
            return wrapper;
        }

        EntityMetaInfo metaInfo = (resultClass != null) ? getOrCreateEntityMeta(resultClass) : null;
        List<Field> fields = getAllDeclaredFields(dto.getClass());

        for (Field field : fields) {
            if (Modifier.isStatic(field.getModifiers()) || field.getDeclaringClass() == PageQuery.class) {
                continue;
            }
            QueryField queryField = field.getAnnotation(QueryField.class);
            String property;
            String explicitColumn = null;
            if (queryField != null) {
                String val = !queryField.value().trim().isEmpty() ? queryField.value().trim() : queryField.column().trim();
                if (!val.isEmpty()) {
                    explicitColumn = val;
                }
                if (!queryField.property().trim().isEmpty()) {
                    property = queryField.property().trim();
                } else {
                    property = field.getName();
                }
            } else {
                property = field.getName();
            }

            if (!isValidFieldOrProperty(property)) {
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

            String targetColumn;
            if (explicitColumn != null) {
                String normCol = explicitColumn.toLowerCase(Locale.ROOT);
                if (HEURISTIC_SENSITIVE_PROPERTIES.contains(normCol)) {
                    log.warn("DTO 查询列命中敏感黑名单，已安全忽略: {}", explicitColumn);
                    continue;
                }
                targetColumn = explicitColumn;
            } else {
                targetColumn = resolveColumn(property, projectionMapping, metaInfo);
            }

            if (targetColumn == null) {
                continue;
            }

            QueryOperator operator = (queryField != null) ? queryField.operator() : QueryOperator.EQ;
            List<Object> values = null;
            if (value instanceof Collection<?> col) {
                values = new ArrayList<>(col);
            }
            applyCondition(wrapper, targetColumn, operator, value, values, field.getType());
        }

        // 若 DTO 继承了 PageQuery，自动处理其排序规则
        if (dto instanceof PageQuery pageQuery) {
            applySorts(wrapper, pageQuery, projectionMapping, metaInfo);
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
     * 校验字段名称或列名是否符合安全白名单（仅允许字母、数字、下划线及至多一个点号）
     */
    private static boolean isValidFieldOrProperty(String field) {
        return field != null && SAFE_PROPERTY_OR_COLUMN_PATTERN.matcher(field.trim()).matches();
    }

    /**
     * 智能解析动态查询或排序属性对应的目标物理列或表达式
     * 依次按敏感词黑名单拦截、AST 投影字典自愈推导、实体/VO 元数据白名单（含 @QueryField 逃生通道）匹配
     *
     * @param property          前端或调用方传递的属性名称（如 "deptName"）
     * @param projectionMapping 外部/XML 动态 SQL 的 AST 投影字典
     * @param metaInfo          实体或 VO 元数据模型
     * @return 底层真实物理列或表达式（如 "d.name"），无法匹配或命中黑名单时返回 null
     */
    private static String resolveColumn(String property, Map<String, String> projectionMapping, EntityMetaInfo metaInfo) {
        if (property == null || property.trim().isEmpty()) {
            return null;
        }
        String prop = property.trim();
        String normalized = prop.toLowerCase(Locale.ROOT);

        // 1. 启发式敏感黑名单过滤
        if (HEURISTIC_SENSITIVE_PROPERTIES.contains(normalized)) {
            log.warn("动态查询属性命中敏感黑名单，已安全忽略: {}", prop);
            return null;
        }

        // 2. 检查实体/VO 元数据中的显式黑名单（如 @DynamicQueryIgnore）
        if (metaInfo != null && metaInfo.isIgnored(normalized)) {
            log.warn("动态查询属性命中安全黑名单或敏感标记，已安全忽略: {}", prop);
            return null;
        }

        // 3. 第一优先级：AST 投影字典自愈推导
        if (projectionMapping != null && !projectionMapping.isEmpty()) {
            String astColumn = DynamicSqlProjectionResolver.findColumn(projectionMapping, prop);
            if (astColumn != null) {
                return astColumn;
            }
        }

        // 4. 第二优先级：实体/VO 白名单（含 @QueryField("d.dept_name") 逃生通道）
        if (metaInfo != null) {
            FieldMetaInfo fieldMeta = metaInfo.getField(normalized);
            if (fieldMeta != null) {
                return fieldMeta.getColumn();
            }
        }

        log.warn("动态查询属性未在 AST 投影字典或实体白名单中声明，已安全忽略: {}", prop);
        return null;
    }

    /**
     * 将受控操作符条件安全追加至 QueryWrapper（具备强类型方言自适应转换能力）
     */
    private static <T> void applyCondition(QueryWrapper<T> wrapper, String column, QueryOperator operator,
                                           Object value, List<Object> values, Class<?> targetType) {
        if (operator == null) {
            operator = QueryOperator.EQ;
        }
        Object convertedValue = convertSingleValue(value, targetType, operator);
        List<Object> convertedValues = null;
        if (values != null) {
            convertedValues = new ArrayList<>(values.size());
            for (Object v : values) {
                convertedValues.add(convertSingleValue(v, targetType, operator));
            }
        }

        switch (operator) {
            case EQ -> {
                if (convertedValue != null) wrapper.eq(column, convertedValue);
            }
            case NE -> {
                if (convertedValue != null) wrapper.ne(column, convertedValue);
            }
            case GT -> {
                if (convertedValue != null) wrapper.gt(column, convertedValue);
            }
            case GE -> {
                if (convertedValue != null) wrapper.ge(column, convertedValue);
            }
            case LT -> {
                if (convertedValue != null) wrapper.lt(column, convertedValue);
            }
            case LE -> {
                if (convertedValue != null) wrapper.le(column, convertedValue);
            }
            case LIKE -> {
                if (convertedValue != null && !convertedValue.toString().trim().isEmpty()) {
                    wrapper.like(column, convertedValue.toString().trim());
                }
            }
            case LIKE_LEFT -> {
                if (convertedValue != null && !convertedValue.toString().trim().isEmpty()) {
                    wrapper.likeLeft(column, convertedValue.toString().trim());
                }
            }
            case LIKE_RIGHT -> {
                if (convertedValue != null && !convertedValue.toString().trim().isEmpty()) {
                    wrapper.likeRight(column, convertedValue.toString().trim());
                }
            }
            case IN -> {
                if (convertedValues != null && !convertedValues.isEmpty()) {
                    wrapper.in(column, convertedValues);
                } else if (convertedValue instanceof Collection<?> col && !col.isEmpty()) {
                    wrapper.in(column, col);
                }
            }
            case NOT_IN -> {
                if (convertedValues != null && !convertedValues.isEmpty()) {
                    wrapper.notIn(column, convertedValues);
                } else if (convertedValue instanceof Collection<?> col && !col.isEmpty()) {
                    wrapper.notIn(column, col);
                }
            }
            case BETWEEN -> {
                if (convertedValues != null && convertedValues.size() >= 2) {
                    wrapper.between(column, convertedValues.get(0), convertedValues.get(1));
                }
            }
            case IS_NULL -> wrapper.isNull(column);
            case IS_NOT_NULL -> wrapper.isNotNull(column);
        }
    }

    /**
     * 解析字段的目标强类型（优先从实体/VO 元数据推导，保障 PostgreSQL 等强类型方言参数自适应）
     *
     * @param property          原始属性名
     * @param resolvedColumn    已解析出的物理列名
     * @param projectionMapping AST 投影字典
     * @param metaInfo          实体/VO 元数据聚合
     * @return 目标字段类型，若未推导成功则返回 null
     */
    private static Class<?> resolveFieldType(String property, String resolvedColumn, Map<String, String> projectionMapping, EntityMetaInfo metaInfo) {
        if (metaInfo == null) {
            return null;
        }
        // 1. 尝试直接以属性名匹配
        if (property != null) {
            FieldMetaInfo meta = metaInfo.getField(property.trim().toLowerCase(Locale.ROOT));
            if (meta != null && meta.getFieldType() != null) {
                return meta.getFieldType();
            }
        }
        // 2. 尝试以解析出的物理列名匹配（如 u.age -> age）
        if (resolvedColumn != null) {
            String col = resolvedColumn.trim();
            int dotIdx = col.lastIndexOf('.');
            String colWithoutAlias = (dotIdx >= 0) ? col.substring(dotIdx + 1) : col;
            FieldMetaInfo meta = metaInfo.getField(colWithoutAlias.toLowerCase(Locale.ROOT));
            if (meta != null && meta.getFieldType() != null) {
                return meta.getFieldType();
            }
        }
        return null;
    }

    /**
     * 将输入值根据目标类型与操作符安全自适应转换
     *
     * @param value      原始值
     * @param targetType 目标强类型（可为 null）
     * @param operator   查询操作符
     * @return 转换后的强类型对象，若无法推导或解析失败则保底返回原始值
     */
    private static Object convertSingleValue(Object value, Class<?> targetType, QueryOperator operator) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String rawStr)) {
            return value;
        }
        String str = rawStr.trim();
        if (str.isEmpty()) {
            return (targetType == String.class) ? "" : null;
        }

        // 模糊匹配系列操作符强制保留字符串，严禁转为数值或对象
        if (operator == QueryOperator.LIKE || operator == QueryOperator.LIKE_LEFT || operator == QueryOperator.LIKE_RIGHT) {
            return str;
        }

        // 1. 若已知目标强类型，执行精准转换
        if (targetType != null) {
            try {
                if (targetType == String.class || targetType == CharSequence.class) {
                    return str;
                }
                if (targetType == Integer.class || targetType == int.class) {
                    return Integer.valueOf(str);
                }
                if (targetType == Long.class || targetType == long.class) {
                    return Long.valueOf(str);
                }
                if (targetType == Short.class || targetType == short.class) {
                    return Short.valueOf(str);
                }
                if (targetType == Byte.class || targetType == byte.class) {
                    return Byte.valueOf(str);
                }
                if (targetType == Double.class || targetType == double.class) {
                    return Double.valueOf(str);
                }
                if (targetType == Float.class || targetType == float.class) {
                    return Float.valueOf(str);
                }
                if (targetType == BigDecimal.class) {
                    return new BigDecimal(str);
                }
                if (targetType == BigInteger.class) {
                    return new BigInteger(str);
                }
                if (targetType == Boolean.class || targetType == boolean.class) {
                    if ("1".equals(str) || "true".equalsIgnoreCase(str)) {
                        return Boolean.TRUE;
                    }
                    if ("0".equals(str) || "false".equalsIgnoreCase(str)) {
                        return Boolean.FALSE;
                    }
                    return Boolean.valueOf(str);
                }
                if (targetType == LocalDate.class) {
                    if (str.length() >= 10) {
                        return LocalDate.parse(str.substring(0, 10));
                    }
                    return LocalDate.parse(str);
                }
                if (targetType == LocalDateTime.class) {
                    String isoStr = str.replace(' ', 'T');
                    if (isoStr.length() == 10) {
                        return LocalDate.parse(isoStr).atStartOfDay();
                    }
                    return LocalDateTime.parse(isoStr);
                }
                if (targetType == LocalTime.class) {
                    return LocalTime.parse(str);
                }
                if (targetType == Instant.class) {
                    return Instant.parse(str);
                }
            } catch (Exception e) {
                log.warn("动态查询参数类型转换失败，已保底回退原始值. targetType={}, value={}, error={}", targetType.getSimpleName(), str, e.getMessage());
                return str;
            }
        }

        // 2. 若未提供 targetType，仅在数值比较操作符下尝试启发式推断数值（防御弱类型 SQL 方言）
        if (operator == QueryOperator.GT || operator == QueryOperator.GE
                || operator == QueryOperator.LT || operator == QueryOperator.LE
                || operator == QueryOperator.BETWEEN) {
            try {
                if (str.matches("^-?\\d+$")) {
                    if (str.length() < 10) {
                        return Integer.valueOf(str);
                    }
                    return Long.valueOf(str);
                }
                if (str.matches("^-?\\d+\\.\\d+$")) {
                    return Double.valueOf(str);
                }
            } catch (Exception ignored) {
            }
        }

        return str;
    }

    /**
     * 将 PageQuery 中携带的 sorts 列表安全校验后装配至 QueryWrapper
     */
    private static <T> void applySorts(QueryWrapper<T> wrapper, PageQuery pageQuery, Map<String, String> projectionMapping, EntityMetaInfo metaInfo) {
        if (pageQuery.getSorts() == null || pageQuery.getSorts().isEmpty()) {
            return;
        }
        for (SortItem sort : pageQuery.getSorts()) {
            if (sort == null || sort.getProperty() == null) {
                continue;
            }
            String prop = sort.getProperty().trim();
            if (!isValidFieldOrProperty(prop)) {
                log.warn("排序属性检测到非法语法或特殊字符，已安全忽略: {}", prop);
                continue;
            }
            String sortColumn = resolveColumn(prop, projectionMapping, metaInfo);
            if (sortColumn == null) {
                continue;
            }
            wrapper.orderBy(true, sort.isAsc(), sortColumn);
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
     * 解析实体类型的白名单与黑名单元数据（支持 VO @QueryField 逃生通道）
     */
    private static EntityMetaInfo resolveEntityMeta(Class<?> entityClass) {
        Map<String, FieldMetaInfo> propertyMap = new ConcurrentHashMap<>();
        Set<String> ignoredProperties = new HashSet<>();

        TableInfo tableInfo = TableInfoHelper.getTableInfo(entityClass);
        List<Field> allFields = getAllDeclaredFields(entityClass);

        // 1. 扫描黑名单注解与非物理字段
        for (Field field : allFields) {
            String propName = field.getName().toLowerCase(Locale.ROOT);
            String colName = DynamicSqlProjectionResolver.camelToUnderline(field.getName()).toLowerCase(Locale.ROOT);

            boolean ignored = false;
            // 标注 @DynamicQueryIgnore
            if (field.isAnnotationPresent(DynamicQueryIgnore.class)) {
                ignored = true;
            }
            // 标注 @FieldEncrypt 存储加密字段
            if (field.isAnnotationPresent(FieldEncrypt.class)) {
                ignored = true;
            }
            // 标注 @TableField(exist = false)
            TableField tf = field.getAnnotation(TableField.class);
            if (tf != null) {
                if (!tf.exist()) {
                    ignored = true;
                }
                if (!tf.value().trim().isEmpty()) {
                    colName = tf.value().trim().toLowerCase(Locale.ROOT);
                }
            }
            // 启发式敏感字段拦截
            if (HEURISTIC_SENSITIVE_PROPERTIES.contains(propName) || HEURISTIC_SENSITIVE_PROPERTIES.contains(colName)) {
                ignored = true;
            }

            if (ignored) {
                ignoredProperties.add(propName);
                ignoredProperties.add(colName);
            }
        }

        // 2. 扫描逻辑删除字段与物理列名映射
        if (tableInfo != null) {
            if (tableInfo.isLogicDelete() && tableInfo.getLogicDeleteFieldInfo() != null) {
                ignoredProperties.add(tableInfo.getLogicDeleteFieldInfo().getProperty().toLowerCase(Locale.ROOT));
                ignoredProperties.add(tableInfo.getLogicDeleteFieldInfo().getColumn().toLowerCase(Locale.ROOT));
            }
            // 确保 tableInfo 中标注了黑名单的列名也被双向收录
            if (tableInfo.getFieldList() != null) {
                for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
                    String prop = fieldInfo.getProperty().toLowerCase(Locale.ROOT);
                    String col = fieldInfo.getColumn().toLowerCase(Locale.ROOT);
                    if (ignoredProperties.contains(prop) || ignoredProperties.contains(col)) {
                        ignoredProperties.add(prop);
                        ignoredProperties.add(col);
                    }
                }
            }
        }

        // 3. 构建实体物理字段白名单（被忽略的属性严格禁止注册入白名单）
        if (tableInfo != null) {
            // 主键
            if (tableInfo.getKeyProperty() != null && tableInfo.getKeyColumn() != null) {
                String keyProp = tableInfo.getKeyProperty();
                String keyCol = tableInfo.getKeyColumn();
                if (!ignoredProperties.contains(keyProp.toLowerCase(Locale.ROOT))
                        && !ignoredProperties.contains(keyCol.toLowerCase(Locale.ROOT))) {
                    FieldMetaInfo meta = new FieldMetaInfo(keyProp, keyCol, tableInfo.getKeyType());
                    registerFieldMeta(propertyMap, keyProp, keyCol, meta);
                }
            }
            // 普通列
            if (tableInfo.getFieldList() != null) {
                for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
                    String prop = fieldInfo.getProperty();
                    String col = fieldInfo.getColumn();
                    if (!ignoredProperties.contains(prop.toLowerCase(Locale.ROOT))
                            && !ignoredProperties.contains(col.toLowerCase(Locale.ROOT))) {
                        FieldMetaInfo meta = new FieldMetaInfo(prop, col, fieldInfo.getPropertyType());
                        registerFieldMeta(propertyMap, prop, col, meta);
                    }
                }
            }
        } else {
            // 未初始化 TableInfo 时（如轻量级单测或纯 VO），依托反射构建白名单（同时支持 @QueryField 逃生通道）
            for (Field field : allFields) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                String prop = field.getName();
                String col = DynamicSqlProjectionResolver.camelToUnderline(prop);

                // 检查 @TableField 显式列名
                TableField tf = field.getAnnotation(TableField.class);
                if (tf != null && !tf.value().trim().isEmpty()) {
                    col = tf.value().trim();
                }

                // 检查 @QueryField 逃生通道（支持带表别名列，如 "d.dept_name"）
                QueryField qf = field.getAnnotation(QueryField.class);
                if (qf != null) {
                    String val = !qf.value().trim().isEmpty() ? qf.value().trim() : qf.column().trim();
                    if (!val.isEmpty()) {
                        col = val;
                    }
                }

                if (!ignoredProperties.contains(prop.toLowerCase(Locale.ROOT))
                        && !ignoredProperties.contains(col.toLowerCase(Locale.ROOT))) {
                    FieldMetaInfo meta = new FieldMetaInfo(prop, col, field.getType());
                    registerFieldMeta(propertyMap, prop, col, meta);
                }
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
            if (ignoredProperties.contains(normalizedProp)) {
                return true;
            }
            FieldMetaInfo meta = propertyMap.get(normalizedProp);
            if (meta != null) {
                return ignoredProperties.contains(meta.getProperty().toLowerCase(Locale.ROOT))
                        || ignoredProperties.contains(meta.getColumn().toLowerCase(Locale.ROOT));
            }
            return false;
        }

        public FieldMetaInfo getField(String normalizedProp) {
            return propertyMap.get(normalizedProp);
        }
    }

    /**
     * 校验合法的单项条件元数据绑定（携带目标字段强类型元数据）
     */
    private record ValidConditionItem(String column, QueryCondition condition, Class<?> targetType) {
    }
}
