package io.github.atengk.boot.mybatisplus.permission;

import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

/**
 * 默认数据权限处理器实现（兜底透传，不追加任何过滤条件）
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class DefaultDataPermissionHandler implements DataPermissionHandler {

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope) {
        return null;
    }
}
