package io.github.atengk.boot.mybatisplus.injector.methods;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlInjectionUtils;
import com.baomidou.mybatisplus.core.toolkit.sql.SqlScriptUtils;
import com.baomidou.mybatisplus.extension.toolkit.JdbcUtils;
import io.github.atengk.boot.mybatisplus.annotation.ConflictKeys;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 原生双方言单条多值批量插入或原地冲突更新（Upsert）SQL 注入器节点
 * <p>
 * 自适应识别数据源数据库方言：
 * <ul>
 *   <li><b>MySQL 方言</b>：编译生成 {@code INSERT INTO table (...) VALUES (...), (...) ON DUPLICATE KEY UPDATE col1=VALUES(col1), ...}</li>
 *   <li><b>PostgreSQL 方言</b>：编译生成 {@code INSERT INTO table (...) VALUES (...), (...) ON CONFLICT (conflict_cols) DO UPDATE SET col1=EXCLUDED.col1, ...}</li>
 * </ul>
 * 自动排除主键列、逻辑删除标记字段（{@code field.isLogicDelete()}）以及仅插入填充字段（{@code FieldFill.INSERT}，如创建时间、创建人）。
 * PostgreSQL 模式下默认使用 {@code @TableId} 主键作为冲突键，支持在实体类标注 {@link ConflictKeys} 自定义复合唯一索引冲突键。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@Accessors(chain = true)
public class UpsertBatchSomeColumn extends AbstractMethod {

    /**
     * 默认方法名
     */
    public static final String METHOD_NAME = "upsertBatchSomeColumn";

    /**
     * 字段筛选条件（默认排除逻辑删除字段）
     */
    private Predicate<TableFieldInfo> predicate;

    /**
     * 显式指定的目标数据库方言（为空时由注入器根据 Configuration / DataSource 自动探测）
     */
    private DbType dbType;

    public UpsertBatchSomeColumn() {
        this(field -> !field.isLogicDelete(), null);
    }

    public UpsertBatchSomeColumn(Predicate<TableFieldInfo> predicate) {
        this(predicate, null);
    }

    public UpsertBatchSomeColumn(DbType dbType) {
        this(field -> !field.isLogicDelete(), dbType);
    }

    public UpsertBatchSomeColumn(Predicate<TableFieldInfo> predicate, DbType dbType) {
        this(METHOD_NAME, predicate, dbType);
    }

    public UpsertBatchSomeColumn(String name, Predicate<TableFieldInfo> predicate, DbType dbType) {
        super(name);
        this.predicate = predicate != null ? predicate : (field -> !field.isLogicDelete());
        this.dbType = dbType;
    }

    @Override
    public MappedStatement injectMappedStatement(Class<?> mapperClass, Class<?> modelClass, TableInfo tableInfo) {
        KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
        List<TableFieldInfo> fieldList = tableInfo.getFieldList();

        // 1. 构建 INSERT 目标列 (col1, col2, ...)
        String insertSqlColumn = tableInfo.getKeyInsertSqlColumn(true, null, false)
                + this.filterTableFieldInfo(fieldList, predicate, TableFieldInfo::getInsertSqlColumn, EMPTY);
        String columnScript = LEFT_BRACKET + insertSqlColumn.substring(0, insertSqlColumn.length() - 1) + RIGHT_BRACKET;

        // 2. 构建 VALUES 单项模板 (#{item.col1}, #{item.col2}, ...)
        String insertSqlProperty = tableInfo.getKeyInsertSqlProperty(true, ENTITY_DOT, false)
                + this.filterTableFieldInfo(fieldList, predicate, i -> i.getInsertSqlProperty(ENTITY_DOT), EMPTY);
        insertSqlProperty = LEFT_BRACKET + insertSqlProperty.substring(0, insertSqlProperty.length() - 1) + RIGHT_BRACKET;

        // 3. 构建 VALUES 批量循环脚本
        String valuesScript = SqlScriptUtils.convertForeach(insertSqlProperty, "list", null, ENTITY, COMMA);

        String keyProperty = null;
        String keyColumn = null;
        if (tableInfo.havePK()) {
            if (tableInfo.getIdType() == IdType.AUTO) {
                keyGenerator = Jdbc3KeyGenerator.INSTANCE;
                keyProperty = tableInfo.getKeyProperty();
                keyColumn = SqlInjectionUtils.removeEscapeCharacter(tableInfo.getKeyColumn());
            } else if (tableInfo.getKeySequence() != null) {
                keyGenerator = TableInfoHelper.genKeyGenerator(this.methodName, tableInfo, builderAssistant);
                keyProperty = tableInfo.getKeyProperty();
                keyColumn = tableInfo.getKeyColumn();
            }
        }

        // 4. 解析方言并构建 ON DUPLICATE KEY UPDATE / ON CONFLICT 子句
        DbType resolvedDbType = resolveDbType(configuration);
        String duplicateKeySql = buildDuplicateKeyClause(resolvedDbType, modelClass, tableInfo, fieldList);

        // 5. 拼装完整 XML Script 并注册 MappedStatement
        String sql = "<script>\nINSERT INTO " + tableInfo.getTableName() + " " + columnScript
                + " VALUES " + valuesScript + "\n" + duplicateKeySql + "\n</script>";
        SqlSource sqlSource = super.createSqlSource(configuration, sql, modelClass);
        return this.addInsertMappedStatement(mapperClass, modelClass, methodName, sqlSource, keyGenerator, keyProperty, keyColumn);
    }

