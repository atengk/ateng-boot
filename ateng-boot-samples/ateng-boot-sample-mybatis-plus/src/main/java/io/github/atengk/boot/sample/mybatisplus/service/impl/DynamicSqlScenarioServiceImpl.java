package io.github.atengk.boot.sample.mybatisplus.service.impl;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryCondition;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlExecutor;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.dynamicsql.ExternalSqlLoader;
import io.github.atengk.boot.sample.mybatisplus.entity.UserEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.UserMapper;
import io.github.atengk.boot.sample.mybatisplus.model.DynamicSqlHotSwapDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserDynamicVO;
import io.github.atengk.boot.sample.mybatisplus.model.UserSearchDTO;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicSqlScenarioService;
import org.apache.ibatis.mapping.SqlCommandType;
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
    private final UserMapper userMapper;

    public DynamicSqlScenarioServiceImpl(
            DynamicSqlRegistry registry,
            DynamicSqlExecutor executor,
            ExternalSqlLoader loader,
            UserMapper userMapper) {
        this.registry = registry;
        this.executor = executor;
        this.loader = loader;
        this.userMapper = userMapper;
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

    @Override
    public PageResult<UserDynamicVO> pageUsersDynamicWithSeed(String name, Integer minAge, PageQuery pageQuery) {
        ensureSeedUsersIfEmpty();
        return findUsersDynamicPage(name, minAge, pageQuery);
    }

    @Override
    public List<Map<String, Object>> hotSwapAndExecute(DynamicSqlHotSwapDTO dto) {
        ensureSeedUsersIfEmpty();

        // 1. 构造动态 SQL 元数据定义，交由 DynamicSqlRegistry 内存沙箱预编译校验并原子覆盖
        DynamicSqlDefinition definition = DynamicSqlDefinition.builder()
                .id(dto.getStatementId())
                .sqlScript(dto.getSqlScript())
                .commandType(SqlCommandType.SELECT)
                .description(dto.getDescription())
                .autoScriptWrap(true)
                .build();

        boolean registered = replaceCustomSql(definition);
        if (!registered) {
            throw new BusinessException(
                    CommonResultCode.BAD_REQUEST,
                    "外部动态 SQL 语法校验失败，内存沙箱已安全拦截并保留上一版本: " + dto.getStatementId()
            );
        }

        // 2. 热替换成功后立即执行最新版本的动态 SQL 语句
        return executeCustomQuery(dto.getStatementId(), dto.getSampleParams());
    }

    @Override
    public boolean existsUsersDynamicRequest(String name, Integer minAge, Long deptId) {
        ensureSeedUsersIfEmpty();
        DynamicQueryRequest request = new DynamicQueryRequest();
        if (name != null && !name.isBlank()) {
            request.addCondition(QueryCondition.like("name", name.trim()));
        }
        if (minAge != null) {
            request.addCondition(QueryCondition.ge("age", minAge));
        }
        Map<String, Object> extraParams = deptId != null ? Map.of("deptId", deptId) : null;
        return extraParams != null
                ? executor.exists("user.findUsersDynamic", request, extraParams)
                : executor.exists("user.findUsersDynamic", request);
    }

    @Override
    public boolean existsUsersDynamicDto(UserSearchDTO searchDTO, Long deptId) {
        ensureSeedUsersIfEmpty();
        Map<String, Object> extraParams = deptId != null ? Map.of("deptId", deptId) : null;
        return extraParams != null
                ? executor.existsDynamic("user.findUsersDynamic", searchDTO, extraParams)
                : executor.existsDynamic("user.findUsersDynamic", searchDTO);
    }

    private void ensureSeedUsersIfEmpty() {
        Long total = userMapper.selectCount(null);
        if (total == null || total == 0L) {
            UserEntity u1 = new UserEntity("张三", 25, "zhangsan@example.com", "13800000001", 101L);
            u1.setId(1001L);
            userMapper.insert(u1);

            UserEntity u2 = new UserEntity("李四", 30, "lisi@example.com", "13800000002", 101L);
            u2.setId(1002L);
            userMapper.insert(u2);

            UserEntity u3 = new UserEntity("张伟", 38, "zhangwei@example.com", "13800000003", 102L);
            u3.setId(1003L);
            userMapper.insert(u3);
        }
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
