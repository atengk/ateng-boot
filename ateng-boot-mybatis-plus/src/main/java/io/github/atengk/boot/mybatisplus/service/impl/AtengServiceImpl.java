package io.github.atengk.boot.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.enums.LockMode;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.OptimisticLockTemplate;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 增强型通用业务 Service 实现基类
 * 继承 MyBatis-Plus ServiceImpl 并实现 AtengBaseService，提供声明式事务守护的分片多值原生批量插入、受管游标流式查询与动态查询直出能力
 *
 * @param <M> 扩展了 AtengBaseMapper 的持久层 Mapper 类型
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-10-16
 */
public class AtengServiceImpl<M extends AtengBaseMapper<T>, T> extends ServiceImpl<M, T> implements AtengBaseService<T> {

    private MybatisPlusProperties properties;
    private DynamicQueryTemplate dynamicQueryTemplate;
    private StreamingQueryTemplate streamingQueryTemplate;

    /**
     * 注入持久层配置属性（Setter 注入规避字段反射注入）
     *
     * @param properties 配置属性
     */
    @Autowired(required = false)
    public void setProperties(MybatisPlusProperties properties) {
        this.properties = properties;
    }

    /**
     * 注入动态查询模板组件
     *
     * @param dynamicQueryTemplate 动态查询模板
     */
    @Autowired(required = false)
    public void setDynamicQueryTemplate(DynamicQueryTemplate dynamicQueryTemplate) {
        this.dynamicQueryTemplate = dynamicQueryTemplate;
    }

    @Override
    public DynamicQueryTemplate getDynamicQueryTemplate() {
        return this.dynamicQueryTemplate;
    }

    /**
     * 注入受管游标流式查询模板组件
     *
     * @param streamingQueryTemplate 流式查询模板
     */
    @Autowired(required = false)
    public void setStreamingQueryTemplate(StreamingQueryTemplate streamingQueryTemplate) {
        this.streamingQueryTemplate = streamingQueryTemplate;
    }

