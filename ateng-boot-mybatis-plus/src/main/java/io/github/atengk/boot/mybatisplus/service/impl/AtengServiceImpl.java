package io.github.atengk.boot.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
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
}
