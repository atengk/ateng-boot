package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.mybatisplus.properties.SqlLogProperties;
import io.github.atengk.boot.mybatisplus.util.MybatisInterceptorUtil;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Statement;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    private static final Logger log = LoggerFactory.getLogger(SqlLogInterceptor.class);

    private final SqlLogProperties properties;
    private final Consumer<String> logConsumer;

    public SqlLogInterceptor(SqlLogProperties properties) {
        this(properties, null);
    }

    public SqlLogInterceptor(SqlLogProperties properties, Consumer<String> logConsumer) {
        this.properties = properties != null ? properties : new SqlLogProperties();
        this.logConsumer = logConsumer;
    }

    public SqlLogProperties getProperties() {
        return properties;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!properties.isEnabled()) {
            return invocation.proceed();
        }

        Object target = MybatisInterceptorUtil.realTarget(invocation.getTarget());
        if (!(target instanceof StatementHandler statementHandler)) {
            return invocation.proceed();
        }

        String methodName = invocation.getMethod().getName();
        // 抑制传统 saveBatch / addBatch 逐条重复打印，杜绝控制台刷屏卡死
        if ("batch".equals(methodName)) {
            return invocation.proceed();
        }

        long startNanos = System.nanoTime();
        Object result = null;
        try {
            result = invocation.proceed();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            processSuccess(statementHandler, methodName, result, elapsedMillis);
            return result;
        } catch (Throwable t) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            processFailure(statementHandler, t, elapsedMillis);
            throw t;
        }
    }

    private void processSuccess(StatementHandler statementHandler, String methodName, Object result, long elapsedMillis) {
        try {
            MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
            MappedStatement mappedStatement = resolveMappedStatement(metaObject);
            String statementId = (mappedStatement != null) ? mappedStatement.getId() : "UNKNOWN";
            Configuration configuration = (mappedStatement != null) ? mappedStatement.getConfiguration() : null;
            BoundSql boundSql = statementHandler.getBoundSql();

            String sql = formatAndTruncateSql(boundSql, configuration);
            String rowDesc = resolveRowDescription(methodName, result);
            String callLocation = properties.isShowCallLocation() ? MybatisInterceptorUtil.resolveCallLocation() : null;

            StringBuilder sb = new StringBuilder(sql.length() + 256);
            sb.append("\n====== [MyBatis-Plus SQL 执行诊断] ======\n");
            sb.append("MappedStatement : ").append(statementId).append("\n");
            sb.append("实际执行耗时   : ").append(elapsedMillis).append(" ms\n");
            sb.append("执行结果影响   : ").append(rowDesc).append("\n");
            if (callLocation != null) {
                sb.append("代码调用位置   : ").append(callLocation).append("\n");
            }
            sb.append("执行 SQL 语句  :\n").append(sql).append("\n");
            sb.append("=========================================");

            outputLog(sb.toString(), false);
        } catch (Throwable t) {
            log.error("输出 SQL 执行诊断日志异常", t);
        }
    }

    private void processFailure(StatementHandler statementHandler, Throwable t, long elapsedMillis) {
        try {
            MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
            MappedStatement mappedStatement = resolveMappedStatement(metaObject);
            String statementId = (mappedStatement != null) ? mappedStatement.getId() : "UNKNOWN";
            Configuration configuration = (mappedStatement != null) ? mappedStatement.getConfiguration() : null;
            BoundSql boundSql = statementHandler.getBoundSql();

            String sql = formatAndTruncateSql(boundSql, configuration);
            String callLocation = properties.isShowCallLocation() ? MybatisInterceptorUtil.resolveCallLocation() : null;
            String errorMessage = (t != null) ? (t.getClass().getSimpleName() + ": " + t.getMessage()) : "UNKNOWN ERROR";

            StringBuilder sb = new StringBuilder(sql.length() + 256);
            sb.append("\n====== [MyBatis-Plus SQL 执行失败诊断] ======\n");
            sb.append("MappedStatement : ").append(statementId).append("\n");
            sb.append("实际执行耗时   : ").append(elapsedMillis).append(" ms\n");
            if (callLocation != null) {
                sb.append("代码调用位置   : ").append(callLocation).append("\n");
            }
            sb.append("异常错误信息   : ").append(errorMessage).append("\n");
            sb.append("执行 SQL 语句  :\n").append(sql).append("\n");
            sb.append("=============================================");

            outputLog(sb.toString(), true);
        } catch (Throwable ex) {
            log.error("输出 SQL 执行失败现场日志异常", ex);
        }
    }

    private String formatAndTruncateSql(BoundSql boundSql, Configuration configuration) {
        if (boundSql == null) {
            return "";
        }
        boolean shouldFormat = properties.isFormatSql();
        int maxLen = properties.getMaxSqlLength();
        String rawSql = boundSql.getSql();
        // 性能短路熔断：原始 SQL 长度超出最大截断阈值时，自动短路跳过多行美化分段，直接单行紧凑压缩输出
        if (shouldFormat && maxLen > 0 && rawSql != null && rawSql.length() > maxLen) {
            shouldFormat = false;
        }
        String sql = SqlParameterFormatter.formatSql(boundSql, configuration, properties.isMaskSensitive(), shouldFormat);
        return applyTruncation(sql, boundSql);
    }

    private String resolveRowDescription(String methodName, Object result) {
        if ("queryCursor".equals(methodName) || (result instanceof Cursor)) {
            return "[Cursor 延迟游标流]";
        }
        if ("update".equals(methodName)) {
            if (result instanceof Number n) {
                return n.intValue() + " 行";
            }
            return (result != null ? result.toString() : "0") + " 行";
        }
        if ("query".equals(methodName)) {
            if (result instanceof Collection<?> coll) {
                return coll.size() + " 行";
            }
            if (result == null) {
                return "0 行";
            }
            return "1 行";
        }
        return (result != null) ? result.toString() : "0 行";
    }

    private String applyTruncation(String sql, BoundSql boundSql) {
        if (sql == null || properties.getMaxSqlLength() <= 0 || sql.length() <= properties.getMaxSqlLength()) {
            return sql;
        }
        int limit = properties.getMaxSqlLength();
        int totalLength = sql.length();
        int paramCount = (boundSql != null && boundSql.getParameterMappings() != null) ? boundSql.getParameterMappings().size() : 0;
        return sql.substring(0, limit) + "\n... [SQL 字符总长度 " + totalLength + "，已截断显示前 " + limit + " 字符，共包含 " + paramCount + " 组参数]";
    }

    private void outputLog(String message, boolean isError) {
        if (logConsumer != null) {
            try {
                logConsumer.accept(message);
            } catch (Throwable ignored) {
            }
        }
        if (isError) {
            log.warn(message);
        } else if ("DEBUG".equalsIgnoreCase(properties.getLevel())) {
            log.debug(message);
        } else {
            log.info(message);
        }
    }

    private static MappedStatement resolveMappedStatement(MetaObject metaObject) {
        if (metaObject.hasGetter("delegate.mappedStatement")) {
            return (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        } else if (metaObject.hasGetter("mappedStatement")) {
            return (MappedStatement) metaObject.getValue("mappedStatement");
        }
        return null;
    }
}
