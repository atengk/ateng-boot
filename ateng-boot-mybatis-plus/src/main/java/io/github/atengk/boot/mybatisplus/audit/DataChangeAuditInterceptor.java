package io.github.atengk.boot.mybatisplus.audit;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.enums.SqlMethod;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.annotation.AuditChange;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import io.github.atengk.boot.mybatisplus.properties.AuditChangeProperties;
import io.github.atengk.boot.mybatisplus.util.MybatisInterceptorUtil;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

        // 判定是否为逻辑删除语义（针对配置了 @TableLogic 的实体，deleteById/deleteBatchIds/delete 等映射为 UPDATE）
        boolean logicDelete = (commandType == SqlCommandType.UPDATE) && isLogicDelete(ms, tableInfo, parameter);

        // ---------- 分支 A: DELETE（物理删除 或 识别出的逻辑删除）变更审计 ----------
        if (commandType == SqlCommandType.DELETE || logicDelete) {
            // A1: 批量主键删除场景（deleteBatchIds / deletePhysicallyBatchByIds 或 Collection/Map 入参）
            Collection<?> batchIds = extractBatchIds(parameter);
            if (batchIds != null && !batchIds.isEmpty()) {
                if (batchIds.size() > maxDiffRows) {
                    Object result = invocation.proceed();
                    int affectedRows = result instanceof Integer ? (Integer) result : 0;
                    if (affectedRows > 0) {
                        scheduleRecord(buildDegradedRecord(tableInfo.getTableName(), meta.getEntityClass(), ChangeType.DELETE, affectedRows, meta.getDescription(), maxDiffRows));
                    }
                    return result;
                }

                Map<Object, Object> beforeSnapshots = new LinkedHashMap<>();
                for (Object id : batchIds) {
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
                    for (Object id : batchIds) {
                        Object before = beforeSnapshots.get(id);
                        if (before != null) {
                            records.add(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.DELETE, 1, false,
                                    meta.getDescription(), Collections.emptyList(), snapshot(before, tableInfo, meta), null));
                        }
                    }
                    if (records.isEmpty()) {
                        records.add(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), null, ChangeType.DELETE, affectedRows, true,
                                meta.getDescription(), Collections.emptyList(), null, null));
                    }
                    scheduleRecords(records);
                }
                return result;
            }

            // A2: 单主键删除场景（deleteById / deletePhysicallyById 或单个 ID / 单实体）
            Object id = extractSingleId(parameter, tableInfo);
            if (id != null) {
                Object beforeEntity = fetchRecordById(executor, ms, tableInfo, id);

                Object result = invocation.proceed();
                int affectedRows = result instanceof Integer ? (Integer) result : 0;

                if (affectedRows > 0) {
                    boolean degraded = (beforeEntity == null);
                    Map<String, Object> beforeSnap = beforeEntity != null ? snapshot(beforeEntity, tableInfo, meta) : null;
                    scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), id, ChangeType.DELETE, affectedRows, degraded,
                            meta.getDescription(), Collections.emptyList(), beforeSnap, null));
                }
                return result;
            }

            // A3: 条件删除（delete with wrapper / deleteByMap）
            Object result = invocation.proceed();
            int affectedRows = result instanceof Integer ? (Integer) result : 0;
            if (affectedRows > 0) {
                boolean degraded = affectedRows > maxDiffRows;
                scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), null, ChangeType.DELETE, affectedRows, degraded,
                        meta.getDescription(), Collections.emptyList(), null, null));
            }
            return result;
        }

        // ---------- 分支 B: UPDATE 变更审计 ----------
        if (commandType == SqlCommandType.UPDATE) {
            // B1: 批量实体更新场景检测（如 updateBatchSomeColumn 或包含 list 入参）
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

            // B2: 单实体更新场景检测（如 updateById）
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

            // B3: 条件更新（update with wrapper，如 restoreById 等按主键或条件更新场景）
            Object wrapperId = extractSingleId(parameter, tableInfo);
            Object beforeEntity = wrapperId != null ? fetchRecordById(executor, ms, tableInfo, wrapperId) : null;

            Object result = invocation.proceed();
            int affectedRows = result instanceof Integer ? (Integer) result : 0;
            if (affectedRows > 0) {
                if (beforeEntity != null && wrapperId != null) {
                    Object afterEntity = fetchRecordById(executor, ms, tableInfo, wrapperId);
                    Map<String, Object> beforeSnap = snapshot(beforeEntity, tableInfo, meta);
                    Map<String, Object> afterSnap = afterEntity != null ? snapshot(afterEntity, tableInfo, meta) : null;
                    List<FieldDiff> diffs = afterEntity != null ? computeDiffs(beforeEntity, afterEntity, tableInfo, meta) : Collections.emptyList();
                    scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), wrapperId, ChangeType.UPDATE, affectedRows, false,
                            meta.getDescription(), diffs, beforeSnap, afterSnap));
                } else {
                    boolean degraded = affectedRows > maxDiffRows;
                    scheduleRecord(buildRecord(tableInfo.getTableName(), meta.getEntityClass(), null, ChangeType.UPDATE, affectedRows, degraded,
                            meta.getDescription(), Collections.emptyList(), null, null));
                }
            }
            return result;
        }

        return invocation.proceed();
    }

    /**
     * 根据主键 ID 在当前事务中抓取前置实体快照（包裹于 LogicDeleteContextHolder 穿透闭包中，支持回收站恢复与物理销毁快照捕获）
     */
    private Object fetchRecordById(Executor executor, MappedStatement ms, TableInfo tableInfo, Object id) {
        if (id == null || tableInfo == null) {
            return null;
        }
        return LogicDeleteContextHolder.callWithDeleted(() -> {
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
        });
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
                taskExecutor.execute(AsyncContext.wrap(task));
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
            AuditChange auditChange = MybatisInterceptorUtil.resolveMapperAnnotation(id, AuditChange.class).orElse(null);

            Class<?> mapperClass = null;
            int lastDot = id.lastIndexOf('.');
            if (lastDot > 0) {
                String className = id.substring(0, lastDot);
                try {
                    mapperClass = Class.forName(className);
                } catch (ClassNotFoundException ignored) {
                }
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
            if (map.containsKey("id") && map.get("id") != null) {
                return map.get("id");
            }
            if (map.containsKey("et") && map.get("et") != null) {
                return tableInfo.getPropertyValue(map.get("et"), tableInfo.getKeyProperty());
            }
            if (map.containsKey("entity") && map.get("entity") != null) {
                return tableInfo.getPropertyValue(map.get("entity"), tableInfo.getKeyProperty());
            }
            if (map.containsKey("ew") && map.get("ew") instanceof AbstractWrapper<?, ?, ?> wrapper) {
                if (wrapper.getEntity() != null) {
                    Object id = tableInfo.getPropertyValue(wrapper.getEntity(), tableInfo.getKeyProperty());
                    if (id != null) {
                        return id;
                    }
                }
                Object wrapperId = extractIdFromWrapper(wrapper, tableInfo);
                if (wrapperId != null) {
                    return wrapperId;
                }
            }
            if (map.containsKey("param1") && map.get("param1") != null && !(map.get("param1") instanceof Collection) && !(map.get("param1") instanceof Wrapper)) {
                Object p1 = map.get("param1");
                if (tableInfo.getEntityType() != null && tableInfo.getEntityType().isInstance(p1)) {
                    return tableInfo.getPropertyValue(p1, tableInfo.getKeyProperty());
                }
                return p1;
            }
        }
        return null;
    }

    private Collection<?> extractBatchIds(Object parameter) {
        if (parameter == null) {
            return null;
        }
        if (parameter instanceof Collection<?> col) {
            return col;
        }
        if (parameter instanceof Map<?, ?> map) {
            if (map.containsKey("coll") && map.get("coll") instanceof Collection<?> col) {
                return col;
            }
            if (map.containsKey("collection") && map.get("collection") instanceof Collection<?> col) {
                return col;
            }
            if (map.containsKey("list") && map.get("list") instanceof Collection<?> col) {
                return col;
            }
            if (map.containsKey("ids") && map.get("ids") instanceof Collection<?> col) {
                return col;
            }
            if (map.containsKey("param1") && map.get("param1") instanceof Collection<?> col) {
                return col;
            }
        }
        return null;
    }

    /**
     * Wrapper 主键匹配正则表达式缓存（避免拦截高频链路反复编译 Pattern）
     */
    private static final ConcurrentHashMap<String, Pattern> WRAPPER_ID_PATTERN_CACHE = new ConcurrentHashMap<>();

    private static Pattern getWrapperIdPattern(String columnOrProperty) {
        return WRAPPER_ID_PATTERN_CACHE.computeIfAbsent(columnOrProperty, key -> {
            String quoted = Pattern.quote(key);
            return Pattern.compile("(?:^|[^a-zA-Z0-9_`])`?" + quoted + "`?\\s*=\\s*#\\{[^}]*paramNameValuePairs\\.([a-zA-Z0-9_]+)\\}", Pattern.CASE_INSENSITIVE);
        });
    }

    private Object extractParamValue(String sql, Map<String, Object> paramMap, String columnOrProperty) {
        if (columnOrProperty == null || columnOrProperty.isBlank()) {
            return null;
        }
        Pattern pattern = getWrapperIdPattern(columnOrProperty);
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            return paramMap.get(matcher.group(1));
        }
        return null;
    }

    private Object extractIdFromWrapper(AbstractWrapper<?, ?, ?> wrapper, TableInfo tableInfo) {
        if (wrapper == null || tableInfo == null || tableInfo.getKeyColumn() == null) {
            return null;
        }
        try {
            String sql = wrapper.getSqlSegment();
            Map<String, Object> paramMap = wrapper.getParamNameValuePairs();
            if (sql != null && paramMap != null && !paramMap.isEmpty()) {
                Object id = extractParamValue(sql, paramMap, tableInfo.getKeyColumn());
                if (id != null) {
                    return id;
                }
                if (tableInfo.getKeyProperty() != null && !tableInfo.getKeyProperty().equalsIgnoreCase(tableInfo.getKeyColumn())) {
                    return extractParamValue(sql, paramMap, tableInfo.getKeyProperty());
                }
            }
        } catch (Throwable t) {
            log.debug("从 Wrapper 提取主键异常 (table: {}): {}", tableInfo.getTableName(), t.getMessage(), t);
        }
        return null;
    }

    private boolean isLogicDelete(MappedStatement ms, TableInfo tableInfo, Object parameter) {
        if (tableInfo == null || !tableInfo.isWithLogicDelete()) {
            return false;
        }
        String msId = ms.getId();
        int lastDot = msId.lastIndexOf('.');
        String methodName = lastDot >= 0 ? msId.substring(lastDot + 1) : msId;
        if (methodName.contains("$")) {
            methodName = methodName.substring(0, methodName.indexOf('$'));
        }
        if (methodName.contains("_mpCount")) {
            methodName = methodName.replace("_mpCount", "");
        }
        if (methodName.startsWith("delete")) {
            return true;
        }

        Object entity = extractSingleEntity(parameter, tableInfo.getEntityType());
        if (entity != null && isUpdatingToDeletedStatus(entity, tableInfo)) {
            return true;
        }

        return false;
    }

    private boolean isUpdatingToDeletedStatus(Object entity, TableInfo tableInfo) {
        if (entity == null || tableInfo == null || !tableInfo.isWithLogicDelete()) {
            return false;
        }
        TableFieldInfo logicField = tableInfo.getLogicDeleteFieldInfo();
        if (logicField == null) {
            return false;
        }
        Object val = tableInfo.getPropertyValue(entity, logicField.getProperty());
        if (val == null) {
            return false;
        }
        String delVal = logicField.getLogicDeleteValue();
        String notDelVal = logicField.getLogicNotDeleteValue();
        Class<?> propType = logicField.getPropertyType();
        if (matchesLogicValue(val, delVal, propType)) {
            return true;
        }
        if (notDelVal != null && !notDelVal.isBlank() && !matchesLogicValue(val, notDelVal, propType)) {
            return true;
        }
        return false;
    }

    private boolean matchesLogicValue(Object val, String expectedStr, Class<?> propertyType) {
        if (val == null || expectedStr == null || expectedStr.isBlank()) {
            return false;
        }
        String clean = expectedStr.trim().replace("'", "").replace("\"", "");
        if ("null".equalsIgnoreCase(clean)) {
            return false;
        }
        if (Integer.class.equals(propertyType) || int.class.equals(propertyType)) {
            try {
                int exp = Integer.parseInt(clean);
                return val instanceof Number num && num.intValue() == exp;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (Long.class.equals(propertyType) || long.class.equals(propertyType)) {
            try {
                long exp = Long.parseLong(clean);
                return val instanceof Number num && num.longValue() == exp;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (Boolean.class.equals(propertyType) || boolean.class.equals(propertyType)) {
            boolean exp = Boolean.parseBoolean(clean) || "1".equals(clean);
            return Objects.equals(val, exp);
        }
        return Objects.equals(val.toString(), clean);
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
