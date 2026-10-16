package io.github.atengk.boot.mybatisplus.permission;

import io.github.atengk.boot.mybatisplus.annotation.DataScope;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

/**
 * 细粒度数据权限 SPI 处理器契约
 * 业务系统实现此接口，根据当前安全上下文（如当前登录人所属部门、角色等）及 @DataScope 元数据，构造 JSqlParser AST 条件表达式
 *
 * @author Ateng
 * @since 2026-10-16
 */
@FunctionalInterface
public interface DataPermissionHandler {

    /**
     * 获取当前表的数据权限过滤条件表达式
     *
     * @param table              当前表 AST 对象（可获取表名、表别名等）
     * @param where              当前 WHERE 节点表达式（可能为 null）
     * @param mappedStatementId MyBatis MappedStatement 唯一标识（例如 io.github.atengk.boot.sample.mapper.UserMapper.selectList）
     * @param dataScope          当前生效的 @DataScope 注解元数据（已从 Mapper 接口或方法级别解析并缓存）
     * @return 过滤条件 Expression；返回 null 表示当前表不需要追加权限条件
     */
    Expression getSqlSegment(Table table, Expression where, String mappedStatementId, DataScope dataScope);
}
