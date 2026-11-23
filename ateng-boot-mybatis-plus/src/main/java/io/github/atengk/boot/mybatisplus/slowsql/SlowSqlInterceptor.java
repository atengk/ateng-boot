package io.github.atengk.boot.mybatisplus.slowsql;

import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import io.github.atengk.boot.mybatisplus.sqllog.SqlParameterFormatter;
import io.github.atengk.boot.mybatisplus.util.MybatisInterceptorUtil;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
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
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * 慢 SQL 性能审计与告警拦截器
 * 拦截 MyBatis 原生 StatementHandler 执行（query/update/batch/queryCursor），
 * 基于 System.nanoTime() 高精度纳秒时钟统计包含底层 JDBC 交互与网络 IO 的真实耗时；
 * 超时后输出多行美化 SQL、真实脱敏参数及业务调用栈，并通知 SlowSqlAlertProvider SPI 与发布 Spring 事件，
 * 恪守生产防雪崩铁律严禁自动发起 EXPLAIN。
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "batch", args = {Statement.class}),
        @Signature(type = StatementHandler.class, method = "queryCursor", args = {Statement.class})
})
public class SlowSqlInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlInterceptor.class);

    private static final Map<String, Boolean> SENSITIVE_PROPERTY_CACHE = new ConcurrentHashMap<>();

    private final Duration threshold;
    private final long thresholdNanos;
    private final boolean formatSql;
    private final boolean maskSensitive;
    private final boolean asyncEnabled;
    private final List<SlowSqlAlertProvider> alertProviders;
    private final ApplicationEventPublisher eventPublisher;
    private final Executor taskExecutor;

    public SlowSqlInterceptor(Duration threshold,
                              boolean formatSql,
                              boolean maskSensitive,
                              boolean asyncEnabled,
                              List<SlowSqlAlertProvider> alertProviders,
                              ApplicationEventPublisher eventPublisher,
                              Executor taskExecutor) {
        this.threshold = (threshold != null) ? threshold : Duration.ofSeconds(1);
        this.thresholdNanos = this.threshold.toNanos();
        this.formatSql = formatSql;
        this.maskSensitive = maskSensitive;
        this.asyncEnabled = asyncEnabled;
        this.alertProviders = (alertProviders != null) ? List.copyOf(alertProviders) : List.of();
        this.eventPublisher = eventPublisher;
        this.taskExecutor = taskExecutor;
    }

    public SlowSqlInterceptor(Duration threshold,
                              boolean formatSql,
                              boolean maskSensitive,
                              List<SlowSqlAlertProvider> alertProviders,
                              ApplicationEventPublisher eventPublisher) {
        this(threshold, formatSql, maskSensitive, false, alertProviders, eventPublisher, null);
    }

    public SlowSqlInterceptor(Duration threshold,
                              boolean formatSql,
                              boolean maskSensitive,
                              SlowSqlAlertProvider alertProvider,
                              ApplicationEventPublisher eventPublisher) {
        this(threshold, formatSql, maskSensitive, false, alertProvider != null ? List.of(alertProvider) : List.of(), eventPublisher, null);
    }

    public SlowSqlInterceptor(Duration threshold) {
        this(threshold, true, true, false, null, null, null);
    }

    public SlowSqlInterceptor() {
        this(Duration.ofSeconds(1));
    }

    public Duration getThreshold() {
        return threshold;
    }

    public boolean isFormatSql() {
        return formatSql;
    }

    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    public Executor getTaskExecutor() {
        return taskExecutor;
    }

    public SlowSqlAlertProvider getAlertProvider() {
        return (alertProviders != null && !alertProviders.isEmpty()) ? alertProviders.get(0) : null;
    }

    public List<SlowSqlAlertProvider> getAlertProviders() {
        return alertProviders;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return invocation.proceed();
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            // 零损耗纳秒差值快速比对，未超时 SQL 直接放行，零额外对象分配
            if (elapsedNanos >= thresholdNanos) {
                processSlowSql(invocation, elapsedNanos);
            }
        }
    }

    private void processSlowSql(Invocation invocation, long elapsedNanos) {
        try {
            Object target = MybatisInterceptorUtil.realTarget(invocation.getTarget());
            if (!(target instanceof StatementHandler statementHandler)) {
                return;
            }

            MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
            MappedStatement mappedStatement = null;
            if (metaObject.hasGetter("delegate.mappedStatement")) {
                mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
            } else if (metaObject.hasGetter("mappedStatement")) {
                mappedStatement = (MappedStatement) metaObject.getValue("mappedStatement");
            }

            String statementId = (mappedStatement != null) ? mappedStatement.getId() : "UNKNOWN";
            BoundSql boundSql = statementHandler.getBoundSql();
            String rawSql = (boundSql != null) ? boundSql.getSql() : "";
            String finalSql = (formatSql) ? formatSql(rawSql) : SqlParameterFormatter.compress(rawSql);

            Configuration configuration = (mappedStatement != null) ? mappedStatement.getConfiguration() : null;
            List<Object> parameters = extractParameters(boundSql, configuration);

            Duration elapsed = Duration.ofNanos(elapsedNanos);
            Instant triggerTime = Instant.now();
            String threadName = Thread.currentThread().getName();
            String callLocation = MybatisInterceptorUtil.resolveCallLocation();

            SlowSqlEvent event = new SlowSqlEvent(
                    this,
                    statementId,
                    finalSql,
                    parameters,
                    elapsed,
                    threshold,
                    triggerTime,
                    threadName,
                    callLocation
            );

            log.warn("""
                    ====== [MyBatis-Plus 慢 SQL 性能审计告警] ======
                    MappedStatement : {}
                    实际执行耗时   : {} ms (判定阈值: {} ms)
                    代码调用位置   : {}
                    执行线程       : {}
                    执行 SQL 语句  :
                    {}
                    绑定参数列表   : {}
                    排查建议       : 严禁在线上直接执行 EXPLAIN；建议在测试库分析索引命中与扫描行数
                    ===============================================""",
                    statementId,
                    elapsed.toMillis(),
                    threshold.toMillis(),
                    callLocation,
                    threadName,
                    finalSql,
                    parameters
            );

            dispatchSlowSqlNotifications(statementId, event);
        } catch (Throwable t) {
            log.error("处理慢 SQL 审计与告警异常", t);
        }
    }

    private void dispatchSlowSqlNotifications(String statementId, SlowSqlEvent event) {
        if ((alertProviders == null || alertProviders.isEmpty()) && eventPublisher == null) {
            return;
        }
        if (asyncEnabled && taskExecutor != null) {
            try {
                Runnable task = AsyncContext.wrap(() -> safeDispatchAlertsAndEvents(event));
                taskExecutor.execute(task);
            } catch (Throwable t) {
                log.warn("异步派发慢 SQL 告警失败，降级为同步执行: statementId={}, error={}",
                        statementId, t.getMessage(), t);
                safeDispatchAlertsAndEvents(event);
            }
            return;
        }
        if (asyncEnabled) {
            log.warn("已开启 slow-sql.async-enabled=true 但未检测到受管 Executor 线程池，降级同步派发慢 SQL 告警: statementId={}",
                    statementId);
        }
        safeDispatchAlertsAndEvents(event);
    }

    private void safeDispatchAlertsAndEvents(SlowSqlEvent event) {
        if (alertProviders != null && !alertProviders.isEmpty()) {
            for (SlowSqlAlertProvider provider : alertProviders) {
                try {
                    provider.onSlowSql(event);
                } catch (Throwable t) {
                    log.error("调用慢 SQL 告警通知提供者 [{}] 失败", provider.getClass().getName(), t);
                }
            }
        }

        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(event);
            } catch (Throwable t) {
                log.error("发布 SlowSqlEvent 慢 SQL 审计事件失败", t);
            }
        }
    }

    private List<Object> extractParameters(BoundSql boundSql, Configuration configuration) {
        if (boundSql == null) {
            return List.of();
        }
        Object parameterObject = boundSql.getParameterObject();
        if (parameterObject == null) {
            return List.of();
        }

        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        if (parameterMappings == null || parameterMappings.isEmpty()) {
            return List.of();
        }

        List<Object> result = new ArrayList<>(parameterMappings.size());
        MetaObject metaParam = (configuration != null) ? configuration.newMetaObject(parameterObject) : SystemMetaObject.forObject(parameterObject);

        for (ParameterMapping mapping : parameterMappings) {
            String propName = mapping.getProperty();
            Object value = null;
            if (boundSql.hasAdditionalParameter(propName)) {
                value = boundSql.getAdditionalParameter(propName);
            } else if (configuration != null && configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass())) {
                value = parameterObject;
            } else if (metaParam.hasGetter(propName)) {
                value = metaParam.getValue(propName);
            } else if (parameterMappings.size() == 1 && configuration != null) {
                value = parameterObject;
            }

            if (maskSensitive) {
                value = maskParamValue(propName, value, mapping, parameterObject);
            }
            result.add(value);
        }
        return result;
    }

    private Object maskParamValue(String propName, Object value, ParameterMapping mapping, Object parameterObject) {
        if (value == null) {
            return null;
        }

        // 1. 若映射声明了 EncryptTypeHandler，直接掩码
        if (mapping != null && mapping.getTypeHandler() instanceof EncryptTypeHandler) {
            return "******";
        }

        // 2. 检查实体字段是否标注 @FieldEncrypt
        if (isFieldEncrypted(parameterObject, propName)) {
            return "******";
        }

        // 3. 检查属性名是否包含密码/凭据敏感关键字
        if (propName != null) {
            String lower = propName.toLowerCase();
            if (lower.contains("password") || lower.contains("pwd") ||
                    lower.contains("secret") || lower.contains("token") ||
                    lower.contains("credential") || (lower.contains("key") && lower.contains("private"))) {
                return "******";
            }
        }

        return value;
    }

    private boolean isFieldEncrypted(Object parameterObject, String propName) {
        if (parameterObject == null || propName == null || propName.isBlank()) {
            return false;
        }
        Class<?> targetClass = parameterObject.getClass();
        String fieldName = propName;

        if (propName.contains(".")) {
            String[] parts = propName.split("\\.");
            Object currentObj = parameterObject;
            for (int i = 0; i < parts.length - 1; i++) {
                if (currentObj instanceof Map<?, ?> map) {
                    currentObj = map.get(parts[i]);
                } else if (currentObj != null) {
                    try {
                        MetaObject mo = SystemMetaObject.forObject(currentObj);
                        if (mo.hasGetter(parts[i])) {
                            currentObj = mo.getValue(parts[i]);
                        } else {
                            currentObj = null;
                        }
                    } catch (Exception ignored) {
                        currentObj = null;
                    }
                }
                if (currentObj == null) {
                    break;
                }
            }
            if (currentObj != null) {
                targetClass = currentObj.getClass();
            }
            fieldName = parts[parts.length - 1];
        }

        return checkFieldEncryptAnnotation(targetClass, fieldName);
    }

    private boolean checkFieldEncryptAnnotation(Class<?> entityClass, String propName) {
        if (entityClass == null || propName == null || propName.isBlank()) {
            return false;
        }
        String cacheKey = entityClass.getName() + "#" + propName;
        return SENSITIVE_PROPERTY_CACHE.computeIfAbsent(cacheKey, k -> {
            Class<?> current = entityClass;
            while (current != null && current != Object.class) {
                try {
                    Field field = current.getDeclaredField(propName);
                    if (field.isAnnotationPresent(FieldEncrypt.class)) {
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
            return false;
        });
    }

    static String formatSql(String sql) {
        return SqlParameterFormatter.prettify(sql);
    }
}
