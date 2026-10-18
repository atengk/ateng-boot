package io.github.atengk.boot.mybatisplus.dynamicsql;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.util.PageUtil;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.util.*;

/**
 * 通用编程式动态 SQL 执行门面组件
 * 依托 SqlSessionTemplate 与动态注册的 MappedStatement，提供免建 Mapper 接口的 CRUD 及符合 ADR-0010 的流式物理分页能力
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class DynamicSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(DynamicSqlExecutor.class);

    private final SqlSessionTemplate sqlSessionTemplate;
    private final DynamicSqlRegistry registry;

    public DynamicSqlExecutor(SqlSessionTemplate sqlSessionTemplate) {
        this(sqlSessionTemplate, null);
    }

    public DynamicSqlExecutor(SqlSessionTemplate sqlSessionTemplate, DynamicSqlRegistry registry) {
        this.sqlSessionTemplate = Objects.requireNonNull(sqlSessionTemplate, "SqlSessionTemplate must not be null");
        this.registry = registry;
    }

    /**
     * 执行单条查询并默认返回 Map 结构
     *
     * @param statementId 语句唯一标识
     * @param params      查询入参（支持 Map 或 JavaBean）
     * @return 查询结果 Map，无数据时返回 null
     */
    public Map<String, Object> selectOne(String statementId, Object params) {
        return selectOne(statementId, params, Map.class);
    }

    /**
     * 执行单条查询并强类型映射为指定 Class 结果
     *
     * @param statementId 语句唯一标识
     * @param params      查询入参（支持 Map 或 JavaBean）
     * @param resultType  目标类型 Class
     * @param <T>         目标泛型
     * @return 映射后的强类型实体或 VO，无数据时返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> T selectOne(String statementId, Object params, Class<T> resultType) {
        checkStatementExists(statementId);
        Object raw = sqlSessionTemplate.selectOne(statementId, params);
        if (raw == null) {
            return null;
        }
        return convertResult(raw, resultType);
    }

    /**
     * 执行批量查询并默认返回 List<Map> 结构
     *
     * @param statementId 语句唯一标识
     * @param params      查询入参
     * @return 列表结果，无匹配时保底返回空 List，严禁返回 null
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> selectList(String statementId, Object params) {
        List<Map> list = selectList(statementId, params, Map.class);
        return (List<Map<String, Object>>) (List<?>) list;
    }

    /**
     * 执行批量查询并强类型映射为 List<T>
     *
     * @param statementId 语句唯一标识
     * @param params      查询入参
     * @param resultType  目标实体或 VO 类型 Class
     * @param <T>         目标泛型
     * @return 列表结果，无匹配时保底返回空 List，严禁返回 null
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> selectList(String statementId, Object params, Class<T> resultType) {
        checkStatementExists(statementId);
        List<?> rawList = sqlSessionTemplate.selectList(statementId, params);
        if (rawList == null || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        if (resultType == null || resultType == Map.class || resultType == Object.class) {
            return (List<T>) rawList;
        }
        return rawList.stream()
                .map(item -> convertResult(item, resultType))
                .toList();
    }

    /**
     * 执行 DML 增删改操作并返回物理受影响行数
     *
     * @param statementId 语句唯一标识
     * @param params      参数
     * @return 受影响行数
     */
    public int executeDml(String statementId, Object params) {
        checkStatementExists(statementId);
        MappedStatement ms = sqlSessionTemplate.getConfiguration().getMappedStatement(statementId);
        SqlCommandType commandType = ms.getSqlCommandType();
        return switch (commandType) {
            case INSERT -> sqlSessionTemplate.insert(statementId, params);
            case DELETE -> sqlSessionTemplate.delete(statementId, params);
            default -> sqlSessionTemplate.update(statementId, params);
        };
    }

    /**
     * 深度融合 ADR-0010 分页契约的通用动态流式分页查询
     * 底层自动驱动 MyBatis-Plus 物理方言分页插件完成 count 统计与 limit 分页截取，并流式转换为 PageResult<T>
     *
     * @param statementId 语句唯一标识
     * @param params      业务查询参数
     * @param pageQuery   业务传输层分页入参
     * @param resultType  目标结果类型 Class
     * @param <T>         目标泛型
     * @return 符合标准契约的前端分页结果 PageResult<T>
     */
    public <T> PageResult<T> selectPage(String statementId, Object params, PageQuery pageQuery, Class<T> resultType) {
        checkStatementExists(statementId);
        Page<T> mpPage = PageUtil.toPage(pageQuery);

        Map<String, Object> paramMap = wrapPageParams(params, mpPage);
        List<?> records = sqlSessionTemplate.selectList(statementId, paramMap);

        // 防御性保障：无论插件是否已直接回写 records，均执行强类型转换与保底装配
        if (mpPage.getRecords() == null || mpPage.getRecords().isEmpty()) {
            if (records != null && !records.isEmpty()) {
                List<T> converted = records.stream()
                        .map(r -> convertResult(r, resultType))
                        .toList();
                mpPage.setRecords(converted);
            } else {
                mpPage.setRecords(Collections.emptyList());
            }
        } else {
            List<T> converted = mpPage.getRecords().stream()
                    .map(r -> convertResult(r, resultType))
                    .toList();
            mpPage.setRecords(converted);
        }

        return PageUtil.toPageResult(mpPage);
    }

    /**
     * 校验目标 statementId 是否已在持久层容器中就绪
     */
    private void checkStatementExists(String statementId) {
        Assert.hasText(statementId, "statementId must not be blank");
        boolean exists;
        if (registry != null) {
            exists = registry.hasStatement(statementId);
        } else {
            exists = sqlSessionTemplate.getConfiguration().hasStatement(statementId, false);
        }
        if (!exists) {
            throw new IllegalArgumentException("未找到已注册的动态 SQL 语句: [" + statementId + "]");
        }
    }

    /**
     * 包装包含 MyBatis-Plus IPage 的联合查询参数 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> wrapPageParams(Object params, Page<?> page) {
        Map<String, Object> map = new HashMap<>();
        if (params instanceof Map<?, ?> userMap) {
            userMap.forEach((k, v) -> map.put(String.valueOf(k), v));
        } else if (params != null) {
            try {
                Map<String, Object> beanMap = JsonUtil.convertValue(params, new TypeReference<Map<String, Object>>() {});
                if (beanMap != null) {
                    map.putAll(beanMap);
                }
            } catch (Exception e) {
                log.debug("入参 POJO 转 Map 展开跳过，直接作为 param 载荷包装: {}", e.getMessage());
                map.put("param", params);
            }
        }
        // 挂载分页对象，便于 ParameterUtils.findPage 智能嗅探
        map.put("page", page);
        return map;
    }

    /**
     * 将底层持久层原生查询结果流式转译为指定目标类型
     */
    @SuppressWarnings("unchecked")
    private <T> T convertResult(Object raw, Class<T> resultType) {
        if (raw == null) {
            return null;
        }
        if (resultType == null || resultType.isInstance(raw)) {
            return (T) raw;
        }
        if (resultType == Map.class) {
            if (raw instanceof Map) {
                return (T) raw;
            }
            return (T) JsonUtil.convertValue(raw, Map.class);
        }
        return JsonUtil.convertValue(raw, resultType);
    }
}
