package io.github.atengk.boot.mybatisplus.logicdelete;

import com.baomidou.mybatisplus.core.toolkit.PluginUtils;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 逻辑删除动态穿透与误删恢复 AST 拦截器
 * 当且仅当 LogicDeleteContextHolder 激活穿透闭包时，依托 JSqlParser AST 精准定位并剥离 WHERE 条件中的逻辑删除过滤表达式
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SuppressWarnings("deprecation")
public class AtengLogicDeleteInnerInterceptor implements InnerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AtengLogicDeleteInnerInterceptor.class);

    private static final Set<String> DEFAULT_COLUMNS = Set.of("deleted", "is_deleted", "del_flag");

    private final Set<String> targetColumns;

    public AtengLogicDeleteInnerInterceptor() {
        this(DEFAULT_COLUMNS);
    }

    public AtengLogicDeleteInnerInterceptor(Collection<String> columns) {
        if (columns == null || columns.isEmpty()) {
            this.targetColumns = DEFAULT_COLUMNS;
        } else {
            this.targetColumns = Collections.unmodifiableSet(
                    columns.stream().map(String::toLowerCase).collect(Collectors.toSet())
            );
        }
    }

    @Override
    public void beforeQuery(Executor executor, MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
        if (!LogicDeleteContextHolder.isWithDeleted()) {
            return;
        }
        PluginUtils.MPBoundSql mpBs = PluginUtils.mpBoundSql(boundSql);
        String originalSql = mpBs.sql();
        String transformedSql = stripLogicDeleteFromSql(originalSql, targetColumns);
        if (!originalSql.equals(transformedSql)) {
            mpBs.sql(transformedSql);
        }
    }

    @Override
    public void beforePrepare(StatementHandler sh, Connection connection, Integer transactionTimeout) {
        if (!LogicDeleteContextHolder.isWithDeleted()) {
            return;
        }
        PluginUtils.MPStatementHandler mpSh = PluginUtils.mpStatementHandler(sh);
        PluginUtils.MPBoundSql mpBs = mpSh.mPBoundSql();
        String originalSql = mpBs.sql();
        String transformedSql = stripLogicDeleteFromSql(originalSql, targetColumns);
        if (!originalSql.equals(transformedSql)) {
            mpBs.sql(transformedSql);
        }
    }

    /**
     * 基于 JSqlParser AST 递归剥离 SQL 语句中的逻辑未删除条件
     *
     * @param sql           原始 SQL
     * @param targetColumns 逻辑删除列名集合
     * @return 剥离后的 SQL
     */
    public static String stripLogicDeleteFromSql(String sql, Set<String> targetColumns) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            boolean modified = false;

            if (statement instanceof Select select) {
                PlainSelect plainSelect = select.getPlainSelect();
                if (plainSelect != null && plainSelect.getWhere() != null) {
                    Expression newWhere = removeLogicDelete(plainSelect.getWhere(), targetColumns);
                    plainSelect.setWhere(newWhere);
                    modified = true;
                }
            } else if (statement instanceof Update update) {
                if (update.getWhere() != null) {
                    Expression newWhere = removeLogicDelete(update.getWhere(), targetColumns);
                    update.setWhere(newWhere);
                    modified = true;
                }
            }

            return modified ? statement.toString() : sql;
        } catch (Exception ex) {
            log.warn("JSqlParser 解析或改写 SQL 逻辑删除条件失败，降级保持原 SQL: [{}]", ex.getMessage());
            return sql;
        }
    }

    /**
     * 从 WHERE 表达式树中递归移除逻辑未删除等值条件（形如 deleted = 0）
     *
     * @param expression    原始表达式
     * @param targetColumns 逻辑删除目标字段集合
     * @return 移除后的表达式，若被完全移除则返回 null
     */
    public static Expression removeLogicDelete(Expression expression, Set<String> targetColumns) {
        if (expression == null) {
            return null;
        }

        // 1. 如果当前单节点即为逻辑未删除等值条件，直接剔除（返回 null）
        if (isLogicNotDeleteEquals(expression, targetColumns)) {
            return null;
        }

        // 2. 如果是 AndExpression，分别处理左右子树
        if (expression instanceof AndExpression andExpr) {
            Expression left = removeLogicDelete(andExpr.getLeftExpression(), targetColumns);
            Expression right = removeLogicDelete(andExpr.getRightExpression(), targetColumns);

            if (left == null && right == null) {
                return null;
            }
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            andExpr.setLeftExpression(left);
            andExpr.setRightExpression(right);
            return andExpr;
        }

        // 3. 如果是 OrExpression，分别处理左右子树
        if (expression instanceof OrExpression orExpr) {
            Expression left = removeLogicDelete(orExpr.getLeftExpression(), targetColumns);
            Expression right = removeLogicDelete(orExpr.getRightExpression(), targetColumns);

            if (left == null && right == null) {
                return null;
            }
            if (left == null) {
                return right;
            }
            if (right == null) {
                return left;
            }
            orExpr.setLeftExpression(left);
            orExpr.setRightExpression(right);
            return orExpr;
        }

        // 4. 如果是括号表达式 Parenthesis
        if (expression instanceof Parenthesis parenthesis) {
            Expression inner = removeLogicDelete(parenthesis.getExpression(), targetColumns);
            if (inner == null) {
                return null;
            }
            parenthesis.setExpression(inner);
            return parenthesis;
        }

        return expression;
    }

    private static boolean isLogicNotDeleteEquals(Expression expression, Set<String> targetColumns) {
        if (expression instanceof EqualsTo equalsTo) {
            Expression left = equalsTo.getLeftExpression();
            Expression right = equalsTo.getRightExpression();
            if (isTargetColumn(left, targetColumns) && isNotDeleteValue(right)) {
                return true;
            }
            if (isTargetColumn(right, targetColumns) && isNotDeleteValue(left)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTargetColumn(Expression expr, Set<String> targetColumns) {
        if (expr instanceof Column col) {
            String columnName = col.getColumnName();
            if (columnName != null) {
                String cleanName = columnName.replace("`", "").replace("\"", "").replace("[", "").replace("]", "").trim();
                return targetColumns.contains(cleanName.toLowerCase());
            }
        }
        return false;
    }

    private static boolean isNotDeleteValue(Expression expr) {
        if (expr instanceof LongValue lv) {
            return lv.getValue() == 0;
        }
        if (expr instanceof StringValue sv) {
            return "0".equals(sv.getValue()) || "false".equalsIgnoreCase(sv.getValue());
        }
        String text = expr.toString().replace("'", "").replace("\"", "").trim();
        return "0".equals(text) || "false".equalsIgnoreCase(text);
    }
}