    /**
     * 构建冲突更新 SQL 子句（区分 PostgreSQL 与 MySQL）
     *
     * @param targetDbType 数据库类型
     * @param modelClass   实体模型 Class
     * @param tableInfo    数据表反射元数据
     * @param fieldList    字段列表
     * @return 冲突子句 SQL 字符串
     */
    protected String buildDuplicateKeyClause(DbType targetDbType, Class<?> modelClass, TableInfo tableInfo, List<TableFieldInfo> fieldList) {
        if (targetDbType == DbType.POSTGRE_SQL) {
            return buildPostgreSqlClause(modelClass, tableInfo, fieldList);
        } else {
            return buildMySqlClause(tableInfo, fieldList);
        }
    }

    /**
     * 构建 MySQL ON DUPLICATE KEY UPDATE 子句
     */
    private String buildMySqlClause(TableInfo tableInfo, List<TableFieldInfo> fieldList) {
        String pkColumn = tableInfo.getKeyColumn();
        String normalizedPk = pkColumn != null ? pkColumn.replace("_", "").toLowerCase() : null;

        List<TableFieldInfo> updateFields = fieldList.stream()
                .filter(f -> predicate.test(f)
                        && !f.isLogicDelete()
                        && !isCreateOnlyField(f)
                        && (normalizedPk == null || !f.getColumn().replace("_", "").equalsIgnoreCase(normalizedPk)))
                .toList();

        if (updateFields.isEmpty()) {
            return "ON DUPLICATE KEY UPDATE " + (pkColumn != null ? pkColumn + " = " + pkColumn : "1 = 1");
        }

        String setClause = updateFields.stream()
                .map(f -> f.getColumn() + " = VALUES(" + f.getColumn() + ")")
                .collect(Collectors.joining(", "));
        return "ON DUPLICATE KEY UPDATE " + setClause;
    }

    /**
     * 构建 PostgreSQL ON CONFLICT (conflict_cols) DO UPDATE SET 子句
     */
    private String buildPostgreSqlClause(Class<?> modelClass, TableInfo tableInfo, List<TableFieldInfo> fieldList) {
        List<String> conflictColumns = resolveConflictColumns(modelClass, tableInfo, fieldList);
        String conflictTargets = String.join(", ", conflictColumns);

        Set<String> conflictColSet = new HashSet<>();
        for (String col : conflictColumns) {
            conflictColSet.add(col.replace("_", "").toLowerCase());
        }

        String pkColumn = tableInfo.getKeyColumn();
        String normalizedPk = pkColumn != null ? pkColumn.replace("_", "").toLowerCase() : null;

        List<TableFieldInfo> updateFields = fieldList.stream()
                .filter(f -> predicate.test(f)
                        && !f.isLogicDelete()
                        && !isCreateOnlyField(f)
                        && (normalizedPk == null || !f.getColumn().replace("_", "").equalsIgnoreCase(normalizedPk))
                        && !conflictColSet.contains(f.getColumn().replace("_", "").toLowerCase()))
                .toList();

        if (updateFields.isEmpty()) {
            return "ON CONFLICT (" + conflictTargets + ") DO NOTHING";
        }

        String setClause = updateFields.stream()
                .map(f -> f.getColumn() + " = EXCLUDED." + f.getColumn())
                .collect(Collectors.joining(", "));
        return "ON CONFLICT (" + conflictTargets + ") DO UPDATE SET " + setClause;
    }

