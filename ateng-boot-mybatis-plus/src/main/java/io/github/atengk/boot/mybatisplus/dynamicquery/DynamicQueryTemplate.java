package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;
import org.mybatis.spring.SqlSessionTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用受管动态查询执行模板组件
 * 作为 Spring 单例 Bean 注入持久层容器，提供跨单表实体、Mapper XML 语句或外部动态 SQL 的全套强类型动态条件直出 API，
 * 内建物理分页、多表 AST 投影别名自愈、${ew.customSqlSegment} 原生绑定与 JSqlParser AST 自适应条件追加。
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Slf4j
public class DynamicQueryTemplate {

    private final SqlSessionTemplate sqlSessionTemplate;
    private final DynamicSqlRegistry dynamicSqlRegistry;

    /**
     * StatementId 对应的原始 SQL 文本缓存
     */
    private final Map<String, String> statementSqlCache = new ConcurrentHashMap<>();

    /**
     * StatementId 对应的 AST 投影映射字典缓存
     */
    private final Map<String, Map<String, String>> projectionCache = new ConcurrentHashMap<>();

    /**
     * 实体类型对应的 Mapper 实例缓存
     */
    private final Map<Class<?>, BaseMapper<?>> entityMapperCache = new ConcurrentHashMap<>();

    /**
     * MappedStatements 反射字段句柄（用于解除 StrictMap 冲突安全注入自适应 Statement）
     */
    private final Field mappedStatementsField;

    public DynamicQueryTemplate(SqlSessionTemplate sqlSessionTemplate) {
        this(sqlSessionTemplate, null);
    }

    public DynamicQueryTemplate(SqlSessionTemplate sqlSessionTemplate, DynamicSqlRegistry dynamicSqlRegistry) {
        this.sqlSessionTemplate = Objects.requireNonNull(sqlSessionTemplate, "SqlSessionTemplate 不能为 null");
        this.dynamicSqlRegistry = dynamicSqlRegistry;
        this.mappedStatementsField = resolveMappedStatementsField(sqlSessionTemplate.getConfiguration());
        if (dynamicSqlRegistry != null) {
            dynamicSqlRegistry.addStatementChangeListener(this::invalidateCache);
        }
    }

    public SqlSessionTemplate getSqlSessionTemplate() {
        return sqlSessionTemplate;
    }

    public DynamicSqlRegistry getDynamicSqlRegistry() {
        return dynamicSqlRegistry;
    }

    // ==========================================
    // 1. 基于 StatementId 的多表 XML / 外部 SQL 查询
    // ==========================================

    /**
     * 基于 StatementId 与结构化请求执行多表联表分页查询并投影为 VO
     *
     * @param statementName 语句名称（支持短方法名或全限定 statementId）
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    public <V> PageResult<V> selectPage(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        Objects.requireNonNull(voClass, "目标类型 voClass 不能为 null");
        if (request == null) {
            return PageResult.empty();
        }

        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<V> wrapper = DynamicQueryHelper.buildWrapper(request, voClass, mapping);

        Page<V> page = PageUtil.toPage(request);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> rawList = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        List<V> voList = convertList(rawList, voClass);
        page.setRecords(voList);
        return PageUtil.toPageResult(page);
    }

    /**
     * 基于 StatementId 与普通查询 DTO 执行多表联表分页查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    public <V> PageResult<V> selectPage(String statementName, Object queryDto, Class<V> voClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectPage(statementName, request, voClass);
        }
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        Objects.requireNonNull(voClass, "目标类型 voClass 不能为 null");
        if (queryDto == null) {
            return PageResult.empty();
        }

        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<V> wrapper = DynamicQueryHelper.buildWrapper(queryDto, voClass, mapping);

        Page<V> page = (queryDto instanceof io.github.atengk.boot.core.model.PageQuery pq)
                ? PageUtil.toPage(pq)
                : new Page<>(1, 10);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> rawList = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        List<V> voList = convertList(rawList, voClass);
        page.setRecords(voList);
        return PageUtil.toPageResult(page);
    }

    /**
     * 基于 StatementId 与结构化请求执行列表查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 列表结果，无记录时返回空集合
     */
    public <V> List<V> selectList(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        Objects.requireNonNull(voClass, "目标类型 voClass 不能为 null");
        if (request == null) {
            return Collections.emptyList();
        }

        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<V> wrapper = DynamicQueryHelper.buildWrapper(request, voClass, mapping);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> rawList = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        return convertList(rawList, voClass);
    }

