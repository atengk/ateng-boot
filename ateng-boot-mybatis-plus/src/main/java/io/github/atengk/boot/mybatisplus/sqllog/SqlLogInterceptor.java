package io.github.atengk.boot.mybatisplus.sqllog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.SqlTypeEnum;
import io.github.atengk.boot.mybatisplus.annotation.IgnoreSqlLog;
import io.github.atengk.boot.mybatisplus.properties.SqlLogProperties;
import io.github.atengk.boot.mybatisplus.util.MybatisInterceptorUtil;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * SQL 日志打印与可执行带参诊断拦截器
 * 拦截 MyBatis 原生 StatementHandler 物理执行终态（query/update/batch/queryCursor），
 * 提供一键可执行带参 SQL 拼装、执行耗时、影响/返回行数看板与调用栈精准穿透；
 * 同时作为物理 SQL 审计数据源，支持双轨调度：本地控制台 ASCII 看板与外部 SPI（{@link SqlLogStorageProvider}）
 * 结构化持久化及 Spring 应用层事件广播（{@link SqlLogEvent}）。
 * 内置多值批量单条输出、敏感字段自动脱敏掩码、@IgnoreSqlLog 注解排除与超长截断防爆保护。
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
    private static final String MDC_TRACE_ID_KEY = "traceId";

    private static final Method DYNAMIC_DS_PEEK_METHOD;
    static {
        Method method = null;
        try {
            Class<?> clazz = Class.forName("com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder");
            method = clazz.getMethod("peek");
        } catch (Throwable ignored) {
            // dynamic-datasource 依赖未引入时静默忽略
        }
        DYNAMIC_DS_PEEK_METHOD = method;
    }

    /**
     * 内部 SQL 拦截执行四维与数据源上下文快照（消除数据泥团 Data Clump）
     */
    private record SqlExecutionContext(
            String traceId,
            String tenantId,
            String userId,
            String userName,
            String datasource
    ) {
        static SqlExecutionContext capture(Invocation invocation) {
            String traceId = MDC.get(MDC_TRACE_ID_KEY);
            String tenantId = TenantContextHolder.getTenantId();
            String userId = UserContextHolder.getUserId();
            String userName = UserContextHolder.get();
            String datasource = resolveDatasource(invocation);
            return new SqlExecutionContext(traceId, tenantId, userId, userName, datasource);
        }
    }

    private final SqlLogProperties properties;
    private final Consumer<String> logConsumer;
    private final SqlLogStorageProvider storageProvider;
    private final ObjectProvider<Executor> taskExecutorProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final ConcurrentMap<String, Boolean> ignoreAnnotationCache = new ConcurrentHashMap<>();

    public SqlLogInterceptor(SqlLogProperties properties) {
        this(properties, null, null, null, null);
    }

    public SqlLogInterceptor(SqlLogProperties properties, Consumer<String> logConsumer) {
        this(properties, logConsumer, null, null, null);
    }

    public SqlLogInterceptor(SqlLogProperties properties,
                             Consumer<String> logConsumer,
                             SqlLogStorageProvider storageProvider,
                             ObjectProvider<Executor> taskExecutorProvider,
                             ApplicationEventPublisher eventPublisher) {
        this.properties = properties != null ? properties : new SqlLogProperties();
        this.logConsumer = logConsumer;
        this.storageProvider = storageProvider;
        this.taskExecutorProvider = taskExecutorProvider;
        this.eventPublisher = eventPublisher;
    }

    @Deprecated
    public SqlLogInterceptor(SqlLogProperties properties,
                             Consumer<String> logConsumer,
                             SqlLogStorageProvider storageProvider,
                             ObjectProvider<Executor> taskExecutorProvider,
                             ApplicationEventPublisher eventPublisher,
                             ObjectMapper objectMapper) {
        this(properties, logConsumer, storageProvider, taskExecutorProvider, eventPublisher);
    }

    public SqlLogProperties getProperties() {
        return properties;
    }

    public SqlLogStorageProvider getStorageProvider() {
        return storageProvider;
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

        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement mappedStatement = resolveMappedStatement(metaObject);
        if (mappedStatement == null) {
            return invocation.proceed();
        }

        // 细粒度过滤防线：排除全局通配规则与 @IgnoreSqlLog 标注
        if (isIgnored(mappedStatement)) {
            return invocation.proceed();
        }

        // 捕获上下文快照
        SqlExecutionContext ctx = SqlExecutionContext.capture(invocation);

        long startNanos = System.nanoTime();
        Object result = null;
        try {
            result = invocation.proceed();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            processSuccess(statementHandler, mappedStatement, methodName, result, elapsedMillis, ctx);
            return result;
        } catch (Throwable t) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            processFailure(statementHandler, mappedStatement, t, elapsedMillis, ctx);
            throw t;
        }
    }

    private boolean isIgnored(MappedStatement mappedStatement) {
        String statementId = mappedStatement.getId();
        // 1. 全局 exclude-statements 通配排除
        List<String> excludes = properties.getExcludeStatements();
        if (excludes != null && !excludes.isEmpty()) {
            String[] patterns = excludes.toArray(new String[0]);
            if (PatternMatchUtils.simpleMatch(patterns, statementId)) {
                return true;
            }
        }
        // 2. @IgnoreSqlLog 声明式注解排除（统一由 MybatisInterceptorUtil 支持衍生后缀剥离与缓存）
        return MybatisInterceptorUtil.hasMapperAnnotation(statementId, IgnoreSqlLog.class, ignoreAnnotationCache);
    }

    private void processSuccess(StatementHandler statementHandler,
                                MappedStatement mappedStatement,
                                String methodName,
                                Object result,
                                long elapsedMillis,
                                SqlExecutionContext ctx) {
        try {
            String statementId = mappedStatement.getId();
            Configuration configuration = mappedStatement.getConfiguration();
            BoundSql boundSql = statementHandler.getBoundSql();

            String sql = formatAndTruncateSql(boundSql, configuration, properties.getMaxSqlLength());
            String rowDesc = resolveRowDescription(methodName, result);
            String callLocation = properties.isShowCallLocation() ? MybatisInterceptorUtil.resolveCallLocation() : null;

            // 1. 控制台诊断看板输出管道
            if (properties.isConsoleEnabled()) {
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
            }

            // 2. 结构化 SPI 审计持久化管道
            if (isStorageActive()) {
                SqlTypeEnum sqlType = resolveSqlType(mappedStatement.getSqlCommandType());
                if (shouldDispatchToStorage(sqlType)) {
                    int storageMaxLen = properties.getStorage().getMaxSqlLength();
                    String storageSql = formatAndTruncateSql(boundSql, configuration, storageMaxLen);
                    Long affectedRows = resolveAffectedRows(methodName, result);

                    SqlLogModel logModel = buildLogModel(ctx, statementId, storageSql, sqlType,
                            elapsedMillis, affectedRows, 0, null, callLocation);

                    dispatchLog(logModel);
                }
            }
        } catch (Throwable t) {
            log.error("输出 SQL 执行诊断日志异常", t);
        }
    }

    private void processFailure(StatementHandler statementHandler,
                                MappedStatement mappedStatement,
                                Throwable t,
                                long elapsedMillis,
                                SqlExecutionContext ctx) {
        try {
            String statementId = mappedStatement.getId();
            Configuration configuration = mappedStatement.getConfiguration();
            BoundSql boundSql = statementHandler.getBoundSql();

            String sql = formatAndTruncateSql(boundSql, configuration, properties.getMaxSqlLength());
            String callLocation = properties.isShowCallLocation() ? MybatisInterceptorUtil.resolveCallLocation() : null;
            String errorMessage = (t != null) ? (t.getClass().getSimpleName() + ": " + t.getMessage()) : "UNKNOWN ERROR";

            // 1. 控制台诊断看板输出管道
            if (properties.isConsoleEnabled()) {
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
            }

            // 2. 结构化 SPI 审计持久化管道（失败现场 100% 采集，不受 includeSelect 过滤限制）
            if (isStorageActive()) {
                SqlTypeEnum sqlType = resolveSqlType(mappedStatement.getSqlCommandType());
                int storageMaxLen = properties.getStorage().getMaxSqlLength();
                String storageSql = formatAndTruncateSql(boundSql, configuration, storageMaxLen);

                SqlLogModel logModel = buildLogModel(ctx, statementId, storageSql, sqlType,
                        elapsedMillis, 0L, 1, errorMessage, callLocation);

                dispatchLog(logModel);
            }
        } catch (Throwable ex) {
            log.error("输出 SQL 执行失败现场日志异常", ex);
        }
    }

    private SqlLogModel buildLogModel(SqlExecutionContext ctx,
                                      String statementId,
                                      String storageSql,
                                      SqlTypeEnum sqlType,
                                      long elapsedMillis,
                                      Long affectedRows,
                                      int status,
                                      String errorMsg,
                                      String callLocation) {
        return SqlLogModel.builder()
                .traceId(ctx.traceId())
                .tenantId(ctx.tenantId())
                .userId(ctx.userId())
                .userName(ctx.userName())
                .datasource(ctx.datasource())
                .statementId(statementId)
                .sql(storageSql)
                .sqlType(sqlType)
                .costTime(elapsedMillis)
                .affectedRows(affectedRows)
                .status(status)
                .errorMsg(errorMsg)
                .callLocation(callLocation)
                .operateTime(LocalDateTime.now())
                .build();
    }

    private boolean isStorageActive() {
        return properties.getStorage().isEnabled() && (storageProvider != null || eventPublisher != null);
    }

    private boolean shouldDispatchToStorage(SqlTypeEnum sqlType) {
        if (sqlType == null) {
            return false;
        }
        if (sqlType.isQuery()) {
            return properties.getStorage().isIncludeSelect();
        }
        return true;
    }

    private void dispatchLog(SqlLogModel logModel) {
        if (logModel == null) {
            return;
        }
        boolean async = properties.getStorage().isAsync();
        Executor executor = (async && taskExecutorProvider != null) ? taskExecutorProvider.getIfAvailable() : null;
        if (executor != null) {
            try {
                Runnable task = AsyncContext.wrap(() -> safeStoreAndPublish(logModel));
                executor.execute(task);
            } catch (Exception e) {
                log.warn("[SQL_LOG] 异步派发 SQL 审计记录失败，降级同步执行: traceId={}, statementId={}, error={}",
                        logModel.getTraceId(), logModel.getStatementId(), e.getMessage());
                safeStoreAndPublish(logModel);
            }
        } else {
            safeStoreAndPublish(logModel);
        }
    }

    private void safeStoreAndPublish(SqlLogModel logModel) {
        try {
            if (storageProvider != null) {
                storageProvider.store(logModel);
            }
            if (eventPublisher != null) {
                eventPublisher.publishEvent(new SqlLogEvent(this, logModel));
            }
        } catch (Throwable t) {
            log.error("[SQL_LOG] 物理 SQL 审计存储持久化异常: traceId={}, statementId={}, error={}",
                    logModel.getTraceId(), logModel.getStatementId(), t.getMessage(), t);
        }
    }

    private SqlTypeEnum resolveSqlType(SqlCommandType commandType) {
        if (commandType == null) {
            return SqlTypeEnum.OTHER;
        }
        return switch (commandType) {
            case SELECT -> SqlTypeEnum.SELECT;
            case INSERT -> SqlTypeEnum.INSERT;
            case UPDATE -> SqlTypeEnum.UPDATE;
            case DELETE -> SqlTypeEnum.DELETE;
            default -> SqlTypeEnum.OTHER;
        };
    }

    private static String resolveDatasource(Invocation invocation) {
        // 1. 优先探测第三方 dynamic-datasource 活跃数据源 key（静态缓存反射加速）
        if (DYNAMIC_DS_PEEK_METHOD != null) {
            try {
                Object dsKey = DYNAMIC_DS_PEEK_METHOD.invoke(null);
                if (dsKey != null && StringUtils.hasText(dsKey.toString())) {
                    return dsKey.toString();
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. 尝试从 Statement 活跃物理连接获取 catalog/database
        try {
            Object[] args = invocation.getArgs();
            if (args != null && args.length > 0 && args[0] instanceof Statement stmt) {
                Connection conn = stmt.getConnection();
                if (conn != null) {
                    String catalog = conn.getCatalog();
                    if (StringUtils.hasText(catalog)) {
                        return catalog;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "default";
    }

    private String formatAndTruncateSql(BoundSql boundSql, Configuration configuration, int maxLen) {
        if (boundSql == null) {
            return "";
        }
        boolean shouldFormat = properties.isFormatSql();
        String rawSql = boundSql.getSql();
        if (shouldFormat && maxLen > 0 && rawSql != null && rawSql.length() > maxLen) {
            shouldFormat = false;
        }
        String sql = SqlParameterFormatter.formatSql(boundSql, configuration, properties.isMaskSensitive(), shouldFormat);
        return applyTruncation(sql, boundSql, maxLen);
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

    private Long resolveAffectedRows(String methodName, Object result) {
        if ("update".equals(methodName)) {
            if (result instanceof Number n) {
                return n.longValue();
            }
        } else if ("query".equals(methodName)) {
            if (result instanceof Collection<?> coll) {
                return (long) coll.size();
            }
            if (result != null) {
                return 1L;
            }
            return 0L;
        }
        return null;
    }

    private String applyTruncation(String sql, BoundSql boundSql, int maxLen) {
        if (sql == null || maxLen <= 0 || sql.length() <= maxLen) {
            return sql;
        }
        int totalLength = sql.length();
        int paramCount = (boundSql != null && boundSql.getParameterMappings() != null) ? boundSql.getParameterMappings().size() : 0;
        return sql.substring(0, maxLen) + "\n... [SQL 字符总长度 " + totalLength + "，已截断显示前 " + maxLen + " 字符，共包含 " + paramCount + " 组参数]";
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
