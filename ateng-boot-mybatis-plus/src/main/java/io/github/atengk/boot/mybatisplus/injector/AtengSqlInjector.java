package io.github.atengk.boot.mybatisplus.injector;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.injector.AbstractMethod;
import com.baomidou.mybatisplus.core.injector.DefaultSqlInjector;
import com.baomidou.mybatisplus.core.mapper.Mapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.core.toolkit.ReflectionKit;
import com.baomidou.mybatisplus.extension.injector.methods.InsertBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysically;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysicallyBatchByIds;
import io.github.atengk.boot.mybatisplus.injector.methods.DeletePhysicallyById;
import io.github.atengk.boot.mybatisplus.injector.methods.SelectCursor;
import io.github.atengk.boot.mybatisplus.injector.methods.UpdateBatchSomeColumn;
import io.github.atengk.boot.mybatisplus.injector.methods.UpsertBatchSomeColumn;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增强型全局 SQL 注入器
 * 继承 MyBatis-Plus DefaultSqlInjector，默认挂载 InsertBatchSomeColumn、UpsertBatchSomeColumn、UpdateBatchSomeColumn 与 SelectCursor 原生方法，
 * 自动排除标注 @TableLogic 的逻辑删除字段与仅插入填充字段，支持 MySQL / PostgreSQL 双方言原生 Upsert 语法、单条 CASE WHEN 防死锁批量更新与受管游标流式查询
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengSqlInjector extends DefaultSqlInjector {

    private static final Set<String> LOGIC_DELETE_METHOD_NAMES = Set.of(
            "delete", "deleteByMap", "deleteById", "deleteByIds", "deleteBatchIds"
    );

    private static final Pattern WHERE_BOUNDARY_PATTERN = Pattern.compile("(?i)\\s+WHERE\\s+");

    private final DbType dbType;

    public AtengSqlInjector() {
        this(null);
    }

    public AtengSqlInjector(DbType dbType) {
        this.dbType = dbType;
    }

    @Override
    public List<AbstractMethod> getMethodList(Configuration configuration, Class<?> mapperClass, TableInfo tableInfo) {
        List<AbstractMethod> methodList = super.getMethodList(configuration, mapperClass, tableInfo);
        // 1. 挂载原生多值批量插入方法：INSERT INTO table (col1, col2) VALUES (...), (...)
        // 传入 Predicate 过滤逻辑删除字段
        methodList.add(new InsertBatchSomeColumn(field -> !field.isLogicDelete()));
        // 2. 挂载原生双方言多值批量 Upsert 方法：INSERT INTO table (...) VALUES (...) ON DUPLICATE KEY / ON CONFLICT
        methodList.add(new UpsertBatchSomeColumn(field -> !field.isLogicDelete(), dbType));
        // 3. 挂载原生单条多值 CASE WHEN 批量更新方法：UPDATE table SET col1 = CASE id WHEN ... ELSE col1 END WHERE id IN (...)
        methodList.add(new UpdateBatchSomeColumn(field -> !field.isLogicDelete()));
        // 4. 挂载原生受管游标查询方法：SELECT ... FROM table <where> ... (fetchSize = 1000)
        methodList.add(new SelectCursor());
        // 5. 挂载原生物理硬删除方法（按主键）：DELETE FROM table WHERE id = #{id}
        methodList.add(new DeletePhysicallyById());
        // 6. 挂载原生物理硬删除方法（按主键集合）：DELETE FROM table WHERE id IN (...)
        methodList.add(new DeletePhysicallyBatchByIds());
        // 7. 挂载原生物理硬删除方法（按 Wrapper 条件）：DELETE FROM table <where> ...
        methodList.add(new DeletePhysically());
        return methodList;
    }

    @Override
    public void inspectInject(MapperBuilderAssistant builderAssistant, Class<?> mapperClass) {
        super.inspectInject(builderAssistant, mapperClass);
        rewriteCharSequenceLogicDeleteStatements(builderAssistant, mapperClass);
    }

    /**
     * 修正字符串/字符序列类型逻辑删除字段在 {@code delval = "id"} 占位语义下被 MyBatis-Plus 误加单引号的问题
     *
     * @param builderAssistant Mapper 构建助手
     * @param mapperClass      当前 Mapper 接口类
     */
    private void rewriteCharSequenceLogicDeleteStatements(MapperBuilderAssistant builderAssistant, Class<?> mapperClass) {
        if (builderAssistant == null || mapperClass == null) {
            return;
        }
        Class<?> modelClass = ReflectionKit.getSuperClassGenericType(mapperClass, Mapper.class, 0);
        if (modelClass == null) {
            return;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(modelClass);
        if (tableInfo == null || !tableInfo.isWithLogicDelete() || !tableInfo.havePK()) {
            return;
        }
        TableFieldInfo logicField = tableInfo.getLogicDeleteFieldInfo();
        if (logicField == null || !logicField.isCharSequence()) {
            return;
        }
        String logicDeleteValue = logicField.getLogicDeleteValue();
        if (logicDeleteValue == null || logicDeleteValue.isBlank()) {
            return;
        }
        String keyColumn = tableInfo.getKeyColumn();
        String keyProperty = tableInfo.getKeyProperty();
        boolean isIdPlaceholder = "id".equalsIgnoreCase(logicDeleteValue)
                || logicDeleteValue.equals(keyColumn)
                || logicDeleteValue.equals(keyProperty);
        if (!isIdPlaceholder || keyColumn == null || keyColumn.isBlank()) {
            return;
        }

        Configuration configuration = builderAssistant.getConfiguration();
        String namespace = mapperClass.getName();
        String logicColumn = logicField.getColumn();
        Pattern setClausePattern = Pattern.compile(
                "(?<=^|[,\\s])" + Pattern.quote(logicColumn) + "\\s*=\\s*'" + Pattern.quote(logicDeleteValue) + "'"
        );
        String replacement = Matcher.quoteReplacement(logicColumn + "=" + keyColumn);

        for (String methodName : LOGIC_DELETE_METHOD_NAMES) {
            String statementId = namespace + "." + methodName;
            if (!configuration.hasStatement(statementId, false)) {
                continue;
            }
            MappedStatement ms = configuration.getMappedStatement(statementId, false);
            if (ms == null || ms.getSqlCommandType() != SqlCommandType.UPDATE) {
                continue;
            }
            SqlSource originalSqlSource = ms.getSqlSource();
            if (originalSqlSource != null && !(originalSqlSource instanceof LogicDeleteColumnPlaceholderSqlSource)) {
                SystemMetaObject.forObject(ms).setValue(
                        "sqlSource",
                        new LogicDeleteColumnPlaceholderSqlSource(originalSqlSource, setClausePattern, replacement)
                );
            }
        }
    }

    /**
     * 拦截 {@code SET} 子句并将被引号包裹的主键占位符还原为裸主键列名赋值
     */
    private static final class LogicDeleteColumnPlaceholderSqlSource implements SqlSource {

        private final SqlSource delegate;
        private final Pattern setClausePattern;
        private final String replacement;

        private LogicDeleteColumnPlaceholderSqlSource(SqlSource delegate, Pattern setClausePattern, String replacement) {
            this.delegate = delegate;
            this.setClausePattern = setClausePattern;
            this.replacement = replacement;
        }

        @Override
        public BoundSql getBoundSql(Object parameterObject) {
            BoundSql boundSql = delegate.getBoundSql(parameterObject);
            String originalSql = boundSql.getSql();
            if (originalSql == null || originalSql.isBlank()) {
                return boundSql;
            }
            String rewritten = rewriteSetClauseOnly(originalSql);
            if (!originalSql.equals(rewritten)) {
                PluginUtils.mpBoundSql(boundSql).sql(rewritten);
            }
            return boundSql;
        }

        private String rewriteSetClauseOnly(String sql) {
            Matcher whereMatcher = WHERE_BOUNDARY_PATTERN.matcher(sql);
            int whereIdx = whereMatcher.find() ? whereMatcher.start() : -1;
            String setPart = whereIdx >= 0 ? sql.substring(0, whereIdx) : sql;
            String wherePart = whereIdx >= 0 ? sql.substring(whereIdx) : "";

            String rewrittenSet = setClausePattern.matcher(setPart).replaceAll(replacement);
            if (rewrittenSet.equals(setPart)) {
                return sql;
            }
            return rewrittenSet + wherePart;
        }
    }

    /**
     * 判断字段是否为仅插入填充字段（如 create_time / create_by 或 FieldFill.INSERT）
     * 批量更新或冲突更新时不应覆盖此类审计字段
     *
     * @param field MyBatis-Plus 字段元数据信息
     * @return true 若为仅插入字段；否则 false
     */
    public static boolean isCreateOnlyField(com.baomidou.mybatisplus.core.metadata.TableFieldInfo field) {
        if (field == null) {
            return false;
        }
        if (field.getFieldFill() == com.baomidou.mybatisplus.annotation.FieldFill.INSERT) {
            return true;
        }
        String prop = field.getProperty();
        return "createTime".equalsIgnoreCase(prop) || "createBy".equalsIgnoreCase(prop) || "gmtCreate".equalsIgnoreCase(prop);
    }
}
