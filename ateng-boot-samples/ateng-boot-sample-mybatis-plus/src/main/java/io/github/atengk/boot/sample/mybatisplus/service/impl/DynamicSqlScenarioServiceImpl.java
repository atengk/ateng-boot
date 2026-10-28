package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicSqlScenarioService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部动态 SQL 体系业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
public class DynamicSqlScenarioServiceImpl implements DynamicSqlScenarioService {

    private final DynamicSqlRegistry registry;
    private final DynamicSqlExecutor executor;
    private final ExternalSqlLoader loader;

    public DynamicSqlScenarioServiceImpl(
            DynamicSqlRegistry registry,
            DynamicSqlExecutor executor,
            ExternalSqlLoader loader) {
        this.registry = registry;
        this.executor = executor;
        this.loader = loader;
    }

    @Override
    public List<UserDynamicVO> findUsersDynamic(String name, Integer minAge, Long deptId) {
        Map<String, Object> params = buildUserQueryParams(name, minAge, deptId);
        List<UserDynamicVO> list = executor.selectList("user.findUsersDynamic", params, UserDynamicVO.class);
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public PageResult<UserDynamicVO> findUsersDynamicPage(String name, Integer minAge, PageQuery pageQuery) {
        Map<String, Object> params = buildUserQueryParams(name, minAge, null);
        return executor.selectPage("user.findUsersDynamic", params, pageQuery, UserDynamicVO.class);
    }

    @Override
    public long countUsersDynamic(String name, Integer minAge, Long deptId) {
        Map<String, Object> params = buildUserQueryParams(name, minAge, deptId);
        Map<String, Object> countMap = executor.selectOne("user.countUsersDynamic", params);
        if (countMap == null || countMap.isEmpty()) {
            return 0L;
        }
        Object countVal = countMap.values().iterator().next();
        return countVal instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(countVal));
    }

    @Override
    public boolean registerCustomSql(DynamicSqlDefinition definition) {
        return registry.register(definition);
    }

    @Override
    public boolean replaceCustomSql(DynamicSqlDefinition definition) {
        return registry.register(definition);
    }

    @Override
    public boolean removeCustomSql(String statementId) {
        return registry.remove(statementId);
    }

    @Override
    public List<Map<String, Object>> executeCustomQuery(String statementId, Map<String, Object> params) {
        List<Map<String, Object>> list = executor.selectList(statementId, params != null ? params : Collections.emptyMap());
        return list != null ? list : Collections.emptyList();
    }

    @Override
    public DynamicSqlRegistry getRegistry() {
        return registry;
    }

    @Override
    public DynamicSqlExecutor getExecutor() {
        return executor;
    }

    @Override
    public ExternalSqlLoader getLoader() {
        return loader;
    }

    /**
     * 统一构建动态用户查询入参 Map，消除重复逻辑
     *
     * @param name   用户名称
     * @param minAge 最小年龄
     * @param deptId 部门 ID
     * @return 过滤参数 Map
     */
    private Map<String, Object> buildUserQueryParams(String name, Integer minAge, Long deptId) {
        Map<String, Object> params = new HashMap<>();
        if (name != null && !name.isBlank()) {
            params.put("name", name.trim());
        }
        if (minAge != null) {
            params.put("minAge", minAge);
        }
        if (deptId != null) {
            params.put("deptId", deptId);
        }
        return params;
    }
}
