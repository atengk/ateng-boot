package io.github.atengk.boot.sample.mybatisplus.permission;

import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 细粒度数据权限 SPI 处理器实现
 * 基于 JSqlParser AST，根据当前安全上下文动态构造部门与本人数据权限过滤表达式
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Component
public class SampleDataPermissionHandler implements DataPermissionHandler {

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope) {
        List<Long> deptIds = SampleSecurityContext.getAccessibleDeptIds();
        String selfOperator = SampleSecurityContext.getSelfOperator();
        boolean hasDepts = deptIds != null && !deptIds.isEmpty();
        boolean hasSelf = selfOperator != null && !selfOperator.isBlank()
                && dataScope.userColumn() != null && !dataScope.userColumn().isBlank();
        if (!hasDepts && !hasSelf) {
            return null;
        }
        String prefix = (dataScope.alias() != null && !dataScope.alias().isBlank())
                ? dataScope.alias() + "."
                : "";

        Expression deptExpression = null;
        if (hasDepts) {
            ParenthesedExpressionList<LongValue> list = new ParenthesedExpressionList<>();
            for (Long id : deptIds) {
                list.add(new LongValue(id));
            }
            InExpression inExpression = new InExpression();
            inExpression.setLeftExpression(new Column(prefix + dataScope.deptColumn()));
            inExpression.setRightExpression(list);
            deptExpression = inExpression;
        }

        Expression selfExpression = null;
        if (hasSelf) {
            EqualsTo equalsTo = new EqualsTo();
            equalsTo.setLeftExpression(new Column(prefix + dataScope.userColumn()));
            equalsTo.setRightExpression(new StringValue(selfOperator));
            selfExpression = equalsTo;
        }

        if (deptExpression != null && selfExpression != null) {
            return new ParenthesedExpressionList<>(new OrExpression(deptExpression, selfExpression));
        }
        return deptExpression != null ? deptExpression : selfExpression;
    }
}
