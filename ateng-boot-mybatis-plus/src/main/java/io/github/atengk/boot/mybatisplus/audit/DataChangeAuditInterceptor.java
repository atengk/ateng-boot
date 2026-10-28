package io.github.atengk.boot.mybatisplus.audit;

import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.annotation.AuditChange;
import io.github.atengk.boot.mybatisplus.properties.AuditChangeProperties;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.GenericTypeResolver;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据变更 Diff 审计拦截器
 * 拦截 MyBatis Executor.update 执行（覆盖 UPDATE 与 DELETE 操作），具备三级立体开关治理：
 * <ul>
 *   <li><b>第一级（全局配置）</b>：{@code ateng.boot.mybatis-plus.audit-change.enabled=false} 或缺 Provider/Event 自动休眠；</li>
 *   <li><b>第二级（声明式注解）</b>：根据 Mapper 或实体上的 {@link AuditChange} 按需开启，支持属性包含与排除；</li>
 *   <li><b>第三级（编程式闭包）</b>：{@link DataChangeAuditContextHolder#runWithoutAudit} 线程安全短路放行。</li>
 * </ul>
 * 核心特性：
 * <ul>
 *   <li><b>前置快照与增量比对</b>：执行前抓取目标记录快照，执行后按属性比对提取 {@link FieldDiff} 变更明细；</li>
 *   <li><b>防爆熔断守卫</b>：受影响行数超过 {@code maxDiffRows} 时自动降级为元数据摘要记录，杜绝内存溢出；</li>
 *   <li><b>事务提交后异步分发</b>：在当前事务 {@code afterCommit} 后分发至 {@link DataChangeAuditProvider} SPI 与 {@link DataChangeEvent}，杜绝幻影审计记录；</li>
 *   <li><b>旁路容错不扰业务</b>：审计逻辑全程防御性隔离，若异常自动降级记录日志，严禁干扰主事务执行。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public class DataChangeAuditInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DataChangeAuditInterceptor.class);

    private static final String MDC_TRACE_ID_KEY = "traceId";

    private final AuditChangeProperties properties;
    private final DataChangeAuditProvider auditProvider;
    private final java.util.concurrent.Executor taskExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final CurrentUserProvider currentUserProvider;
    private final String defaultOperator;

    private final Map<String, AuditMeta> auditMetaCache = new ConcurrentHashMap<>();

    public DataChangeAuditInterceptor(AuditChangeProperties properties,
                                      DataChangeAuditProvider auditProvider,
                                      java.util.concurrent.Executor taskExecutor,
                                      ApplicationEventPublisher eventPublisher,
                                      String defaultOperator) {
        this(properties, auditProvider, taskExecutor, eventPublisher, null, defaultOperator);
    }

    public DataChangeAuditInterceptor(AuditChangeProperties properties,
                                      DataChangeAuditProvider auditProvider,
                                      java.util.concurrent.Executor taskExecutor,
                                      ApplicationEventPublisher eventPublisher,
                                      CurrentUserProvider currentUserProvider,
                                      String defaultOperator) {
        this.properties = properties != null ? properties : new AuditChangeProperties();
        this.auditProvider = auditProvider;
        this.taskExecutor = taskExecutor;
        this.eventPublisher = eventPublisher;
        this.currentUserProvider = currentUserProvider;
        this.defaultOperator = (defaultOperator != null && !defaultOperator.isBlank()) ? defaultOperator : "SYSTEM";
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 第一级开关判定与前置防卫
        if (!properties.isEnabled() || DataChangeAuditContextHolder.isAuditDisabled()) {
            return invocation.proceed();
        }
        if (auditProvider == null && !properties.isPublishEvent()) {
            // 缺 Provider 且未开启事件发布：自动休眠放行，避免任何额外开销
            return invocation.proceed();
        }

        MappedStatement ms = (MappedStatement) invocation.getArgs()[0];
        SqlCommandType commandType = ms.getSqlCommandType();
        if (commandType != SqlCommandType.UPDATE && commandType != SqlCommandType.DELETE) {
            return invocation.proceed();
        }

        Object parameter = invocation.getArgs()[1];
        Executor executor = (Executor) invocation.getTarget();

        // 2. 第二级注解与元数据解析
        AuditMeta meta = resolveAuditMeta(ms, parameter);
        if (meta == null || !meta.shouldAudit()) {
            return invocation.proceed();
        }

        TableInfo tableInfo = meta.getTableInfo();
        if (tableInfo == null || !tableInfo.havePK()) {
            return invocation.proceed();
        }

        // 3. 执行变更审计处理（旁路异常隔离，绝不阻断业务执行）
        try {
            return doInterceptWithAudit(invocation, executor, ms, parameter, commandType, tableInfo, meta);
        } catch (Throwable t) {
            log.warn("数据变更审计捕获异常，安全回退放行业务执行: {}", t.getMessage(), t);
            return invocation.proceed();
        }
    }

    private Object doInterceptWithAudit(Invocation invocation,
                                        Executor executor,
                                        MappedStatement ms,
                                        Object parameter,
                                        SqlCommandType commandType,
                                        TableInfo tableInfo,
                                        AuditMeta meta) throws Throwable {
        int maxDiffRows = properties.getMaxDiffRows() > 0 ? properties.getMaxDiffRows() : 100;

        // ---------- 分支 A: UPDATE 变更审计 ----------
        if (commandType == SqlCommandType.UPDATE) {
            // A1: 批量更新场景检测（如 updateBatchSomeColumn 或包含 list 入参）
            Collection<?> batchEntities = extractBatchEntities(parameter);
            if (batchEntities != null) {
                if (batchEntities.size() > maxDiffRows) {
                    // 超限直接熔断降级，不抓取前置快照
                    Object result = invocation.proceed();
                    int affectedRows = result instanceof Integer ? (Integer) result : 0;
                    if (affectedRows > 0) {
                        scheduleRecord(buildDegradedRecord(tableInfo.getTableName(), meta.getEntityClass(), ChangeType.UPDATE, affectedRows, meta.getDescription(), maxDiffRows));
                    }
                    return result;
                }

                // 未超限批量更新：分别抓取前置快照
                Map<Object, Object> beforeSnapshots = new LinkedHashMap<>();
                for (Object entity : batchEntities) {
                    Object id = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
                    if (id != null) {
                        Object before = fetchRecordById(executor, ms, tableInfo, id);
                        if (before != null) {
                            beforeSnapshots.put(id, before);
                        }
                    }
                }

                Object result = invocation.proceed();
                int affectedRows = result instanceof Integer ? (Integer) result : 0;
                if (affectedRows > 0) {
                    List<DataChangeRecord> records = new ArrayList<>();
                    for (Object entity : batchEntities) {
                        Object id = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
                        Object before = beforeSnapshots.get(id);
                        if (before != null) {
                            List<FieldDiff> diffs = computeDiffs(before, entity, tableInfo, meta);
                            if (!diffs.isEmpty()) {
                                records.add(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.UPDATE, 1, false, meta.getDescription(), diffs,
                                        snapshot(before, tableInfo, meta), snapshot(entity, tableInfo, meta)));
                            }
                        }
                    }
                    scheduleRecords(records);
                }
                return result;
            }

            // A2: 单实体更新场景检测（如 updateById）
            Object entity = extractSingleEntity(parameter, meta.getEntityClass());
            if (entity != null) {
                Object id = tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty());
                Object beforeEntity = id != null ? fetchRecordById(executor, ms, tableInfo, id) : null;

                Object result = invocation.proceed();
                int affectedRows = result instanceof Integer ? (Integer) result : 0;

                if (affectedRows > 0) {
                    if (beforeEntity != null) {
                        List<FieldDiff> diffs = computeDiffs(beforeEntity, entity, tableInfo, meta);
                        if (!diffs.isEmpty()) {
                            scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.UPDATE, affectedRows, false, meta.getDescription(), diffs,
                                    snapshot(beforeEntity, tableInfo, meta), snapshot(entity, tableInfo, meta)));
                        }
                    } else {
                        // 未获取到前置快照（如主键为 null 或快照未命中），但更新成功影响了行数：记录降级审计记录，防止无主键实体更新发生审计漏检
                        scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.UPDATE, affectedRows, true,
                                meta.getDescription(), Collections.emptyList(), null, snapshot(entity, tableInfo, meta)));
                    }
                }
                return result;
            }

            // A3: 条件更新（update with wrapper）等其它场景：执行后按影响行数判定是否超限熔断
            Object result = invocation.proceed();
            int affectedRows = result instanceof Integer ? (Integer) result : 0;
            if (affectedRows > 0) {
                boolean degraded = affectedRows > maxDiffRows;
                scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), null, ChangeType.UPDATE, affectedRows, degraded,
                        meta.getDescription(), Collections.emptyList(), null, null));
            }
            return result;
        }

        // ---------- 分支 B: DELETE 变更审计 ----------
        if (commandType == SqlCommandType.DELETE) {
            Object id = extractSingleId(parameter, tableInfo);
            Object beforeEntity = id != null ? fetchRecordById(executor, ms, tableInfo, id) : null;

            Object result = invocation.proceed();
            int affectedRows = result instanceof Integer ? (Integer) result : 0;

            if (affectedRows > 0) {
                boolean degraded = affectedRows > maxDiffRows;
                Map<String, Object> beforeSnap = (!degraded && beforeEntity != null) ? snapshot(beforeEntity, tableInfo, meta) : null;
                scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.DELETE, affectedRows, degraded,
                        meta.getDescription(), Collections.emptyList(), beforeSnap, null));
            }
            return result;
        }

        return invocation.proceed();
    }

    /**
     * 根据主键 ID 在当前事务中抓取前置实体快照
     */
    private Object fetchRecordById(Executor executor, MappedStatement ms, TableInfo tableInfo, Object id) {
        if (id == null || tableInfo == null) {
            return null;
        }
        try {
            String selectByIdId = tableInfo.getSqlStatement(SqlMethod.SELECT_BY_ID.getMethod());
            Configuration configuration = ms.getConfiguration();
            if (!configuration.hasStatement(selectByIdId, false)) {
                return null;
            }
            MappedStatement selectMs = configuration.getMappedStatement(selectByIdId);
            executor.clearLocalCache();
            List<?> list = executor.query(selectMs, id, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
            return (list != null && !list.isEmpty()) ? list.get(0) : null;
        } catch (Throwable t) {
            log.debug("抓取前置实体快照跳过 (table: {}, id: {}): {}", tableInfo.getTableName(), id, t.getMessage());
            return null;
        }
    }

    /**
     * 比对实体属性差异并生成 FieldDiff 列表
     */
    private List<FieldDiff> computeDiffs(Object beforeEntity, Object newEntity, TableInfo tableInfo, AuditMeta meta) {
        if (beforeEntity == null || newEntity == null || tableInfo == null) {
            return Collections.emptyList();
        }
        List<FieldDiff> diffs = new ArrayList<>();
        for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
            String property = fieldInfo.getProperty();
            if (!meta.getIncludeFields().isEmpty() && !meta.getIncludeFields().contains(property)) {
                continue;
            }
            if (meta.getExcludeFields().contains(property)) {
                continue;
            }

            Object newVal = tableInfo.getPropertyValue(newEntity, property);
            // MyBatis-Plus 默认 updateById 策略为 NOT_NULL，未传值为 null 表示不更新
            if (newVal == null) {
                continue;
            }
            Object oldVal = tableInfo.getPropertyValue(beforeEntity, property);
            if (!Objects.equals(oldVal, newVal)) {
                diffs.add(FieldDiff.builder()
                        .propertyName(property)
                        .columnName(fieldInfo.getColumn())
                        .oldValue(oldVal)
                        .newValue(newVal)
                        .fieldType(fieldInfo.getPropertyType().getName())
                        .build());
            }
        }
        return diffs;
    }

    /**
     * 生成实体快照 Map
     */
    private Map<String, Object> snapshot(Object entity, TableInfo tableInfo, AuditMeta meta) {
        if (entity == null || tableInfo == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        if (tableInfo.havePK()) {
            map.put(tableInfo.getKeyProperty(), tableInfo.getPropertyValue(entity, tableInfo.getKeyProperty()));
        }
        for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
            String property = fieldInfo.getProperty();
            if (meta != null && meta.getExcludeFields().contains(property)) {
                continue;
            }
            map.put(property, tableInfo.getPropertyValue(entity, property));
        }
        return map;
    }

    /**
     * 构建标准变更审计记录
     */
    private DataChangeRecord buildRecord(String tableName,
                                         Class<?> entityClass,
                                         Object entityId,
                                         ChangeType changeType,
                                         Integer affectedRows,
                                         boolean degraded,
                                         String description,
                                         List<FieldDiff> diffs,
                                         Map<String, Object> beforeSnapshot,
                                         Map<String, Object> afterSnapshot) {
        return DataChangeRecord.builder()
                .tableName(tableName)
                .entityClass(entityClass != null ? entityClass.getName() : null)
                .entityId(entityId)
                .changeType(changeType)
                .operator(resolveCurrentUser())
                .tenantId(TenantContextHolder.getTenantId())
                .traceId(MDC.get(MDC_TRACE_ID_KEY))
                .changeTime(LocalDateTime.now())
                .affectedRows(affectedRows)
                .degraded(degraded)
                .description(description)
                .diffs(diffs)
                .beforeSnapshot(beforeSnapshot)
                .afterSnapshot(afterSnapshot)
                .build();
    }

    /**
     * 构建超限熔断降级摘要记录
     */
    private DataChangeRecord buildDegradedRecord(String tableName,
                                                 Class<?> entityClass,
                                                 ChangeType changeType,
                                                 Integer affectedRows,
                                                 String description,
                                                 int maxDiffRows) {
        String msg = (description != null && !description.isBlank()) ? description : "";
        msg = msg.isEmpty() ? "批量更新影响行数 (" + affectedRows + ") 超出阈值 (" + maxDiffRows + ")，自动熔断降级为摘要记录"
                : msg + " [超限熔断降级: " + affectedRows + " > " + maxDiffRows + "]";
        return buildRecord(tableName, entityClass, null, changeType, affectedRows, true, msg, Collections.emptyList(), null, null);
    }

    /**
     * 调度投递审计记录（事务提交后回调或直接投递）
     */
    private void scheduleRecord(DataChangeRecord record) {
        if (record != null) {
            scheduleRecords(List.of(record));
        }
    }

    /**
     * 调度投递多条审计记录
     */
    private void scheduleRecords(List<DataChangeRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(records);
                }
            });
        } else {
            dispatch(records);
        }
    }

    /**
     * 执行分发（根据配置决定同步或异步）
     */
    private void dispatch(List<DataChangeRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Runnable task = () -> {
            try {
                if (auditProvider != null) {
                    auditProvider.onDataChanged(records);
                }
                if (properties.isPublishEvent() && eventPublisher != null) {
                    eventPublisher.publishEvent(new DataChangeEvent(this, records));
                }
            } catch (Throwable t) {
                log.error("数据变更审计记录投递异常", t);
            }
        };

        if (properties.isAsync()) {
            if (taskExecutor != null) {
                taskExecutor.execute(task);
            } else {
                log.warn("未检测到受管 TaskExecutor 异步线程池，降级为同步分发数据变更审计事件");
                task.run();
            }
        } else {
            task.run();
        }
    }

    /**
     * 解析操作人标识
     */
    private String resolveCurrentUser() {
        String user = null;
        if (currentUserProvider != null) {
            try {
                user = currentUserProvider.getCurrentUser();
            } catch (Throwable ignored) {
            }
        }
        if (user == null || user.isBlank()) {
            user = UserContextHolder.get();
        }
        if (user == null || user.isBlank()) {
            user = defaultOperator;
        }
        return user;
    }

    /**
     * 解析并缓存 MappedStatement 对应的 @AuditChange 元数据
     */
    private AuditMeta resolveAuditMeta(MappedStatement ms, Object parameter) {
        String msId = ms.getId();
        return auditMetaCache.computeIfAbsent(msId, id -> {
            Class<?> mapperClass = null;
            Method mapperMethod = null;
            int lastDot = id.lastIndexOf('.');
            if (lastDot > 0) {
                String className = id.substring(0, lastDot);
                String methodName = id.substring(lastDot + 1);
                try {
                    mapperClass = Class.forName(className);
                    for (Method m : mapperClass.getMethods()) {
                        if (m.getName().equals(methodName)) {
                            mapperMethod = m;
                            break;
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                }
            }

            AuditChange auditChange = null;
            if (mapperMethod != null) {
                auditChange = mapperMethod.getAnnotation(AuditChange.class);
            }
            if (auditChange == null && mapperClass != null) {
                auditChange = mapperClass.getAnnotation(AuditChange.class);
            }

            Class<?> entityClass = resolveEntityClass(mapperClass);
            if (entityClass == null && parameter != null) {
                entityClass = extractEntityClass(parameter);
            }

            if (auditChange == null && entityClass != null) {
                auditChange = entityClass.getAnnotation(AuditChange.class);
            }

            if (auditChange == null || !auditChange.enabled()) {
                return AuditMeta.DISABLED;
            }

            TableInfo tableInfo = entityClass != null ? TableInfoHelper.getTableInfo(entityClass) : null;
            Set<String> includes = auditChange.includeFields().length > 0 ? Set.of(auditChange.includeFields()) : Collections.emptySet();
            Set<String> excludes = auditChange.excludeFields().length > 0 ? Set.of(auditChange.excludeFields()) : Collections.emptySet();

            return new AuditMeta(true, auditChange, includes, excludes, auditChange.description(), entityClass, tableInfo);
        });
    }

    private Class<?> resolveEntityClass(Class<?> mapperClass) {
        if (mapperClass == null) {
            return null;
        }
        Class<?> resolved = GenericTypeResolver.resolveTypeArgument(mapperClass, BaseMapper.class);
        if (resolved != null && resolved != Object.class) {
            return resolved;
        }
        return null;
    }

    private Class<?> extractEntityClass(Object parameter) {
        if (parameter == null) {
            return null;
        }
        if (parameter instanceof Map<?, ?> map) {
            if (map.containsKey("et") && map.get("et") != null) {
                return map.get("et").getClass();
            }
            if (map.containsKey("entity") && map.get("entity") != null) {
                return map.get("entity").getClass();
            }
            if (map.containsKey("list") && map.get("list") instanceof Collection<?> col && !col.isEmpty()) {
                Object first = col.iterator().next();
                return first != null ? first.getClass() : null;
            }
        }
        return parameter.getClass();
    }

    private Collection<?> extractBatchEntities(Object parameter) {
        if (parameter instanceof Map<?, ?> map) {
            if (map.containsKey("list") && map.get("list") instanceof Collection<?> col) {
                return col;
            }
        }
        if (parameter instanceof Collection<?> col) {
            return col;
        }
        return null;
    }

    private Object extractSingleEntity(Object parameter, Class<?> entityClass) {
        if (parameter == null) {
            return null;
        }
        if (entityClass != null && entityClass.isInstance(parameter)) {
            return parameter;
        }
        if (parameter instanceof Map<?, ?> map) {
            if (map.containsKey("et")) {
                return map.get("et");
            }
            if (map.containsKey("entity")) {
                return map.get("entity");
            }
            if (map.containsKey("param1")) {
                Object p1 = map.get("param1");
                if (entityClass != null && entityClass.isInstance(p1)) {
                    return p1;
                }
            }
        }
        return null;
    }

    private Object extractSingleId(Object parameter, TableInfo tableInfo) {
        if (parameter == null || tableInfo == null) {
            return null;
        }
        if (parameter instanceof Serializable && !(parameter instanceof Map)) {
            if (tableInfo.getEntityType() == null || !tableInfo.getEntityType().isInstance(parameter)) {
                return parameter;
            }
        }
        if (tableInfo.getEntityType() != null && tableInfo.getEntityType().isInstance(parameter)) {
            return tableInfo.getPropertyValue(parameter, tableInfo.getKeyProperty());
        }
        if (parameter instanceof Map<?, ?> map) {
            if (map.containsKey("id")) {
                return map.get("id");
            }
            if (map.containsKey("param1") && !(map.get("param1") instanceof Collection)) {
                return map.get("param1");
            }
        }
        return null;
    }

    /**
     * @AuditChange 反射元数据不可变缓存项
     */
    private static class AuditMeta {
        private static final AuditMeta DISABLED = new AuditMeta(false, null, Collections.emptySet(), Collections.emptySet(), null, null, null);

        private final boolean shouldAudit;
        private final AuditChange annotation;
        private final Set<String> includeFields;
        private final Set<String> excludeFields;
        private final String description;
        private final Class<?> entityClass;
        private final TableInfo tableInfo;

        AuditMeta(boolean shouldAudit,
                  AuditChange annotation,
                  Set<String> includeFields,
                  Set<String> excludeFields,
                  String description,
                  Class<?> entityClass,
                  TableInfo tableInfo) {
            this.shouldAudit = shouldAudit;
            this.annotation = annotation;
            this.includeFields = includeFields;
            this.excludeFields = excludeFields;
            this.description = description;
            this.entityClass = entityClass;
            this.tableInfo = tableInfo;
        }

        public boolean shouldAudit() {
            return shouldAudit;
        }

        public AuditChange getAnnotation() {
            return annotation;
        }

        public Set<String> getIncludeFields() {
            return includeFields;
        }

        public Set<String> getExcludeFields() {
            return excludeFields;
        }

        public String getDescription() {
            return description;
        }

        public Class<?> getEntityClass() {
            return entityClass;
        }

        public TableInfo getTableInfo() {
            return tableInfo;
        }
    }
}