    /**
     * 解析 PostgreSQL 冲突列名（优先取 @ConflictKeys 注解，缺省回退至主键列）
     */
    private List<String> resolveConflictColumns(Class<?> modelClass, TableInfo tableInfo, List<TableFieldInfo> fieldList) {
        ConflictKeys conflictKeys = modelClass.getAnnotation(ConflictKeys.class);
        List<String> columns = new ArrayList<>();
        if (conflictKeys != null && conflictKeys.value().length > 0) {
            for (String rawKey : conflictKeys.value()) {
                if (rawKey == null || rawKey.isBlank()) {
                    continue;
                }
                String key = rawKey.trim();
                // 1. 优先匹配主键
                if (tableInfo.havePK() && isNameMatch(key, tableInfo.getKeyProperty(), tableInfo.getKeyColumn())) {
                    columns.add(tableInfo.getKeyColumn());
                    continue;
                }
                // 2. 匹配普通字段
                boolean matched = false;
                for (TableFieldInfo field : fieldList) {
                    if (isNameMatch(key, field.getProperty(), field.getColumn())) {
                        columns.add(field.getColumn());
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    columns.add(key);
                }
            }
        }

        if (columns.isEmpty()) {
            if (tableInfo.havePK()) {
                columns.add(tableInfo.getKeyColumn());
            } else {
                throw new IllegalStateException("实体 " + modelClass.getName() + " 未配置主键且未标注 @ConflictKeys，无法在 PostgreSQL 下生成 ON CONFLICT 子句");
            }
        }
        return columns;
    }

    /**
     * 归一化比对属性名或列名（自适应驼峰与下划线，如 user_id 与 userId）
     */
    private boolean isNameMatch(String target, String property, String column) {
        if (target.equalsIgnoreCase(property) || target.equalsIgnoreCase(column)) {
            return true;
        }
        String normalizedTarget = target.replace("_", "").toLowerCase();
        if (property != null && normalizedTarget.equals(property.replace("_", "").toLowerCase())) {
            return true;
        }
        if (column != null && normalizedTarget.equals(column.replace("_", "").toLowerCase())) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为仅插入填充字段（如 create_time / create_by），冲突更新时不应覆盖
     */
    private boolean isCreateOnlyField(TableFieldInfo field) {
        if (field.getFieldFill() == FieldFill.INSERT) {
            return true;
        }
        String prop = field.getProperty();
        return "createTime".equalsIgnoreCase(prop) || "createBy".equalsIgnoreCase(prop) || "gmtCreate".equalsIgnoreCase(prop);
    }

    /**
     * 智能解析当前目标数据库方言
     *
     * @param configuration MyBatis 配置实例
     * @return 解析后的 DbType
     */
    public DbType resolveDbType(Configuration configuration) {
        if (this.dbType != null) {
            return this.dbType;
        }

        if (configuration != null) {
            // 1. configuration.getDatabaseId()
            String databaseId = configuration.getDatabaseId();
            if (databaseId != null && !databaseId.isBlank()) {
                String id = databaseId.toLowerCase();
                if (id.contains("mysql") || id.contains("mariadb")) {
                    return DbType.MYSQL;
                }
                if (id.contains("postgre") || id.contains("pg")) {
                    return DbType.POSTGRE_SQL;
                }
            }

            // 2. DataSource connection URL / metadata / H2 settings
            if (configuration.getEnvironment() != null && configuration.getEnvironment().getDataSource() != null) {
                try (Connection conn = configuration.getEnvironment().getDataSource().getConnection()) {
                    String url = conn.getMetaData().getURL();
                    String prodName = conn.getMetaData().getDatabaseProductName();
                    if (url != null) {
                        String lowerUrl = url.toLowerCase();
                        if (lowerUrl.contains("mode=postgresql") || lowerUrl.contains("mode=postgres")) {
                            return DbType.POSTGRE_SQL;
                        }
                        if (lowerUrl.contains("mode=mysql") || lowerUrl.contains("mode=mariadb")) {
                            return DbType.MYSQL;
                        }
                        DbType detected = JdbcUtils.getDbType(url);
                        if (detected == DbType.POSTGRE_SQL) {
                            return DbType.POSTGRE_SQL;
                        }
                        if (detected == DbType.MYSQL || detected == DbType.MARIADB) {
                            return DbType.MYSQL;
                        }
                    }

                    if (prodName != null) {
                        String lowerProd = prodName.toLowerCase();
                        if (lowerProd.contains("postgre")) {
                            return DbType.POSTGRE_SQL;
                        }
                        if (lowerProd.contains("mysql") || lowerProd.contains("mariadb")) {
                            return DbType.MYSQL;
                        }
                        // 特殊针对 H2 内存库探查方言模式 (MODE)
                        if (lowerProd.contains("h2")) {
                            try (Statement stmt = conn.createStatement();
                                 ResultSet rs = stmt.executeQuery("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'")) {
                                if (rs.next()) {
                                    String mode = rs.getString(1);
                                    if (mode != null) {
                                        if (mode.equalsIgnoreCase("PostgreSQL")) {
                                            return DbType.POSTGRE_SQL;
                                        }
                                        if (mode.equalsIgnoreCase("MySQL") || mode.equalsIgnoreCase("MariaDB")) {
                                            return DbType.MYSQL;
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 容错降级
                }
            }
        }

        // 3. 兜底默认 MySQL
        return DbType.MYSQL;
    }
}