    /**
     * 基于 StatementId 与普通查询 DTO 执行列表查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 列表结果
     */
    public <V> List<V> selectList(String statementName, Object queryDto, Class<V> voClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectList(statementName, request, voClass);
        }
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        Objects.requireNonNull(voClass, "目标类型 voClass 不能为 null");
        if (queryDto == null) {
            return Collections.emptyList();
        }

        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<V> wrapper = DynamicQueryHelper.buildWrapper(queryDto, voClass, mapping);

        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> rawList = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        return convertList(rawList, voClass);
    }

    /**
     * 基于 StatementId 执行单条查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 映射后的强类型 VO，无记录时返回 null
     */
    public <V> V selectOne(String statementName, DynamicQueryRequest request, Class<V> voClass) {
        List<V> list = selectList(statementName, request, voClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于 StatementId 与 DTO 执行单条查询并投影为 VO
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @param voClass       目标 VO 类型
     * @param <V>           目标 VO 泛型
     * @return 映射后的强类型 VO，无记录时返回 null
     */
    public <V> V selectOne(String statementName, Object queryDto, Class<V> voClass) {
        List<V> list = selectList(statementName, queryDto, voClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于 StatementId 执行记录数统计
     *
     * @param statementName 语句名称
     * @param request       动态查询请求
     * @return 匹配记录总数
     */
    public long count(String statementName, DynamicQueryRequest request) {
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        if (request == null) {
            return 0L;
        }
        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<?> wrapper = DynamicQueryHelper.buildWrapper(request, mapping);

        Page<?> page = new Page<>(1, 1);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> list = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        if (page.getTotal() > 0) {
            return page.getTotal();
        }
        return list != null ? list.size() : 0L;
    }

    /**
     * 基于 StatementId 与 DTO 执行记录数统计
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @return 匹配记录总数
     */
    public long count(String statementName, Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return count(statementName, request);
        }
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        if (queryDto == null) {
            return 0L;
        }
        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<?> wrapper = DynamicQueryHelper.buildWrapper(queryDto, mapping);

        Page<?> page = new Page<>(1, 1);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> list = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        if (page.getTotal() > 0) {
            return page.getTotal();
        }
        return list != null ? list.size() : 0L;
    }

    /**
     * 基于 StatementId 与结构化请求执行存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param statementName 语句名称（支持短方法名或全限定 statementId）
     * @param request       动态查询请求
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    public boolean exists(String statementName, DynamicQueryRequest request) {
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        if (request == null) {
            return false;
        }
        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<?> wrapper = DynamicQueryHelper.buildWrapper(request, mapping);

        Page<?> page = new Page<>(1, 1, false);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> list = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        return list != null && !list.isEmpty();
    }

    /**
     * 基于 StatementId 与普通查询 DTO 执行存在性判断（基于单页 Page(1, 1, false) 实施物理短路）
     *
     * @param statementName 语句名称
     * @param queryDto      业务查询 DTO
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    public boolean exists(String statementName, Object queryDto) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return exists(statementName, request);
        }
        Objects.requireNonNull(statementName, "statementName 不能为 null");
        if (queryDto == null) {
            return false;
        }
        String statementId = findStatementId(statementName);
        Map<String, String> mapping = resolveProjections(statementId);
        QueryWrapper<?> wrapper = DynamicQueryHelper.buildWrapper(queryDto, mapping);

        Page<?> page = new Page<>(1, 1, false);
        Map<String, Object> paramMap = new HashMap<>();
        paramMap.put("page", page);
        paramMap.put(Constants.WRAPPER, wrapper);
        paramMap.put("ew", wrapper);

        String targetStatementId = prepareTargetStatement(statementId);
        List<?> list = sqlSessionTemplate.selectList(targetStatementId, paramMap);
        return list != null && !list.isEmpty();
    }

    // ==========================================
    // 2. 基于单表实体类型的直出查询
    // ==========================================

    /**
     * 基于单表实体类型执行动态分页查询并强类型投影为 VO
     *
     * @param request     动态查询请求
     * @param entityClass 目标持久化实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    @SuppressWarnings("unchecked")
    public <V> PageResult<V> selectPage(DynamicQueryRequest request, Class<?> entityClass, Class<V> voClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        Objects.requireNonNull(voClass, "voClass 不能为 null");
        if (request == null) {
            return PageResult.empty();
        }
        BaseMapper<Object> mapper = getEntityMapper((Class<Object>) entityClass);
        QueryWrapper<Object> wrapper = DynamicQueryHelper.buildWrapper(request, (Class<Object>) entityClass);
        Page<Object> page = PageUtil.toPage(request);
        IPage<Object> resultPage = mapper.selectPage(page, wrapper);
        return PageUtil.toPageResult(resultPage, item -> JsonUtil.convertValue(item, voClass));
    }

    /**
     * 基于单表实体类型执行动态分页查询并直接返回实体分页结果
     *
     * @param request     动态查询请求
     * @param entityClass 目标持久化实体类型
     * @param <T>         实体泛型
     * @return 实体分页结果 PageResult<T>
     */
    public <T> PageResult<T> selectPage(DynamicQueryRequest request, Class<T> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (request == null) {
            return PageResult.empty();
        }
        BaseMapper<T> mapper = getEntityMapper(entityClass);
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        Page<T> page = PageUtil.toPage(request);
        IPage<T> resultPage = mapper.selectPage(page, wrapper);
        return PageUtil.toPageResult(resultPage);
    }

    /**
     * 基于单表实体类型与 DTO 执行动态分页查询并投影为 VO
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标持久化实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return 分页结果 PageResult<V>
     */
    @SuppressWarnings("unchecked")
    public <V> PageResult<V> selectPage(Object queryDto, Class<?> entityClass, Class<V> voClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectPage(request, entityClass, voClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        Objects.requireNonNull(voClass, "voClass 不能为 null");
        if (queryDto == null) {
            return PageResult.empty();
        }
        BaseMapper<Object> mapper = getEntityMapper((Class<Object>) entityClass);
        QueryWrapper<Object> wrapper = DynamicQueryHelper.buildWrapper(queryDto, (Class<Object>) entityClass);
        Page<Object> page = (queryDto instanceof io.github.atengk.boot.core.model.PageQuery pq)
                ? PageUtil.toPage(pq)
                : new Page<>(1, 10);
        IPage<Object> resultPage = mapper.selectPage(page, wrapper);
        return PageUtil.toPageResult(resultPage, item -> JsonUtil.convertValue(item, voClass));
    }

    /**
     * 基于单表实体类型与 DTO 执行动态分页查询并直接返回实体
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标持久化实体类型
     * @param <T>         实体泛型
     * @return 实体分页结果 PageResult<T>
     */
    public <T> PageResult<T> selectPage(Object queryDto, Class<T> entityClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectPage(request, entityClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (queryDto == null) {
            return PageResult.empty();
        }
        BaseMapper<T> mapper = getEntityMapper(entityClass);
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        Page<T> page = (queryDto instanceof io.github.atengk.boot.core.model.PageQuery pq)
                ? PageUtil.toPage(pq)
                : new Page<>(1, 10);
        IPage<T> resultPage = mapper.selectPage(page, wrapper);
        return PageUtil.toPageResult(resultPage);
    }

    /**
     * 基于单表实体类型执行动态列表查询并投影为 VO
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return 列表结果
     */
    @SuppressWarnings("unchecked")
    public <V> List<V> selectList(DynamicQueryRequest request, Class<?> entityClass, Class<V> voClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        Objects.requireNonNull(voClass, "voClass 不能为 null");
        if (request == null) {
            return Collections.emptyList();
        }
        BaseMapper<Object> mapper = getEntityMapper((Class<Object>) entityClass);
        QueryWrapper<Object> wrapper = DynamicQueryHelper.buildWrapper(request, (Class<Object>) entityClass);
        List<Object> list = mapper.selectList(wrapper);
        return convertList(list, voClass);
    }

    /**
     * 基于单表实体类型执行动态列表查询并返回实体集合
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 实体列表
     */
    public <T> List<T> selectList(DynamicQueryRequest request, Class<T> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (request == null) {
            return Collections.emptyList();
        }
        BaseMapper<T> mapper = getEntityMapper(entityClass);
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(request, entityClass);
        List<T> list = mapper.selectList(wrapper);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 基于单表实体类型与 DTO 执行动态列表查询并投影为 VO
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return 列表结果
     */
    @SuppressWarnings("unchecked")
    public <V> List<V> selectList(Object queryDto, Class<?> entityClass, Class<V> voClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectList(request, entityClass, voClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        Objects.requireNonNull(voClass, "voClass 不能为 null");
        if (queryDto == null) {
            return Collections.emptyList();
        }
        BaseMapper<Object> mapper = getEntityMapper((Class<Object>) entityClass);
        QueryWrapper<Object> wrapper = DynamicQueryHelper.buildWrapper(queryDto, (Class<Object>) entityClass);
        List<Object> list = mapper.selectList(wrapper);
        return convertList(list, voClass);
    }

    /**
     * 基于单表实体类型与 DTO 执行动态列表查询并返回实体集合
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 实体列表
     */
    public <T> List<T> selectList(Object queryDto, Class<T> entityClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return selectList(request, entityClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (queryDto == null) {
            return Collections.emptyList();
        }
        BaseMapper<T> mapper = getEntityMapper(entityClass);
        QueryWrapper<T> wrapper = DynamicQueryHelper.buildWrapper(queryDto, entityClass);
        List<T> list = mapper.selectList(wrapper);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 基于单表实体类型执行单条查询并投影为 VO
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return VO 实例，无记录时返回 null
     */
    public <V> V selectOne(DynamicQueryRequest request, Class<?> entityClass, Class<V> voClass) {
        List<V> list = selectList(request, entityClass, voClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于单表实体类型执行单条查询并返回实体
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 实体实例，无记录时返回 null
     */
    public <T> T selectOne(DynamicQueryRequest request, Class<T> entityClass) {
        List<T> list = selectList(request, entityClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于单表实体类型与 DTO 执行单条查询并投影为 VO
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标实体类型
     * @param voClass     目标 VO 类型
     * @param <V>         目标 VO 泛型
     * @return VO 实例，无记录时返回 null
     */
    public <V> V selectOne(Object queryDto, Class<?> entityClass, Class<V> voClass) {
        List<V> list = selectList(queryDto, entityClass, voClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于单表实体类型与 DTO 执行单条查询并返回实体
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标实体类型
     * @param <T>         实体泛型
     * @return 实体实例，无记录时返回 null
     */
    public <T> T selectOne(Object queryDto, Class<T> entityClass) {
        List<T> list = selectList(queryDto, entityClass);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    /**
     * 基于单表实体类型执行满足条件的记录数统计
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @return 匹配记录总数
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public long count(DynamicQueryRequest request, Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        BaseMapper mapper = getEntityMapper(entityClass);
        QueryWrapper wrapper = DynamicQueryHelper.buildWrapper(request, (Class) entityClass);
        Long count = mapper.selectCount((Wrapper) wrapper);
        return count != null ? count : 0L;
    }

    /**
     * 基于单表实体类型与 DTO 执行满足条件的记录数统计
     *
     * @param queryDto    查询 DTO 对象
     * @param entityClass 目标实体类型
     * @return 匹配记录总数
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public long count(Object queryDto, Class<?> entityClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return count(request, entityClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        BaseMapper mapper = getEntityMapper(entityClass);
        QueryWrapper wrapper = DynamicQueryHelper.buildWrapper(queryDto, (Class) entityClass);
        Long count = mapper.selectCount((Wrapper) wrapper);
        return count != null ? count : 0L;
    }

    /**
     * 基于单表实体类型与结构化请求执行存在性判断（物理短路 LIMIT 1）
     *
     * @param request     动态查询请求
     * @param entityClass 目标实体类型
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean exists(DynamicQueryRequest request, Class<?> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (request == null) {
            return false;
        }
        BaseMapper mapper = getEntityMapper(entityClass);
        QueryWrapper wrapper = DynamicQueryHelper.buildWrapper(request, (Class) entityClass);
        return mapper.exists((Wrapper) wrapper);
    }

    /**
     * 基于单表实体类型与普通查询 DTO 执行存在性判断（物理短路 LIMIT 1）
     *
     * @param queryDto    业务查询 DTO
     * @param entityClass 目标实体类型
     * @return 若存在至少一条满足条件的记录则返回 true，否则返回 false
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean exists(Object queryDto, Class<?> entityClass) {
        if (queryDto instanceof DynamicQueryRequest request) {
            return exists(request, entityClass);
        }
        Objects.requireNonNull(entityClass, "entityClass 不能为 null");
        if (queryDto == null) {
            return false;
        }
        BaseMapper mapper = getEntityMapper(entityClass);
        QueryWrapper wrapper = DynamicQueryHelper.buildWrapper(queryDto, (Class) entityClass);
        return mapper.exists((Wrapper) wrapper);
    }

    // ==========================================
    // 3. 核心辅助方法与自适应 Statement 处理
    // ==========================================

    /**
     * 解析目标 StatementId 的多表 AST 投影映射字典
     *
     * @param statementId MyBatis 语句唯一标识
     * @return 投影字典（属性名 -> 物理表别名/列名）
     */
    public Map<String, String> resolveProjections(String statementId) {
        if (statementId == null || statementId.trim().isEmpty()) {
            return Collections.emptyMap();
        }
        return projectionCache.computeIfAbsent(statementId.trim(), id -> {
            if (dynamicSqlRegistry != null) {
                io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition def = dynamicSqlRegistry.getDefinition(id);
                if (def != null && def.getProjections() != null && !def.getProjections().isEmpty()) {
                    return def.getProjections();
                }
            }
            String sql = resolveStatementSql(id);
            if (sql == null || sql.trim().isEmpty()) {
                return Collections.emptyMap();
            }
            return DynamicSqlProjectionResolver.resolveProjections(id, sql);
        });
    }

    /**
     * 清理目标 StatementId 的本地 AST 投影与 SQL 文本缓存（供动态脚本热加载或卸载联动）
     *
     * @param statementId 语句唯一标识
     */
    public void invalidateCache(String statementId) {
        if (statementId != null && !statementId.trim().isEmpty()) {
            String key = statementId.trim();
            projectionCache.remove(key);
            statementSqlCache.remove(key);
            DynamicSqlProjectionResolver.invalidate(key);
            safelyRemoveStatement(key + "$dynamicAdaptive");
            if (key.contains(".")) {
                String shortKey = key.substring(key.lastIndexOf('.') + 1);
                projectionCache.remove(shortKey);
                statementSqlCache.remove(shortKey);
                DynamicSqlProjectionResolver.invalidate(shortKey);
                safelyRemoveStatement(shortKey + "$dynamicAdaptive");
            }
        }
    }

    private void safelyRemoveStatement(String id) {
        if (mappedStatementsField != null) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, ?> map = (Map<String, ?>) mappedStatementsField.get(sqlSessionTemplate.getConfiguration());
                if (map != null) {
                    map.remove(id);
                    if (id.contains(".")) {
                        String shortName = id.substring(id.lastIndexOf('.') + 1);
                        map.remove(shortName);
                    }
                }
            } catch (Throwable e) {
                log.debug("反射移除 Statement [{}] 跳过: {}", id, e.getMessage());
            }
        }
    }

    /**
     * 提取 StatementId 对应的原始 SQL 文本（带缓存）
     */
    public String resolveStatementSql(String statementId) {
        if (statementId == null || statementId.trim().isEmpty()) {
            return "";
        }
        return statementSqlCache.computeIfAbsent(statementId.trim(), this::extractSqlFromStatement);
    }

    /**
     * 智能定位 StatementId（支持短方法名在全局 Statement 中模糊匹配）
     */
    public String findStatementId(String statementName) {
        if (statementName == null || statementName.trim().isEmpty()) {
            throw new IllegalArgumentException("statementName 不能为 null 或空字符串");
        }
        String name = statementName.trim();
        Configuration configuration = sqlSessionTemplate.getConfiguration();

        // 1. 若已经是精确匹配的全限定 StatementId（包含点号且存在），优先直接返回
        if (name.contains(".") && configuration.hasStatement(name)) {
            return name;
        }

        // 2. 遍历查找匹配全限定 StatementId（支持通过类名.方法名、内部类$类名.方法名、或纯方法名）
        for (String id : configuration.getMappedStatementNames()) {
            if (id.contains(".") && (id.equals(name) || id.endsWith("." + name) || id.endsWith("$" + name))) {
                return id;
            }
        }

        // 3. 回退检查 configuration 中是否有该语句
        if (configuration.hasStatement(name)) {
            return name;
        }
        return name;
    }

    /**
     * 判断目标 Statement 是否已原生支持动态 Wrapper 参数占位符
     */
    public boolean hasWrapperPlaceholder(String statementId) {
        Configuration configuration = sqlSessionTemplate.getConfiguration();
        if (!configuration.hasStatement(statementId)) {
            return false;
        }
        MappedStatement ms = configuration.getMappedStatement(statementId);
        try {
            QueryWrapper<Object> probe = new QueryWrapper<>();
            probe.eq("__DYNAMIC_PROBE_SENTINEL__", "1");
            Map<String, Object> probeParams = new HashMap<>();
            probeParams.put(Constants.WRAPPER, probe);
            probeParams.put("ew", probe);
            BoundSql boundSql = ms.getBoundSql(probeParams);
            String sql = boundSql.getSql();
            return sql != null && sql.contains("__DYNAMIC_PROBE_SENTINEL__");
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 为目标 Statement 准备可执行的 statementId（自适应检测 ${ew.customSqlSegment}）
     */
    private String prepareTargetStatement(String statementId) {
        if (hasWrapperPlaceholder(statementId)) {
            return statementId;
        }
        String originalSql = resolveStatementSql(statementId);
        if (originalSql == null || originalSql.trim().isEmpty()) {
            return statementId;
        }
        // 未声明占位符：利用 JSqlParser 自动挂载自适应动态脚本并注册自适应 Statement
        return getOrCreateAdaptiveStatement(statementId, originalSql);
    }

    /**
     * 创建或复用自适应动态 Statement（JSqlParser AST 自动条件追加）
     */
    private synchronized String getOrCreateAdaptiveStatement(String statementId, String originalSql) {
        String adaptiveId = statementId + "$dynamicAdaptive";
        Configuration configuration = sqlSessionTemplate.getConfiguration();
        if (configuration.hasStatement(adaptiveId)) {
            return adaptiveId;
        }
        try {
            MappedStatement rawMs = configuration.getMappedStatement(statementId);
            boolean hasWhere = hasWhereClause(originalSql);
            String adaptiveSql;
            if (hasWhere) {
                adaptiveSql = "<script>" + originalSql +
                        "<choose>" +
                        "<when test=\"ew != null and !ew.emptyOfWhere\"> AND ${ew.sqlSegment}</when>" +
                        "<when test=\"ew != null and ew.emptyOfWhere and !ew.emptyOfNormal\"> ${ew.sqlSegment}</when>" +
                        "</choose></script>";
            } else {
                adaptiveSql = "<script>" + originalSql + " ${ew.customSqlSegment}</script>";
            }
            SqlSource sqlSource = configuration.getDefaultScriptingLanguageInstance().createSqlSource(configuration, adaptiveSql, Map.class);
            MappedStatement.Builder builder = new MappedStatement.Builder(configuration, adaptiveId, sqlSource, rawMs.getSqlCommandType())
                    .resource(rawMs.getResource())
                    .fetchSize(rawMs.getFetchSize())
                    .timeout(rawMs.getTimeout())
                    .statementType(rawMs.getStatementType())
                    .resultSetType(rawMs.getResultSetType())
                    .cache(rawMs.getCache())
                    .flushCacheRequired(rawMs.isFlushCacheRequired())
                    .useCache(rawMs.isUseCache())
                    .resultMaps(rawMs.getResultMaps())
                    .parameterMap(rawMs.getParameterMap());
            MappedStatement adaptiveMs = builder.build();

            safelyRegisterStatement(configuration, adaptiveId, adaptiveMs);
            return adaptiveId;
        } catch (Throwable e) {
            log.warn("自适应改写 Statement [{}] 失败，回退为原始 Statement: {}", statementId, e.getMessage());
            return statementId;
        }
    }

    private boolean hasWhereClause(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }
        try {
            String sanitizedSql = DynamicSqlProjectionResolver.cleanSqlForAstParsing(sql);
            net.sf.jsqlparser.statement.Statement statement = net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sanitizedSql);
            if (statement instanceof net.sf.jsqlparser.statement.select.Select select) {
                net.sf.jsqlparser.statement.select.PlainSelect plainSelect = select.getPlainSelect();
                if (plainSelect != null) {
                    return plainSelect.getWhere() != null;
                }
            }
        } catch (Throwable ignored) {
        }
        return sql.toUpperCase(Locale.ROOT).contains(" WHERE ");
    }

    /**
     * 安全注册 MappedStatement 至 Configuration（解除 StrictMap 冲突）
     */
    @SuppressWarnings("unchecked")
    private void safelyRegisterStatement(Configuration configuration, String id, MappedStatement ms) {
        if (mappedStatementsField != null) {
            try {
                Map<String, MappedStatement> map = (Map<String, MappedStatement>) mappedStatementsField.get(configuration);
                if (map != null) {
                    if (map.containsKey(id)) {
                        Map<String, MappedStatement> patch = new HashMap<>();
                        patch.put(id, ms);
                        if (id.contains(".")) {
                            String shortKey = id.substring(id.lastIndexOf('.') + 1);
                            patch.put(shortKey, ms);
                        }
                        map.putAll(patch);
                        return;
                    }
                }
            } catch (Throwable e) {
                log.debug("putAll 原子覆盖尝试失败，将回退至官方注册机制: {}", e.getMessage());
            }
        }
        configuration.addMappedStatement(ms);
    }

    private static Field resolveMappedStatementsField(Configuration configuration) {
        Class<?> clazz = configuration.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field field = clazz.getDeclaredField("mappedStatements");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        log.warn("未能定位 Configuration.mappedStatements 字段，同 ID 热替换可能触发 StrictMap 冲突告警");
        return null;
    }

    private String extractSqlFromStatement(String statementId) {
        Configuration configuration = sqlSessionTemplate.getConfiguration();
        if (!configuration.hasStatement(statementId)) {
            return "";
        }
        MappedStatement ms = configuration.getMappedStatement(statementId);
        try {
            Map<String, Object> probeParams = new HashMap<>();
            probeParams.put(Constants.WRAPPER, new QueryWrapper<>());
            probeParams.put("ew", new QueryWrapper<>());
            BoundSql boundSql = ms.getBoundSql(probeParams);
            return boundSql.getSql();
        } catch (Throwable e) {
            try {
                return ms.getBoundSql(null).getSql();
            } catch (Throwable ex) {
                return "";
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> BaseMapper<T> getEntityMapper(Class<T> entityClass) {
        BaseMapper<?> cached = entityMapperCache.get(entityClass);
        if (cached != null) {
            return (BaseMapper<T>) cached;
        }
        // 遍历所有 Mapper 接口匹配其实体泛型
        for (Class<?> mapperClass : sqlSessionTemplate.getConfiguration().getMapperRegistry().getMappers()) {
            for (Type type : mapperClass.getGenericInterfaces()) {
                if (type instanceof ParameterizedType pt) {
                    for (Type actual : pt.getActualTypeArguments()) {
                        if (actual.equals(entityClass)) {
                            BaseMapper<T> mapper = (BaseMapper<T>) sqlSessionTemplate.getMapper(mapperClass);
                            entityMapperCache.put(entityClass, mapper);
                            return mapper;
                        }
                    }
                }
            }
        }
        // 尝试通过类名规则推断 Mapper
        try {
            String mapperName = entityClass.getName().replace(".entity.", ".mapper.") + "Mapper";
            Class<?> mapperClass = Class.forName(mapperName);
            BaseMapper<T> mapper = (BaseMapper<T>) sqlSessionTemplate.getMapper(mapperClass);
            entityMapperCache.put(entityClass, mapper);
            return mapper;
        } catch (Throwable e) {
            throw new IllegalStateException("未能为实体类型 [" + entityClass.getName() + "] 找到对应的 Mapper 接口", e);
        }
    }

    @SuppressWarnings("unchecked")
    private <V> List<V> convertList(List<?> rawList, Class<V> voClass) {
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        if (voClass == null || voClass == Object.class) {
            return (List<V>) rawList;
        }
        return rawList.stream()
                .map(item -> DynamicSqlProjectionResolver.convertValueWithCamelSupport(item, voClass))
                .toList();
    }
}
