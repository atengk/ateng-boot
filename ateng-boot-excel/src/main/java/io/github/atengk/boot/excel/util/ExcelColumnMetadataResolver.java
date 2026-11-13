package io.github.atengk.boot.excel.util;

import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Excel 实体列索引与属性元数据统一解析器
 * <p>
 * 收敛跨读写链路的实体字段扫描与列序解析规则，严格对齐 Apache Fesod 运行时列映射模型：
 * 自动忽略 {@code static}、{@code transient}、{@link ExcelIgnore} 字段，支持类级
 * {@link ExcelIgnoreUnannotated} 与乱序 {@link ExcelProperty#index()} 显式列定位，
 * 并内置类级并发安全缓存。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-13
 */
public final class ExcelColumnMetadataResolver {

    private static final Map<Class<?>, EntityColumnMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    private static final EntityColumnMetadata EMPTY_METADATA = new EntityColumnMetadata(
            1,
            Collections.emptyList(),
            Collections.emptyMap(),
            Collections.emptyList()
    );

    private ExcelColumnMetadataResolver() {
    }

    /**
     * 解析或从缓存获取目标实体类的完整列元数据
     *
     * @param clazz 目标实体类 Class
     * @return 结构化实体列元数据（入参为 null 或 Object 时返回空元数据）
     */
    public static EntityColumnMetadata resolve(Class<?> clazz) {
        if (clazz == null || clazz == Object.class) {
            return EMPTY_METADATA;
        }
        return METADATA_CACHE.computeIfAbsent(clazz, ExcelColumnMetadataResolver::scanMetadata);
    }

    /**
     * 获取类及其父类中参与 Excel 导入导出的有序字段名称列表
     *
     * @param clazz 目标实体类 Class
     * @return 按物理列序排列的只读字段名列表
     */
    public static List<String> getDeclaredFieldNames(Class<?> clazz) {
        return resolve(clazz).declaredFieldNames();
    }

    private static EntityColumnMetadata scanMetadata(Class<?> clazz) {
        List<Class<?>> hierarchy = collectClassHierarchy(clazz);
        boolean ignoreUnannotated = hasIgnoreUnannotated(hierarchy);
        List<Field> eligibleFields = collectEligibleFields(hierarchy, ignoreUnannotated);

        if (eligibleFields.isEmpty()) {
            return EMPTY_METADATA;
        }

        // 分离显式指定 index 的列与需要顺序分配列号的隐式列
        Map<Integer, Field> explicitIndexFields = new TreeMap<>();
        List<Field> implicitFields = new ArrayList<>();

        for (Field field : eligibleFields) {
            ExcelProperty prop = field.getAnnotation(ExcelProperty.class);
            if (prop != null && prop.index() >= 0) {
                explicitIndexFields.putIfAbsent(prop.index(), field);
            } else {
                implicitFields.add(field);
            }
        }

        // 未显式指定 index 的列按 @ExcelProperty(order) 升序与原声明顺序稳定排序
        Map<Field, Integer> declarationOrder = new IdentityHashMap<>(eligibleFields.size());
        for (int i = 0; i < eligibleFields.size(); i++) {
            declarationOrder.put(eligibleFields.get(i), i);
        }
        implicitFields.sort((f1, f2) -> {
            ExcelProperty p1 = f1.getAnnotation(ExcelProperty.class);
            ExcelProperty p2 = f2.getAnnotation(ExcelProperty.class);
            int o1 = (p1 != null) ? p1.order() : Integer.MAX_VALUE;
            int o2 = (p2 != null) ? p2.order() : Integer.MAX_VALUE;
            if (o1 != o2) {
                return Integer.compare(o1, o2);
            }
            return Integer.compare(declarationOrder.get(f1), declarationOrder.get(f2));
        });

        // 按 Fesod 对齐策略填空槽位分配物理列索引
        Map<Integer, ColumnMetadata> sortedColumnMap = new TreeMap<>();
        int cursor = 0;
        for (Field implicitField : implicitFields) {
            while (explicitIndexFields.containsKey(cursor)) {
                Field explicitField = explicitIndexFields.remove(cursor);
                sortedColumnMap.put(cursor, buildColumnMetadata(cursor, explicitField));
                cursor++;
            }
            sortedColumnMap.put(cursor, buildColumnMetadata(cursor, implicitField));
            cursor++;
        }
        for (Map.Entry<Integer, Field> entry : explicitIndexFields.entrySet()) {
            sortedColumnMap.put(entry.getKey(), buildColumnMetadata(entry.getKey(), entry.getValue()));
        }

        List<ColumnMetadata> columns = new ArrayList<>(sortedColumnMap.size());
        Map<String, ColumnMetadata> columnByFieldName = new LinkedHashMap<>(sortedColumnMap.size() * 2);
        List<String> declaredFieldNames = new ArrayList<>(sortedColumnMap.size());
        int maxHeadDepth = 1;

        for (ColumnMetadata col : sortedColumnMap.values()) {
            columns.add(col);
            columnByFieldName.put(col.fieldName(), col);
            declaredFieldNames.add(col.fieldName());
            maxHeadDepth = Math.max(maxHeadDepth, col.headerPath().size());
        }

        return new EntityColumnMetadata(
                maxHeadDepth,
                Collections.unmodifiableList(columns),
                Collections.unmodifiableMap(columnByFieldName),
                Collections.unmodifiableList(declaredFieldNames)
        );
    }

    private static List<Class<?>> collectClassHierarchy(Class<?> clazz) {
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            hierarchy.add(0, current);
            current = current.getSuperclass();
        }
        return hierarchy;
    }

    private static boolean hasIgnoreUnannotated(List<Class<?>> hierarchy) {
        for (Class<?> clz : hierarchy) {
            if (clz.isAnnotationPresent(ExcelIgnoreUnannotated.class)) {
                return true;
            }
        }
        return false;
    }

    private static List<Field> collectEligibleFields(List<Class<?>> hierarchy, boolean ignoreUnannotated) {
        List<Field> result = new ArrayList<>();
        for (Class<?> clz : hierarchy) {
            for (Field field : clz.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) {
                    continue;
                }
                if (field.isAnnotationPresent(ExcelIgnore.class)) {
                    continue;
                }
                if (ignoreUnannotated && !field.isAnnotationPresent(ExcelProperty.class)) {
                    continue;
                }
                result.add(field);
            }
        }
        return result;
    }

    private static ColumnMetadata buildColumnMetadata(int columnIndex, Field field) {
        ExcelProperty prop = field.getAnnotation(ExcelProperty.class);
        String[] path = (prop != null && prop.value() != null && prop.value().length > 0)
                ? prop.value()
                : new String[]{field.getName()};
        List<String> immutablePath = List.of(path);
        String leaf = immutablePath.get(immutablePath.size() - 1);
        return new ColumnMetadata(columnIndex, field, field.getName(), immutablePath, leaf);
    }

    /**
     * 单个实体字段对应的 Excel 列元数据
     *
     * @param columnIndex    0-based 物理列索引
     * @param field          反射字段对象
     * @param fieldName      Java 属性名称
     * @param headerPath     多级表头不可变路径列表
     * @param leafHeaderName 叶子级表头名称
     */
    public record ColumnMetadata(
            int columnIndex,
            Field field,
            String fieldName,
            List<String> headerPath,
            String leafHeaderName
    ) {
    }

    /**
     * 实体类聚合列元数据快照
     *
     * @param maxHeadDepth       最大表头层级深度
     * @param columns            按物理列序排列的只读列元数据列表
     * @param columnByFieldName  属性名到列元数据的只读映射
     * @param declaredFieldNames 按物理列序排列的只读属性名列表
     */
    public record EntityColumnMetadata(
            int maxHeadDepth,
            List<ColumnMetadata> columns,
            Map<String, ColumnMetadata> columnByFieldName,
            List<String> declaredFieldNames
    ) {
        /**
         * 根据 Java 属性名获取对应列元数据
         *
         * @param fieldName 属性名
         * @return 列元数据，未命中返回 null
         */
        public ColumnMetadata getColumnByFieldName(String fieldName) {
            return (fieldName != null) ? columnByFieldName.get(fieldName) : null;
        }
    }
}
