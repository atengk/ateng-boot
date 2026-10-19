package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicLambdaHelper;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicMapperListMethod;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicMapperPageMethod;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 增强型通用业务 Service 接口
 * 继承 MyBatis-Plus IService，扩展支持自定义分批切片的原生高性能批量插入与单表动态查询直出能力
 *
 * @param <T> 实体类型
 * @author Ateng
 * @since 2026-10-16
 */
public interface AtengBaseService<T> extends IService<T> {

    // ==========================================
    // 0. 零实体泄露基础查询 VO 投影门面 (ADR-0010)
    // ==========================================

    /**
     * 根据主键 ID 查询实体并一步到位投影转换为目标强类型 VO（零持久层实体泄露）
     *
     * @param id      主键 ID
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 映射后的强类型 VO 对象，当 id 为 null 或无匹配数据时返回 null
     */
    default <V> V getById(Serializable id, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (id == null) {
            return null;
        }
        T entity = getById(id);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    /**
     * 根据主键 ID 集合批量查询实体并投影转换为目标强类型 VO 列表（零持久层实体泄露）
     * 遵循健壮性与空安全契约：当入参为空或查询无匹配数据时保底返回空集合，严禁返回 null
     *
     * @param idList  主键 ID 集合
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 映射后的强类型 VO 列表（保底空集合，严禁返回 null）
     */
    default <V> List<V> listByIds(Collection<? extends Serializable> idList, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (idList == null || idList.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> entityList = listByIds(idList);
        if (entityList == null || entityList.isEmpty()) {
            return Collections.emptyList();
        }
        return entityList.stream()
                .map(item -> JsonUtil.convertValue(item, voClass))
                .toList();
    }

    /**
     * 根据查询条件 Wrapper 列表查询实体并投影转换为目标强类型 VO 列表（零持久层实体泄露）
     *
     * @param queryWrapper 查询条件构造器
     * @param voClass      目标 VO 类型
     * @param <V>          目标 VO 泛型
     * @return 映射后的强类型 VO 列表（保底空集合，严禁返回 null）
     */
    default <V> List<V> list(Wrapper<T> queryWrapper, Class<V> voClass) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        List<T> entityList = list(queryWrapper);
        if (entityList == null || entityList.isEmpty()) {
            return Collections.emptyList();
        }
        return entityList.stream()
                .map(item -> JsonUtil.convertValue(item, voClass))
                .toList();
    }

    /**
     * 根据查询条件 Wrapper 查询单条记录并投影转换为目标强类型 VO（默认宽松模式，匹配多条返回首条）
     *
     * @param queryWrapper 查询条件构造器
     * @param voClass      目标 VO 类型
     * @param <V>          目标 VO 泛型
     * @return 强类型 VO 对象，无记录时返回 null
     */
    default <V> V getOne(Wrapper<T> queryWrapper, Class<V> voClass) {
        return getOne(queryWrapper, voClass, false);
    }

    /**
     * 根据查询条件 Wrapper 查询单条记录并投影转换为目标强类型 VO（支持严格多记录快速失败控制）
     *
     * @param queryWrapper 查询条件构造器
     * @param voClass      目标 VO 类型
     * @param throwEx      若匹配到多条记录是否抛出 TooManyResultsException 异常
     * @param <V>          目标 VO 泛型
     * @return 强类型 VO 对象，无记录时返回 null
     */
    default <V> V getOne(Wrapper<T> queryWrapper, Class<V> voClass, boolean throwEx) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        T entity = getOne(queryWrapper, throwEx);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    // ==========================================
    // 1. 动态查询与分页投影直出门面
    // ==========================================

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
     * 基于结构化动态查询请求执行单表单条查询并返回实体（支持严格多记录快速失败控制）
     *
     * @param request 动态查询请求
     * @param throwEx 若匹配到多条记录是否抛出 TooManyResultsException 异常（true: 抛出异常; false: 宽容返回首条并警告）
     * @return 实体对象，无匹配记录时返回 null（调用方需前置卫语句防卫）
     */
    default T getOneDynamic(DynamicQueryRequest request, boolean throwEx) {
        if (request == null) {
            return null;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        return getOne(wrapper, throwEx);
    }

    /**
     * 基于结构化动态查询请求执行单表单条查询并返回实体（默认宽松模式，匹配多条返回首条）
     *
     * @param request 动态查询请求
     * @return 实体对象，无匹配记录时返回 null（调用方需前置卫语句防卫）
     */
    default T getOneDynamic(DynamicQueryRequest request) {
        return getOneDynamic(request, false);
    }

    /**
     * 基于结构化动态查询请求执行单表单条查询并投影为目标 VO（支持严格多记录快速失败控制）
     *
     * @param request 动态查询请求
     * @param voClass 目标 VO 类型
     * @param throwEx 若匹配到多条记录是否抛出 TooManyResultsException 异常
     * @param <V>     目标 VO 泛型
     * @return 强类型 VO 对象，无记录时返回 null
     */
    default <V> V getOneDynamic(DynamicQueryRequest request, Class<V> voClass, boolean throwEx) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        T entity = getOneDynamic(request, throwEx);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    /**
     * 基于结构化动态查询请求执行单表单条查询并投影为目标 VO（默认宽松模式，匹配多条返回首条）
     *
     * @param request 动态查询请求
     * @param voClass 目标 VO 类型
     * @param <V>     目标 VO 泛型
     * @return 强类型 VO 对象，无记录时返回 null
     */
    default <V> V getOneDynamic(DynamicQueryRequest request, Class<V> voClass) {
        return getOneDynamic(request, voClass, false);
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并返回实体（支持严格多记录快速失败控制）
     *
     * @param queryDto 查询 DTO 对象
     * @param throwEx  若匹配到多条记录是否抛出 TooManyResultsException 异常
     * @return 实体对象
     */
    default T getOneDynamic(Object queryDto, boolean throwEx) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return getOneDynamic(request, throwEx);
        }
        if (queryDto == null) {
            return null;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        return getOne(wrapper, throwEx);
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并返回实体（默认宽松模式，匹配多条返回首条）
     *
     * @param queryDto 查询 DTO 对象
     * @return 实体对象
     */
    default T getOneDynamic(Object queryDto) {
        return getOneDynamic(queryDto, false);
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并投影为目标 VO（支持严格多记录快速失败控制）
     *
     * @param queryDto 查询 DTO 对象
     * @param voClass  目标 VO 类型
     * @param throwEx  若匹配到多条记录是否抛出 TooManyResultsException 异常
     * @param <V>      目标 VO 泛型
     * @return 强类型 VO 对象
     */
    default <V> V getOneDynamic(Object queryDto, Class<V> voClass, boolean throwEx) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        if (queryDto instanceof DynamicQueryRequest request) {
            return getOneDynamic(request, voClass, throwEx);
        }
        T entity = getOneDynamic(queryDto, throwEx);
        return entity != null ? JsonUtil.convertValue(entity, voClass) : null;
    }

    /**
     * 基于普通查询 DTO 对象执行单表单条查询并投影为目标 VO（默认宽松模式，匹配多条返回首条）
     *
     * @param queryDto 查询 DTO 对象
     * @param voClass  目标 VO 类型
     * @param <V>      目标 VO 泛型
     * @return 强类型 VO 对象
     */
    default <V> V getOneDynamic(Object queryDto, Class<V> voClass) {
        return getOneDynamic(queryDto, voClass, false);
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
     * 基于结构化动态查询请求执行单表存在性判断（物理短路 LIMIT 1）
     *
     * @param request 动态查询请求
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default boolean existsDynamic(DynamicQueryRequest request) {
        if (request == null) {
            return false;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        return getBaseMapper().exists(wrapper);
    }

    /**
     * 基于普通查询 DTO 对象执行单表存在性判断（物理短路 LIMIT 1）
     *
     * @param queryDto 查询 DTO 对象
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default boolean existsDynamic(Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return existsDynamic(request);
        }
        if (queryDto == null) {
            return false;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        return getBaseMapper().exists(wrapper);
    }

    /**
     * 获取受管动态查询模板组件（子类实现可按需注入）
     *
     * @return 动态查询执行模板，未注入时返回 null
     */
    default DynamicQueryTemplate getDynamicQueryTemplate() {
        return null;
    }

    /**
     * 获取受管游标流式查询模板组件（子类实现可按需注入）
     *
     * @return 游标流式查询模板，未注入时返回 null
     */
    default StreamingQueryTemplate getStreamingQueryTemplate() {
        return null;
    }

    // ==========================================
    // 3. 多表联表方法引用与短方法名动态直出
    // ==========================================

    /**
     * 基于自定义 Mapper 分页方法引用执行联表查询，自动自愈 AST 投影表别名并返回强类型 VO 分页结果
     *
     * @param mapperMethod Mapper 分页方法引用（如 baseMapper::selectUserDeptPage）
     * @param request      动态查询请求
     * @param voClass      目标 VO 类型
     * @param <V>          目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(DynamicMapperPageMethod<V> mapperMethod, DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(mapperMethod, "mapperMethod 不能为 null");
        if (request == null) {
            return PageResult.empty();
        }
        String methodName = DynamicLambdaHelper.resolveMethodName(mapperMethod);
        Map<String, String> mapping = null;
        if (methodName != null && !methodName.isEmpty()) {
            DynamicQueryTemplate template = getDynamicQueryTemplate();
            if (template != null) {
                mapping = template.resolveProjections(methodName);
            }
        }

        QueryWrapper<V> wrapper = DynamicQueryHelper.buildWrapper(request, voClass, mapping);
        Page<V> page = PageUtil.toPage(request);
        IPage<V> resultPage = mapperMethod.select(page, wrapper);
        return PageUtil.toPageResult(resultPage);
    }

    /**
     * 基于自定义 Mapper 分页方法引用执行联表查询（泛型自动推断）
     *
     * @param mapperMethod Mapper 分页方法引用（如 baseMapper::selectUserDeptPage）
     * @param request      动态查询请求
     * @param <V>          目标视图或实体泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(DynamicMapperPageMethod<V> mapperMethod, DynamicQueryRequest request) {
        return pageDynamic(mapperMethod, request, null);
    }

    /**
     * 基于自定义 Mapper 分页方法引用与普通查询 DTO 执行联表查询
     *
     * @param mapperMethod Mapper 分页方法引用
     * @param queryDto     业务查询 DTO
     * @param voClass      目标 VO 类型
     * @param <V>          目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(DynamicMapperPageMethod<V> mapperMethod, Object queryDto, Class<V> voClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return pageDynamic(mapperMethod, request, voClass);
        }
        Objects.requireNonNull(mapperMethod, "mapperMethod 不能为 null");
        if (queryDto == null) {
            return PageResult.empty();
        }
        String methodName = DynamicLambdaHelper.resolveMethodName(mapperMethod);
        Map<String, String> mapping = null;
        if (methodName != null && !methodName.isEmpty()) {
            DynamicQueryTemplate template = getDynamicQueryTemplate();
            if (template != null) {
                mapping = template.resolveProjections(methodName);
            }
        }

        QueryWrapper<V> wrapper = (voClass != null)
                ? DynamicQueryHelper.buildWrapper(queryDto, voClass, mapping)
                : DynamicQueryHelper.buildWrapper(queryDto, mapping);
        Page<V> page = (queryDto instanceof PageQuery pq)
                ? PageUtil.toPage(pq)
                : new Page<>(1, 10);
        IPage<V> resultPage = mapperMethod.select(page, wrapper);
        return PageUtil.toPageResult(resultPage);
    }

    /**
     * 基于自定义 Mapper 分页方法引用与普通查询 DTO 执行联表查询（泛型推断）
     *
     * @param mapperMethod Mapper 分页方法引用
     * @param queryDto     业务查询 DTO
     * @param <V>          目标泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(DynamicMapperPageMethod<V> mapperMethod, Object queryDto) {
        return pageDynamic(mapperMethod, queryDto, null);
    }

    /**
     * 基于自定义 Mapper 列表方法引用执行联表查询并返回 VO 集合
     *
     * @param mapperMethod Mapper 列表方法引用
     * @param request      动态查询请求
     * @param voClass      目标 VO 类型
     * @param <V>          目标 VO 泛型
     * @return 列表结果集合
     */
    default <V> List<V> listDynamic(DynamicMapperListMethod<V> mapperMethod, DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(mapperMethod, "mapperMethod 不能为 null");
        if (request == null) {
            return Collections.emptyList();
        }
        String methodName = DynamicLambdaHelper.resolveMethodName(mapperMethod);
        Map<String, String> mapping = null;
        if (methodName != null && !methodName.isEmpty()) {
            DynamicQueryTemplate template = getDynamicQueryTemplate();
            if (template != null) {
                mapping = template.resolveProjections(methodName);
            }
        }

        QueryWrapper<V> wrapper = (voClass != null)
                ? DynamicQueryHelper.buildWrapper(request, voClass, mapping)
                : (mapping != null ? DynamicQueryHelper.buildWrapper(request, mapping) : new QueryWrapper<>());
        List<V> list = mapperMethod.select(wrapper);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 基于自定义 Mapper 列表方法引用执行联表查询
     *
     * @param mapperMethod Mapper 列表方法引用
     * @param request      动态查询请求
     * @param <V>          目标泛型
     * @return 列表结果集合
     */
    default <V> List<V> listDynamic(DynamicMapperListMethod<V> mapperMethod, DynamicQueryRequest request) {
        return listDynamic(mapperMethod, request, null);
    }

    /**
     * 基于自定义 Mapper 分页方法引用执行联表存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param mapperMethod Mapper 分页方法引用（如 baseMapper::selectUserDeptPage）
     * @param request      动态查询请求
     * @param <V>          目标视图或实体泛型
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default <V> boolean existsDynamic(DynamicMapperPageMethod<V> mapperMethod, DynamicQueryRequest request) {
        Objects.requireNonNull(mapperMethod, "mapperMethod 不能为 null");
        if (request == null) {
            return false;
        }
        String methodName = DynamicLambdaHelper.resolveMethodName(mapperMethod);
        Map<String, String> mapping = null;
        if (methodName != null && !methodName.isEmpty()) {
            DynamicQueryTemplate template = getDynamicQueryTemplate();
            if (template != null) {
                mapping = template.resolveProjections(methodName);
            }
        }
        QueryWrapper<V> wrapper = (mapping != null)
                ? DynamicQueryHelper.buildWrapper(request, mapping)
                : DynamicQueryHelper.buildWrapper(request, Collections.emptyMap());
        Page<V> page = new Page<>(1, 1, false);
        IPage<V> resultPage = mapperMethod.select(page, wrapper);
        return resultPage != null && resultPage.getRecords() != null && !resultPage.getRecords().isEmpty();
    }

    /**
     * 基于自定义 Mapper 分页方法引用与普通 DTO 执行联表存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param mapperMethod Mapper 分页方法引用
     * @param queryDto     业务查询 DTO
     * @param <V>          目标视图或实体泛型
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default <V> boolean existsDynamic(DynamicMapperPageMethod<V> mapperMethod, Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return existsDynamic(mapperMethod, request);
        }
        Objects.requireNonNull(mapperMethod, "mapperMethod 不能为 null");
        if (queryDto == null) {
            return false;
        }
        String methodName = DynamicLambdaHelper.resolveMethodName(mapperMethod);
        Map<String, String> mapping = null;
        if (methodName != null && !methodName.isEmpty()) {
            DynamicQueryTemplate template = getDynamicQueryTemplate();
            if (template != null) {
                mapping = template.resolveProjections(methodName);
            }
        }
        QueryWrapper<V> wrapper = (mapping != null)
                ? DynamicQueryHelper.buildWrapper(queryDto, mapping)
                : DynamicQueryHelper.buildWrapper(queryDto, Collections.emptyMap());
        Page<V> page = new Page<>(1, 1, false);
        IPage<V> resultPage = mapperMethod.select(page, wrapper);
        return resultPage != null && resultPage.getRecords() != null && !resultPage.getRecords().isEmpty();
    }

    /**
     * 基于短方法名或全限定 StatementId 执行多表联表分页查询并投影为 VO
     *
     * @param statementName 语句名称（支持短方法名如 "selectUserDeptPage"）
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectPage(statementName, request, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 与普通 DTO 执行多表联表分页查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    default <V> PageResult<V> pageDynamic(String statementName, Object queryDto, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectPage(statementName, queryDto, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 执行多表联表列表查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 列表结果集合
     */
    default <V> List<V> listDynamic(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectList(statementName, request, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 与普通 DTO 执行多表联表列表查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 列表结果集合
     */
    default <V> List<V> listDynamic(String statementName, Object queryDto, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectList(statementName, queryDto, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 执行单条查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return VO 实例，无记录时返回 null
     */
    default <V> V getOneDynamic(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectOne(statementName, request, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 与普通 DTO 执行单条查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return VO 实例，无记录时返回 null
     */
    default <V> V getOneDynamic(String statementName, Object queryDto, Class<V> voClass) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.selectOne(statementName, queryDto, voClass);
    }

    /**
     * 基于短方法名或全限定 StatementId 执行记录数统计
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @return 匹配记录总数
     */
    default long countDynamic(String statementName, DynamicQueryRequest request) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.count(statementName, request);
    }

    /**
     * 基于短方法名或全限定 StatementId 与普通 DTO 执行记录数统计
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @return 匹配记录总数
     */
    default long countDynamic(String statementName, Object queryDto) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.count(statementName, queryDto);
    }

    /**
     * 基于短方法名或全限定 StatementId 执行多表联表存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param statementName 语句名称（支持短方法名如 "selectUserDeptPage"）
     * @param request       动态查询请求
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default boolean existsDynamic(String statementName, DynamicQueryRequest request) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.exists(statementName, request);
    }

    /**
     * 基于短方法名或全限定 StatementId 与普通 DTO 执行多表联表存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    default boolean existsDynamic(String statementName, Object queryDto) {
        DynamicQueryTemplate template = getDynamicQueryTemplate();
        if (template == null) {
            throw new IllegalStateException("当前 Service 未注入 DynamicQueryTemplate 组件，无法通过语句名称执行动态查询");
        }
        return template.exists(statementName, queryDto);
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

    /**
     * 原生分批批量 Upsert（采用全局默认批次大小，受事务守护）
     * 底层自适应 MySQL DUPLICATE KEY 与 PostgreSQL ON CONFLICT 方言
     *
     * @param entityList 待批量 Upsert 的实体数据集合
     * @return 累计成功操作影响的总行数
     */
    int upsertBatch(Collection<T> entityList);

    /**
     * 原生分批批量 Upsert（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功操作影响的总行数
     */
    int upsertBatch(Collection<T> entityList, int batchSize);

    /**
     * 原生分批批量 Upsert 别名门面（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量 Upsert 的实体数据集合
     * @return 累计成功操作影响的总行数
     */
    default int upsertBatchSomeColumn(Collection<T> entityList) {
        return upsertBatch(entityList);
    }

    /**
     * 原生分批批量 Upsert 别名门面（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量 Upsert 的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功操作影响的总行数
     */
    default int upsertBatchSomeColumn(Collection<T> entityList, int batchSize) {
        return upsertBatch(entityList, batchSize);
    }

    /**
     * 原生分批批量更新（采用全局默认批次大小，受事务守护）
     * 自动生成 ANSI CASE WHEN 单条 SQL，并在内存中强制按主键正序排列以从数学机理上杜绝并发死锁
     *
     * @param entityList 待批量更新的实体数据集合
     * @return 累计成功更新影响的总行数
     */
    int updateBatch(Collection<T> entityList);

    /**
     * 原生分批批量更新（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量更新的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功更新影响的总行数
     */
    int updateBatch(Collection<T> entityList, int batchSize);

    /**
     * 原生分批批量更新别名门面（采用全局默认批次大小，受事务守护）
     *
     * @param entityList 待批量更新的实体数据集合
     * @return 累计成功更新影响的总行数
     */
    default int updateBatchSomeColumn(Collection<T> entityList) {
        return updateBatch(entityList);
    }

    /**
     * 原生分批批量更新别名门面（支持自定义单批次切片大小，受事务守护）
     *
     * @param entityList 待批量更新的实体数据集合
     * @param batchSize  单批次切片大小（小于等于 0 时采用默认配置）
     * @return 累计成功更新影响的总行数
     */
    default int updateBatchSomeColumn(Collection<T> entityList, int batchSize) {
        return updateBatch(entityList, batchSize);
    }

    /**
     * 原生单实体冲突更新（Upsert）便捷门面（受事务守护）
     *
     * @param entity 实体对象
     * @return true 若操作成功影响行数大于 0；否则 false
     */
    default boolean upsert(T entity) {
        if (entity == null) {
            return false;
        }
        return upsertBatch(Collections.singletonList(entity), 1) > 0;
    }

    // ==========================================
    // 5. 原生受管游标流式与动态投影查询体系 (SelectCursor Pipeline)
    // ==========================================

    /**
     * 原生受管游标逐条流式查询（单条实体消费）
     * 在受管只读事务中借助 MyBatis 原生 {@link org.apache.ibatis.cursor.Cursor} 逐条读取数据，消费完毕后确定性关闭游标并释放连接
     *
     * @param queryWrapper 查询条件封装器（可为 null）
     * @param consumer     单条记录消费器，不能为 null
     */
    void stream(Wrapper<T> queryWrapper, Consumer<T> consumer);

    /**
     * 原生受管游标逐条流式查询，并一步到位流式投影为强类型 VO
     *
     * @param queryWrapper 查询条件封装器（可为 null）
     * @param voClass      目标 VO 类型，不能为 null
     * @param consumer     VO 消费器，不能为 null
     * @param <V>          目标 VO 泛型
     */
    default <V> void stream(Wrapper<T> queryWrapper, Class<V> voClass, Consumer<V> consumer) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        Objects.requireNonNull(consumer, "Consumer 不能为 null");
        stream(queryWrapper, entity -> consumer.accept(JsonUtil.convertValue(entity, voClass)));
    }

    /**
     * 原生受管游标分批切片流式查询（支持自定义切片批次大小）
     *
     * @param queryWrapper  查询条件封装器（可为 null）
     * @param batchSize     单批次切片大小（小于等于 0 时采用默认配置）
     * @param batchConsumer 分批消费器（入参为只读切片 List），不能为 null
     */
    void streamBatch(Wrapper<T> queryWrapper, int batchSize, Consumer<List<T>> batchConsumer);

    /**
     * 原生受管游标分批切片流式查询（采用全局默认批次大小）
     *
     * @param queryWrapper  查询条件封装器（可为 null）
     * @param batchConsumer 分批消费器，不能为 null
     */
    default void streamBatch(Wrapper<T> queryWrapper, Consumer<List<T>> batchConsumer) {
        streamBatch(queryWrapper, 0, batchConsumer);
    }

    /**
     * 原生受管游标分批切片流式查询，并一步到位流式投影为强类型 VO 切片（支持自定义切片批次大小）
     *
     * @param queryWrapper  查询条件封装器（可为 null）
     * @param voClass       目标 VO 类型，不能为 null
     * @param batchSize     单批次切片大小（小于等于 0 时采用默认配置）
     * @param batchConsumer 分批 VO 消费器，不能为 null
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamBatch(Wrapper<T> queryWrapper, Class<V> voClass, int batchSize, Consumer<List<V>> batchConsumer) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        Objects.requireNonNull(batchConsumer, "Batch consumer 不能为 null");
        streamBatch(queryWrapper, batchSize, entityList -> {
            List<V> voList = entityList.stream()
                    .map(entity -> JsonUtil.convertValue(entity, voClass))
                    .toList();
            batchConsumer.accept(voList);
        });
    }

    /**
     * 原生受管游标分批切片流式查询，并一步到位流式投影为强类型 VO 切片（采用全局默认批次大小）
     *
     * @param queryWrapper  查询条件封装器（可为 null）
     * @param voClass       目标 VO 类型，不能为 null
     * @param batchConsumer 分批 VO 消费器，不能为 null
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamBatch(Wrapper<T> queryWrapper, Class<V> voClass, Consumer<List<V>> batchConsumer) {
        streamBatch(queryWrapper, voClass, 0, batchConsumer);
    }

    /**
     * 原生受管游标条件流式查询（支持 Predicate 提前短路退出）
     * 当 Predicate 返回 true 时继续遍历消费，当返回 false 时提前退出循环并触发 try-with-resources 确定性关闭游标与连接释放
     *
     * @param queryWrapper 查询条件封装器（可为 null）
     * @param predicate    条件断言消费器（返回 true 继续消费，返回 false 提前短路退出），不能为 null
     */
    void streamWhile(Wrapper<T> queryWrapper, Predicate<T> predicate);

    /**
     * 原生受管游标条件流式查询，并一步到位投影为强类型 VO 条件断言（支持 Predicate 提前短路退出）
     *
     * @param queryWrapper 查询条件封装器（可为 null）
     * @param voClass      目标 VO 类型，不能为 null
     * @param predicate    条件断言消费器，不能为 null
     * @param <V>          目标 VO 泛型
     */
    default <V> void streamWhile(Wrapper<T> queryWrapper, Class<V> voClass, Predicate<V> predicate) {
        Objects.requireNonNull(voClass, "目标视图类型 voClass 不能为 null");
        Objects.requireNonNull(predicate, "Predicate 不能为 null");
        streamWhile(queryWrapper, entity -> predicate.test(JsonUtil.convertValue(entity, voClass)));
    }

    /**
     * 基于结构化动态查询请求执行受管游标逐条流式消费
     *
     * @param request  动态查询请求
     * @param consumer 单条实体消费器
     */
    default void streamDynamic(DynamicQueryRequest request, Consumer<T> consumer) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        stream(wrapper, consumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标逐条流式消费，并一步到位流式投影为强类型 VO
     *
     * @param request  动态查询请求
     * @param voClass  目标 VO 类型
     * @param consumer 单条 VO 消费器
     * @param <V>      目标 VO 泛型
     */
    default <V> void streamDynamic(DynamicQueryRequest request, Class<V> voClass, Consumer<V> consumer) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        stream(wrapper, voClass, consumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标逐条流式消费
     *
     * @param queryDto 查询 DTO
     * @param consumer 单条实体消费器
     */
    default void streamDynamic(Object queryDto, Consumer<T> consumer) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamic(request, consumer);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        stream(wrapper, consumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标逐条流式消费，并一步到位流式投影为强类型 VO
     *
     * @param queryDto 查询 DTO
     * @param voClass  目标 VO 类型
     * @param consumer 单条 VO 消费器
     * @param <V>      目标 VO 泛型
     */
    default <V> void streamDynamic(Object queryDto, Class<V> voClass, Consumer<V> consumer) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamic(request, voClass, consumer);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        stream(wrapper, voClass, consumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标分批切片流式消费（支持自定义切片批次大小）
     *
     * @param request       动态查询请求
     * @param batchSize     分批大小
     * @param batchConsumer 分批消费器
     */
    default void streamDynamicBatch(DynamicQueryRequest request, int batchSize, Consumer<List<T>> batchConsumer) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        streamBatch(wrapper, batchSize, batchConsumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标分批切片流式消费（采用全局默认批次大小）
     *
     * @param request       动态查询请求
     * @param batchConsumer 分批消费器
     */
    default void streamDynamicBatch(DynamicQueryRequest request, Consumer<List<T>> batchConsumer) {
        streamDynamicBatch(request, 0, batchConsumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标分批切片流式消费，并一步到位投影为 VO 切片（支持自定义批次）
     *
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param batchSize     分批大小
     * @param batchConsumer 分批 VO 消费器
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamDynamicBatch(DynamicQueryRequest request, Class<V> voClass, int batchSize, Consumer<List<V>> batchConsumer) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        streamBatch(wrapper, voClass, batchSize, batchConsumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标分批切片流式消费，并一步到位投影为 VO 切片（采用全局默认批次大小）
     *
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param batchConsumer 分批 VO 消费器
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamDynamicBatch(DynamicQueryRequest request, Class<V> voClass, Consumer<List<V>> batchConsumer) {
        streamDynamicBatch(request, voClass, 0, batchConsumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标分批切片流式消费（支持自定义切片批次大小）
     *
     * @param queryDto      查询 DTO
     * @param batchSize     分批大小
     * @param batchConsumer 分批消费器
     */
    default void streamDynamicBatch(Object queryDto, int batchSize, Consumer<List<T>> batchConsumer) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamicBatch(request, batchSize, batchConsumer);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        streamBatch(wrapper, batchSize, batchConsumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标分批切片流式消费（采用全局默认批次大小）
     *
     * @param queryDto      查询 DTO
     * @param batchConsumer 分批消费器
     */
    default void streamDynamicBatch(Object queryDto, Consumer<List<T>> batchConsumer) {
        streamDynamicBatch(queryDto, 0, batchConsumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标分批切片流式消费，并一步到位投影为 VO 切片（支持自定义批次）
     *
     * @param queryDto      查询 DTO
     * @param voClass       目标 VO 类型
     * @param batchSize     分批大小
     * @param batchConsumer 分批 VO 消费器
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamDynamicBatch(Object queryDto, Class<V> voClass, int batchSize, Consumer<List<V>> batchConsumer) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamicBatch(request, voClass, batchSize, batchConsumer);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        streamBatch(wrapper, voClass, batchSize, batchConsumer);
    }

    /**
     * 基于普通查询 DTO 执行受管游标分批切片流式消费，并一步到位投影为 VO 切片（采用全局默认批次大小）
     *
     * @param queryDto      查询 DTO
     * @param voClass       目标 VO 类型
     * @param batchConsumer 分批 VO 消费器
     * @param <V>           目标 VO 泛型
     */
    default <V> void streamDynamicBatch(Object queryDto, Class<V> voClass, Consumer<List<V>> batchConsumer) {
        streamDynamicBatch(queryDto, voClass, 0, batchConsumer);
    }

    /**
     * 基于结构化动态查询请求执行受管游标条件流式消费（支持 Predicate 提前短路退出）
     *
     * @param request   动态查询请求
     * @param predicate 条件断言消费器
     */
    default void streamDynamicWhile(DynamicQueryRequest request, Predicate<T> predicate) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        streamWhile(wrapper, predicate);
    }

    /**
     * 基于结构化动态查询请求执行受管游标条件流式消费，并一步到位投影为 VO 断言（支持 Predicate 提前短路退出）
     *
     * @param request   动态查询请求
     * @param voClass   目标 VO 类型
     * @param predicate VO 条件断言消费器
     * @param <V>       目标 VO 泛型
     */
    default <V> void streamDynamicWhile(DynamicQueryRequest request, Class<V> voClass, Predicate<V> predicate) {
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        streamWhile(wrapper, voClass, predicate);
    }

    /**
     * 基于普通查询 DTO 执行受管游标条件流式消费（支持 Predicate 提前短路退出）
     *
     * @param queryDto  查询 DTO
     * @param predicate 条件断言消费器
     */
    default void streamDynamicWhile(Object queryDto, Predicate<T> predicate) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamicWhile(request, predicate);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        streamWhile(wrapper, predicate);
    }

    /**
     * 基于普通查询 DTO 执行受管游标条件流式消费，并一步到位投影为 VO 断言（支持 Predicate 提前短路退出）
     *
     * @param queryDto  查询 DTO
     * @param voClass   目标 VO 类型
     * @param predicate VO 条件断言消费器
     * @param <V>       目标 VO 泛型
     */
    default <V> void streamDynamicWhile(Object queryDto, Class<V> voClass, Predicate<V> predicate) {
        if (queryDto instanceof DynamicQueryRequest request) {
            streamDynamicWhile(request, voClass, predicate);
            return;
        }
        Class<T> entityClass = getEntityClass();
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        streamWhile(wrapper, voClass, predicate);
    }
}