    @Override
    public StreamingQueryTemplate getStreamingQueryTemplate() {
        return this.streamingQueryTemplate;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int insertBatchSomeColumn(Collection<T> entityList) {
        return insertBatchSomeColumn(entityList, getDefaultBatchSize());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int insertBatchSomeColumn(Collection<T> entityList, int batchSize) {
        int effectiveSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        return BatchUtil.insertBatch(baseMapper, entityList, effectiveSize);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int insertBatch(Collection<T> entityList) {
        return insertBatchSomeColumn(entityList);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int insertBatch(Collection<T> entityList, int batchSize) {
        return insertBatchSomeColumn(entityList, batchSize);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int upsertBatch(Collection<T> entityList) {
        return upsertBatch(entityList, getDefaultBatchSize());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int upsertBatch(Collection<T> entityList, int batchSize) {
        int effectiveSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        return BatchUtil.upsertBatch(baseMapper, entityList, effectiveSize);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int updateBatch(Collection<T> entityList) {
        return updateBatch(entityList, getDefaultBatchSize());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int updateBatch(Collection<T> entityList, int batchSize) {
        int effectiveSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        return BatchUtil.updateBatch(baseMapper, entityList, effectiveSize);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean upsert(T entity) {
        if (entity == null) {
            return false;
        }
        return upsertBatch(java.util.Collections.singletonList(entity), 1) > 0;
    }

    /**
     * 获取全局默认批量切片大小
     * 优先读取 ateng.boot.mybatis-plus.batch.default-size 配置，未配置时缺省为 1000
     *
     * @return 默认切片批次大小
     */
    protected int getDefaultBatchSize() {
        if (properties != null && properties.getBatch() != null && properties.getBatch().getDefaultSize() > 0) {
            return properties.getBatch().getDefaultSize();
        }
        return BatchUtil.DEFAULT_BATCH_SIZE;
    }

    @Override
    public void stream(Wrapper<T> queryWrapper, Consumer<T> consumer) {
        StreamingQueryTemplate template = getRequiredStreamingQueryTemplate();
        template.forEach(() -> baseMapper.selectCursor(queryWrapper), consumer);
    }

    @Override
    public void streamBatch(Wrapper<T> queryWrapper, int batchSize, Consumer<List<T>> batchConsumer) {
        StreamingQueryTemplate template = getRequiredStreamingQueryTemplate();
        int effectiveBatchSize = batchSize > 0 ? batchSize : getDefaultBatchSize();
        template.forEachBatch(() -> baseMapper.selectCursor(queryWrapper), effectiveBatchSize, batchConsumer);
    }

    @Override
    public void streamWhile(Wrapper<T> queryWrapper, Predicate<T> predicate) {
        StreamingQueryTemplate template = getRequiredStreamingQueryTemplate();
        template.forEachWhile(() -> baseMapper.selectCursor(queryWrapper), predicate);
    }

    /**
     * 获取必需的受管游标流式查询模板组件，未注入时快速失败抛出 IllegalStateException
     *
     * @return 流式查询模板实例
     */
    protected StreamingQueryTemplate getRequiredStreamingQueryTemplate() {
        if (this.streamingQueryTemplate == null) {
            throw new IllegalStateException("当前 Service 未注入 StreamingQueryTemplate 组件，无法执行流式游标查询");
        }
        return this.streamingQueryTemplate;
    }

    @Override
    public T updateWithOptimisticRetry(Serializable id, Consumer<T> updater) {
        return updateWithOptimisticRetry(id, updater, OptimisticLockTemplate.DEFAULT_MAX_ATTEMPTS, OptimisticLockTemplate.DEFAULT_INITIAL_DELAY);
    }

    @Override
    public T updateWithOptimisticRetry(Serializable id, Consumer<T> updater, int maxAttempts, Duration delay) {
        Objects.requireNonNull(id, "实体主键 id 不能为 null");
        Objects.requireNonNull(updater, "实体修改函数 updater 不能为 null");
        return OptimisticLockTemplate.execute(
                () -> getById(id),
                entity -> {
                    updater.accept(entity);
                    return updateById(entity);
                },
                maxAttempts,
                delay
        );
    }

    @Override
    public T getByIdForUpdate(Serializable id, LockMode lockMode) {
        if (id == null) {
            return null;
        }
        Objects.requireNonNull(lockMode, "lockMode 不能为 null");
        TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
        String keyColumn = (tableInfo != null && tableInfo.getKeyColumn() != null)
                ? tableInfo.getKeyColumn()
                : "id";
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        wrapper.eq(keyColumn, id);
        PessimisticLockUtil.apply(wrapper, lockMode);
        return getOne(wrapper, false);
    }

    @Override
    public List<T> listForUpdate(Wrapper<T> queryWrapper, LockMode lockMode) {
        Objects.requireNonNull(lockMode, "lockMode 不能为 null");
        final Wrapper<T> effectiveWrapper;
        if (queryWrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper) {
            PessimisticLockUtil.apply(abstractWrapper, lockMode);
            effectiveWrapper = queryWrapper;
        } else if (queryWrapper == null) {
            QueryWrapper<T> wrapper = new QueryWrapper<>();
            PessimisticLockUtil.apply(wrapper, lockMode);
            effectiveWrapper = wrapper;
        } else {
            throw new IllegalArgumentException("queryWrapper 必须为 AbstractWrapper 的子类以支持悲观锁追加");
        }
        List<T> list = list(effectiveWrapper);
        return list != null ? list : java.util.Collections.emptyList();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean restoreById(Serializable id) {
        if (id == null) {
            return false;
        }
        TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
        if (tableInfo == null) {
            throw new IllegalStateException("未能解析到实体 [" + getEntityClass().getName() + "] 的 TableInfo 元数据");
        }
        if (!tableInfo.isWithLogicDelete()) {
            throw new UnsupportedOperationException("实体 [" + getEntityClass().getName() + "] 未配置 @TableLogic 逻辑删除，无法执行 restoreById 操作");
        }

        String keyColumn = tableInfo.getKeyColumn();
        TableFieldInfo logicField = tableInfo.getLogicDeleteFieldInfo();
        Object notDeleteValue = parseLogicValue(logicField.getLogicNotDeleteValue(), logicField.getPropertyType());

        UpdateWrapper<T> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq(keyColumn, id);
        updateWrapper.set(logicField.getColumn(), notDeleteValue);
        applyAuditFieldsOnRestore(updateWrapper, tableInfo);

        return LogicDeleteContextHolder.callWithDeleted(() -> baseMapper.update(null, updateWrapper) > 0);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int restore(Wrapper<T> queryWrapper) {
        TableInfo tableInfo = TableInfoHelper.getTableInfo(getEntityClass());
        if (tableInfo == null) {
            throw new IllegalStateException("未能解析到实体 [" + getEntityClass().getName() + "] 的 TableInfo 元数据");
        }
        if (!tableInfo.isWithLogicDelete()) {
            throw new UnsupportedOperationException("实体 [" + getEntityClass().getName() + "] 未配置 @TableLogic 逻辑删除，无法执行 restore 操作");
        }

        TableFieldInfo logicField = tableInfo.getLogicDeleteFieldInfo();
        Object notDeleteValue = parseLogicValue(logicField.getLogicNotDeleteValue(), logicField.getPropertyType());

        final UpdateWrapper<T> updateWrapper;
        if (queryWrapper instanceof UpdateWrapper<T> uw) {
            updateWrapper = uw;
        } else {
            updateWrapper = new UpdateWrapper<>();
            if (queryWrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper) {
                String customSql = abstractWrapper.getCustomSqlSegment();
                if (customSql != null && !customSql.isBlank()) {
                    String whereSql = customSql.replaceFirst("^(?i)\\s*WHERE\\s+", "");
                    updateWrapper.apply(whereSql);
                }
            }
        }
        updateWrapper.set(logicField.getColumn(), notDeleteValue);
        applyAuditFieldsOnRestore(updateWrapper, tableInfo);

        return LogicDeleteContextHolder.callWithDeleted(() -> baseMapper.update(null, updateWrapper));
    }

    private void applyAuditFieldsOnRestore(UpdateWrapper<T> updateWrapper, TableInfo tableInfo) {
        LocalDateTime now = LocalDateTime.now();
        for (TableFieldInfo fieldInfo : tableInfo.getFieldList()) {
            String prop = fieldInfo.getProperty();
            if ("updateTime".equals(prop)) {
                updateWrapper.set(fieldInfo.getColumn(), now);
            } else if ("updateBy".equals(prop)) {
                String operator = resolveCurrentOperator();
                updateWrapper.set(fieldInfo.getColumn(), operator);
            }
        }
    }

    private Object parseLogicValue(String valueStr, Class<?> propertyType) {
        if (valueStr == null || valueStr.isBlank() || "null".equalsIgnoreCase(valueStr.trim())) {
            return null;
        }
        String clean = valueStr.trim().replace("'", "").replace("\"", "");
        if (Integer.class.equals(propertyType) || int.class.equals(propertyType)) {
            return Integer.parseInt(clean);
        }
        if (Long.class.equals(propertyType) || long.class.equals(propertyType)) {
            return Long.parseLong(clean);
        }
        if (Boolean.class.equals(propertyType) || boolean.class.equals(propertyType)) {
            return Boolean.parseBoolean(clean) || "1".equals(clean);
        }
        return clean;
    }

    private String resolveCurrentOperator() {
        String user = UserContextHolder.get();
        if (user != null && !user.isBlank()) {
            return user;
        }
        if (properties != null && properties.getAudit() != null && properties.getAudit().getDefaultOperator() != null) {
            return properties.getAudit().getDefaultOperator();
        }
        return "SYSTEM";
    }
}
