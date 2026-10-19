package io.github.atengk.boot.mybatisplus.injector.methods;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 原生单条 CASE WHEN 多值批量更新 SQL 注入器节点
 * <p>
 * 编译生成标准高效的单条 CASE WHEN 批量更新 SQL 语法树：
 * <pre>{@code
 * UPDATE table_name
 * SET
 *   col1 = CASE id WHEN ? THEN ? ELSE col1 END,
 *   col2 = CASE id WHEN ? THEN ? ELSE col2 END
 * WHERE id IN (?, ?) [AND logic_delete = 0]
 * }</pre>
 * 具备以下企业级工程特性：
 * <ul>
 *   <li><b>多方言 ANSI SQL 兼容</b>：基于标准 {@code CASE WHEN ... ELSE ... END} 语法，100% 兼容 MySQL、PostgreSQL 与 H2；</li>
 *   <li><b>安全字段过滤</b>：自动排除主键列、逻辑删除标记列（{@code isLogicDelete()}）以及仅插入填充审计列（{@code FieldFill.INSERT}，如 {@code create_time}/{@code create_by}）；</li>
 *   <li><b>动态选择性更新</b>：每列内嵌 {@code <if test="item.property != null">}，仅针对非 null 属性生成 WHEN 判定，为 null 时自动回退 {@code ELSE col} 保护既有值；</li>
 *   <li><b>并发防死锁铁律（Anti-Deadlock Invariant）</b>：在 {@link SqlSource#getBoundSql(Object)} 触发前，强制在内存中对入参实体列表按照主键 ID 升序重排，从源头杜绝并发跨事务行级锁死锁。</li>
 * </ul>
 * </p>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@Accessors(chain = true)
public class UpdateBatchSomeColumn extends AbstractMethod {

    /**
     * 默认方法名
     */
    public static final String METHOD_NAME = "updateBatchSomeColumn";

    /**
     * 字段筛选条件（默认排除逻辑删除字段）
     */
    private Predicate<TableFieldInfo> predicate;

    public UpdateBatchSomeColumn() {
        this(field -> !field.isLogicDelete());
    }

    public UpdateBatchSomeColumn(Predicate<TableFieldInfo> predicate) {
        this(METHOD_NAME, predicate);
    }

    public UpdateBatchSomeColumn(String name, Predicate<TableFieldInfo> predicate) {
        super(name);
        this.predicate = predicate != null ? predicate : (field -> !field.isLogicDelete());
    }

    @Override
    public MappedStatement injectMappedStatement(Class<?> mapperClass, Class<?> modelClass, TableInfo tableInfo) {
        if (!tableInfo.havePK()) {
            throw new IllegalStateException("实体 " + modelClass.getName() + " 未配置主键，无法执行基于主键的批量更新 updateBatchSomeColumn");
        }

        String pkColumn = tableInfo.getKeyColumn();
        String pkProperty = tableInfo.getKeyProperty();
        List<TableFieldInfo> fieldList = tableInfo.getFieldList();

        // 1. 过滤可更新字段：排除主键、逻辑删除、以及仅插入填充字段
        List<TableFieldInfo> updateFields = fieldList.stream()
                .filter(f -> predicate.test(f)
                        && !f.isLogicDelete()
                        && f.getFieldFill() != FieldFill.INSERT
                        && !isCreateOnlyField(f)
                        && (pkColumn == null || !f.getColumn().equalsIgnoreCase(pkColumn)))
                .toList();

        if (updateFields.isEmpty()) {
            throw new IllegalStateException("实体 " + modelClass.getName() + " 未包含任何可更新的有效字段");
        }

        // 2. 构建 CASE WHEN SET 语法树片段
        StringBuilder setScript = new StringBuilder("<trim prefix=\"SET\" suffixOverrides=\",\">\n");
        for (TableFieldInfo field : updateFields) {
            String column = field.getColumn();
            String property = field.getProperty();
            String el = field.getEl();
            setScript.append("  <trim prefix=\"").append(column).append(" = CASE ").append(pkColumn).append(" \" suffix=\"ELSE ").append(column).append(" END,\">\n");
            setScript.append("    <foreach collection=\"list\" item=\"item\">\n");
            setScript.append("      <if test=\"item.").append(property).append(" != null\">\n");
            setScript.append("        WHEN ").append(SqlScriptUtils.safeParam("item." + pkProperty)).append(" THEN ").append(SqlScriptUtils.safeParam("item." + el)).append("\n");
            setScript.append("      </if>\n");
            setScript.append("    </foreach>\n");
            setScript.append("  </trim>\n");
        }
        setScript.append("</trim>\n");

        // 3. 构建 WHERE pk IN (...) 条件片段（若有逻辑删除则追加逻辑未删除过滤）
        String whereInScript = "WHERE " + pkColumn + " IN\n"
                + "<foreach collection=\"list\" item=\"item\" open=\"(\" separator=\",\" close=\")\">\n"
                + "  " + SqlScriptUtils.safeParam("item." + pkProperty) + "\n"
                + "</foreach>\n";
        if (tableInfo.isWithLogicDelete()) {
            whereInScript += tableInfo.getLogicDeleteSql(true, true) + "\n";
        }

        // 4. 拼装完整脚本并注入挂载防死锁排序 SqlSource
        String sql = "<script>\nUPDATE " + tableInfo.getTableName() + "\n"
                + setScript
                + whereInScript
                + "</script>";

        SqlSource originalSqlSource = super.createSqlSource(configuration, sql, modelClass);
        SqlSource antiDeadlockSqlSource = new AntiDeadlockSqlSource(originalSqlSource, tableInfo);

        return this.addUpdateMappedStatement(mapperClass, modelClass, methodName, antiDeadlockSqlSource);
    }

    /**
     * 判断是否为仅插入填充字段（如 create_time / create_by），批量更新时不应覆盖
     */
    private boolean isCreateOnlyField(TableFieldInfo field) {
        if (field.getFieldFill() == FieldFill.INSERT) {
            return true;
        }
        String prop = field.getProperty();
        return "createTime".equalsIgnoreCase(prop) || "createBy".equalsIgnoreCase(prop) || "gmtCreate".equalsIgnoreCase(prop);
    }

    /**
     * 挂载防死锁主键升序排序的 SqlSource 包装器
     */
    public static class AntiDeadlockSqlSource implements SqlSource {
        private final SqlSource delegate;
        private final TableInfo tableInfo;

        public AntiDeadlockSqlSource(SqlSource delegate, TableInfo tableInfo) {
            this.delegate = delegate;
            this.tableInfo = tableInfo;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            sortListIfPresent(parameterObject, tableInfo);
            return delegate.getBoundSql(parameterObject);
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        public static void sortListIfPresent(Object parameterObject, TableInfo tableInfo) {
            if (parameterObject instanceof Map map) {
                Object rawList = map.get("list");
                if (rawList instanceof Collection<?> col && col.size() > 1) {
                    List sortedList = new ArrayList(col);
                    sortedList.sort((a, b) -> {
                        Comparable idA = extractId(a, tableInfo);
                        Comparable idB = extractId(b, tableInfo);
                        if (idA == null && idB == null) return 0;
                        if (idA == null) return -1;
                        if (idB == null) return 1;
                        try {
                            return idA.compareTo(idB);
                        } catch (ClassCastException ignored) {
                            return idA.toString().compareTo(idB.toString());
                        }
                    });
                    map.put("list", sortedList);
                    if (map.containsKey("collection")) {
                        map.put("collection", sortedList);
                    }
                    if (map.containsKey("param1")) {
                        map.put("param1", sortedList);
                    }
                }
            }
        }

        private static Comparable<?> extractId(Object entity, TableInfo tableInfo) {
            if (entity == null || tableInfo == null || !tableInfo.havePK()) {
                return null;
            }
            try {
                Object val = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
                if (val instanceof Comparable<?> c) {
                    return c;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
