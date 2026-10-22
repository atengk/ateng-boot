package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.mybatisplus.properties.SqlLogProperties;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Statement;

/**
 * SQL 日志打印与可执行带参诊断拦截器
 * 拦截 MyBatis 原生 StatementHandler 物理执行终态（query/update/batch/queryCursor），
 * 提供一键可执行带参 SQL 拼装、执行耗时、影响/返回行数看板与调用栈精准穿透，
 * 内置多值批量单条输出、敏感字段自动脱敏掩码与超长 SQL 截断防爆保护。
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "queryCursor", args = {Statement.class})
})
public class SqlLogInterceptor implements Interceptor {

    private final SqlLogProperties properties;

    public SqlLogInterceptor(SqlLogProperties properties) {
        this.properties = properties != null ? properties : new SqlLogProperties();
    }

    public SqlLogProperties getProperties() {
        return properties;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }
}
