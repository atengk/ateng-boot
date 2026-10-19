package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.util.PageUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 增强型通用业务 Service 接口
 * 继承 MyBatis-Plus IService，扩展支持自定义分批切片的原生高性能批量插入与单表动态查询直出能力
 *
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-10-16
 */
public interface AtengBaseService<T> extends IService<T> {

    /**
     * 基于结构化动态查询请求执行单表分页查询，并直接返回强类型实体分页结果
     *
     * @param request 动态查询请求（承载过滤条件、排序项及分页参数）
     * @return 标准传输层实体分页结果（保底空分页，严禁返回 null）
     */
    default PageResult<T> pageDynamic(DynamicQueryRequest request) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        Page<T> page = PageUtil.toPage(request, entityClass);
        return PageUtil.toPageResult(page(page, wrapper));
    }

    /**
     * 基于结构化动态查询请求执行单表分页查询，并一步到位流式投影为目标强类型 VO 分页结果
     * 符合 ADR-0010 传输层契约解耦与零持久层实体泄露原则
     *
     * @param request 动态查询请求
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 标准传输层强类型 VO 分页结果
     */
    default <V> PageResult<V> pageDynamic(DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        PageResult<T> entityPage = pageDynamic(request);
        return entityPage.map(item -> JsonUtil.convertValue(item, voClass));
    }

    /**
     * 基于普通查询 DTO 对象（支持 @QueryField 注解或普通属性）执行单表分页查询并返回实体分页结果
     *
     * @param queryDto 查询 DTO 对象（若继承 PageQuery 则自动承载分页与排序）
     * @return 标准传输层实体分页结果
     */
    default PageResult<T> pageDynamic(Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return pageDynamic(request);
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        PageQuery pageQuery = (queryDto instanceof PageQuery pq) ? pq : null;
        Page<T> page = PageUtil.toPage(pageQuery, entityClass);
        return PageUtil.toPageResult(page(page, wrapper));
    }

    /**
     * 基于普通查询 DTO 对象执行单表分页查询，并一步到位流式投影为目标强类型 VO 分页结果
     *
     * @param queryDto 查询 DTO 对象
     * @param voClass  目标 VO 类型
     * @param <V>      目标 VO 泛型
     * @return 标准传输层强类型 VO 分页结果
     */
    default <V> PageResult<V> pageDynamic(Object queryDto, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (queryDto instanceof DynamicQueryRequest request) {
            return pageDynamic(request, voClass);
        }
        PageResult<T> entityPage = pageDynamic(queryDto);
        return entityPage.map(item -> JsonUtil.convertValue(item, voClass));
    }

    /**
     * 基于结构化动态查询请求执行单表列表查询并返回实体集合
     *
     * @param request 动态查询请求
     * @return 实体列表（无匹配时保底返回空 List，严禁返回 null）
     */
    default List<T> listDynamic(DynamicQueryRequest request) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        List<T> result = list(wrapper);
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 基于结构化动态查询请求执行单表列表查询，并一步到位流式投影为目标强类型 VO 列表
     *
     * @param request 动态查询请求
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 强类型 VO 列表（无匹配时保底返回空 List，严禁返回 null）
     */
    default <V> List<V> listDynamic(DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        List<T> entityList = listDynamic(request);
        if (entityList.isEmpty()) {
            return Collections.emptyList();
        }
        return entityList.stream()
                .map(item -> JsonUtil.convertValue(item, voClass))
                .toList();
    }

    /**
     * 基于普通查询 DTO 对象执行单表列表查询并返回实体集合
     *
     * @param queryDto 查询 DTO 对象
     * @return 实体列表
     */
    default List<T> listDynamic(Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return listDynamic(request);
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        List<T> result = list(wrapper);
        return result != null ? result : Collections.emptyList();
    }

    /**
     * 基于普通查询 DTO 对象执行单表列表查询并投影为 VO 集合
     *
     * @param queryDto 查询 DTO 对象
     * @param voClass  目标 VO 类型
     * @param <V>      目标 VO 泛型
     * @return 强类型 VO 列表
     */
    default <V> List<V> listDynamic(Object queryDto, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (queryDto instanceof DynamicQueryRequest request) {
            return listDynamic(request, voClass);
        }
        List<T> entityList = listDynamic(queryDto);
        if (entityList.isEmpty()) {
            return Collections.emptyList();
        }
        return entityList.stream()
                .map(item -> JsonUtil.convertValue(item, voClass))
                .toList();
    }

    /**
     * 基于结构化动态查询请求执行单表单条查询并返回实体
     *
     * @param request 动态查询请求
     * @return 实体对象，无匹配记录时返回 null（调用方需前置卫语句防卫）
     */
    default T getOneDynamic(DynamicQueryRequest request) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        return getOne(wrapper, false);
    }

    /**
     * 基于结构化动态查询请求执行单表单条查询并投影为目标 VO
     *
     * @param request 动态查询请求
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 强类型 VO 对象，无记录时返回 null
     */
    default <V> V getOneDynamic(DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        T entity = getOneDynamic(request);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并返回实体
     *
     * @param queryDto 查询 DTO 对象
     * @return 实体对象
     */
    default T getOneDynamic(Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return getOneDynamic(request);
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        return getOne(wrapper, false);
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并投影为目标 VO
     *
     * @param queryDto 查询 DTO 对象
     * @param voClass  目标 VO 类型
     * @param <V>      目标 VO 泛型
     * @return 强类型 VO 对象
     */
    default <V> V getOneDynamic(Object queryDto, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (queryDto instanceof DynamicQueryRequest request) {
            return getOneDynamic(request, voClass);
        }
        T entity = getOneDynamic(queryDto);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    /**
     * 基于结构化动态查询请求执行满足条件的记录数统计
     *
     * @param request 动态查询请求
     * @return 满足条件的匹配记录总数
     */
    default long countDynamic(DynamicQueryRequest request) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        return count(wrapper);
    }

    /**
     * 基于普通查询 DTO 对象执行满足条件的记录数统计
     *
     * @param queryDto 查询 DTO 对象
     * @return 满足条件的匹配记录总数
     */
    default long countDynamic(Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return countDynamic(request);
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        return count(wrapper);
    }

    /**
     * 原生分批批量插入（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @return 累计成功插入影响的总行数
     */
    int insertBatchSomeColumn(Collection<T> entityList);

    /**
     * 原生分批批量插入（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功插入影响的总行数
     */
     int insertBatchSomeColumn(Collection<T> entityList, int batchSize);

    /**
     * 原生分批批量插入便捷门面（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @return 累计成功插入影响的总行数
     */
    default int insertBatch(Collection<T> entityList) {
        return insertBatchSomeColumn(entityList);
    }

    /**
     * 原生分批批量插入便捷门面（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量插入的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功插入影响的总行数
     */
    default int insertBatch(Collection<T> entityList, int batchSize) {
        return insertBatchSomeColumn(entityList, batchSize);
    }
}
