package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DynamicQueryTemplate 核心门面全功能与对称 API 单元测试
 * 覆盖单表实体、DTO 驱动、基于 StatementId 的多表 XML/外部 SQL 查询及记录数统计
 *
 * @author Ateng
 * @since 2026-10-19
 */
class DynamicQueryTemplateTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static DynamicQueryTemplate dynamicQueryTemplate;

    @Data
    @TableName("t_template_user")
    public static class TemplateUserEntity implements Serializable {
        @TableId
        private Long id;
        private String username;
        private Integer age;
        private String email;
        private String status;
    }

    @Data
    public static class TemplateUserVO implements Serializable {
        private Long id;
        private String username;
        private Integer age;
        private String email;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class TemplateUserQueryDTO extends PageQuery {
        @QueryField(operator = QueryOperator.LIKE)
        private String username;

        @QueryField(operator = QueryOperator.GE)
        private Integer age;

        @QueryField(operator = QueryOperator.EQ)
        private String status;
    }

    public interface TemplateUserMapper extends AtengBaseMapper<TemplateUserEntity> {
    }

    @BeforeAll
    static void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:template_query_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t_template_user (" +
                    "id BIGINT PRIMARY KEY, " +
                    "username VARCHAR(64), " +
                    "age INT, " +
                    "email VARCHAR(64), " +
                    "status VARCHAR(16)" +
                    ")");

            stmt.execute("INSERT INTO t_template_user VALUES " +
                    "(1, 'alice', 20, 'alice@example.com', 'ACTIVE'), " +
                    "(2, 'bob', 25, 'bob@example.com', 'ACTIVE'), " +
                    "(3, 'charlie', 30, 'charlie@example.com', 'INACTIVE'), " +
                    "(4, 'david', 35, 'david@example.com', 'ACTIVE')");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("template_test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(interceptor);

        configuration.addMapper(TemplateUserMapper.class);

        // 注册模拟的外部 XML Statement
        String mapperName = TemplateUserMapper.class.getName();
        String sqlWithEw = "<script>SELECT id, username, age, email FROM t_template_user ${ew.customSqlSegment}</script>";
        registerMappedStatement(configuration, mapperName + ".selectActiveUsers", sqlWithEw, TemplateUserVO.class);

        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        dynamicQueryTemplate = new DynamicQueryTemplate(sqlSessionTemplate);
    }

    @AfterAll
    static void destroy() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_template_user");
        }
    }

    private static void registerMappedStatement(MybatisConfiguration configuration, String id, String sqlScript, Class<?> resultType) {
        SqlSource sqlSource = configuration.getDefaultScriptingLanguageInstance().createSqlSource(configuration, sqlScript, Object.class);
        ResultMap resultMap = new ResultMap.Builder(configuration, id + "-ResultMap", resultType, Collections.emptyList()).build();
        MappedStatement ms = new MappedStatement.Builder(configuration, id, sqlSource, SqlCommandType.SELECT)
                .resultMaps(List.of(resultMap))
                .build();
        configuration.addMappedStatement(ms);
    }

    @Test
    @DisplayName("测试单表实体分页查询并投影为 VO：selectPage(request, entityClass, voClass)")
    void testSelectPageEntityToVo() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(2L);
        request.addCondition(QueryCondition.eq("status", "ACTIVE"));
        request.addSort(SortItem.asc("age"));

        PageResult<TemplateUserVO> result = dynamicQueryTemplate.selectPage(request, TemplateUserEntity.class, TemplateUserVO.class);

        assertNotNull(result);
        assertEquals(3L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals("alice", result.getRecords().get(0).getUsername());
        assertEquals(20, result.getRecords().get(0).getAge());
        assertEquals("bob", result.getRecords().get(1).getUsername());
    }

    @Test
    @DisplayName("测试单表实体直接分页查询：selectPage(request, entityClass)")
    void testSelectPageEntityDirect() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.addCondition(QueryCondition.like("username", "li")); // alice, charlie

        PageResult<TemplateUserEntity> result = dynamicQueryTemplate.selectPage(request, TemplateUserEntity.class);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("测试 DTO 驱动的单表实体分页查询并投影为 VO：selectPage(dto, entityClass, voClass)")
    void testSelectPageDtoToVo() {
        TemplateUserQueryDTO dto = new TemplateUserQueryDTO();
        dto.setCurrent(1L);
        dto.setSize(10L);
        dto.setAge(25);
        dto.setStatus("ACTIVE");

        PageResult<TemplateUserVO> result = dynamicQueryTemplate.selectPage(dto, TemplateUserEntity.class, TemplateUserVO.class);

        assertNotNull(result);
        assertEquals(2L, result.getTotal()); // bob (25), david (35)
        assertEquals(2, result.getRecords().size());
    }

    @Test
    @DisplayName("测试单表实体列表查询并投影为 VO：selectList(request, entityClass, voClass)")
    void testSelectListEntityToVo() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.in("username", List.of("alice", "david")));
        request.addSort(SortItem.desc("age"));

        List<TemplateUserVO> list = dynamicQueryTemplate.selectList(request, TemplateUserEntity.class, TemplateUserVO.class);

        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("david", list.get(0).getUsername());
        assertEquals("alice", list.get(1).getUsername());
    }

    @Test
    @DisplayName("测试单表实体直接列表查询：selectList(request, entityClass)")
    void testSelectListEntityDirect() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("status", "INACTIVE"));

        List<TemplateUserEntity> list = dynamicQueryTemplate.selectList(request, TemplateUserEntity.class);

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("charlie", list.get(0).getUsername());
    }

    @Test
    @DisplayName("测试 DTO 驱动的单表实体列表查询并投影为 VO：selectList(dto, entityClass, voClass)")
    void testSelectListDtoToVo() {
        TemplateUserQueryDTO dto = new TemplateUserQueryDTO();
        dto.setUsername("a"); // alice, charlie, david
        dto.setStatus("ACTIVE"); // alice, david

        List<TemplateUserVO> list = dynamicQueryTemplate.selectList(dto, TemplateUserEntity.class, TemplateUserVO.class);

        assertNotNull(list);
        assertEquals(2, list.size());
    }

    @Test
    @DisplayName("测试单表实体单条查询：selectOne(request, entityClass, voClass)")
    void testSelectOneEntity() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("username", "bob"));

        TemplateUserVO vo = dynamicQueryTemplate.selectOne(request, TemplateUserEntity.class, TemplateUserVO.class);
        assertNotNull(vo);
        assertEquals("bob", vo.getUsername());
        assertEquals(25, vo.getAge());

        TemplateUserEntity entity = dynamicQueryTemplate.selectOne(request, TemplateUserEntity.class);
        assertNotNull(entity);
        assertEquals("bob", entity.getUsername());
        assertEquals("bob@example.com", entity.getEmail());
    }

    @Test
    @DisplayName("测试单表实体记录数统计：count(request, entityClass) 与 count(dto, entityClass)")
    void testCountEntity() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("status", "ACTIVE"));

        long activeCount = dynamicQueryTemplate.count(request, TemplateUserEntity.class);
        assertEquals(3L, activeCount);

        TemplateUserQueryDTO dto = new TemplateUserQueryDTO();
        dto.setAge(30);
        long ageGeCount = dynamicQueryTemplate.count(dto, TemplateUserEntity.class);
        assertEquals(2L, ageGeCount); // charlie (30), david (35)
    }

    @Test
    @DisplayName("测试基于 StatementId 的分页、列表、单条与统计查询")
    void testStatementBasedOperations() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(2L);
        request.addCondition(QueryCondition.ge("age", 25));
        request.addSort(SortItem.asc("age"));

        // 分页
        PageResult<TemplateUserVO> pageResult = dynamicQueryTemplate.selectPage("selectActiveUsers", request, TemplateUserVO.class);
        assertNotNull(pageResult);
        assertEquals(3L, pageResult.getTotal());
        assertEquals(2, pageResult.getRecords().size());
        assertEquals("bob", pageResult.getRecords().get(0).getUsername());

        // 列表
        DynamicQueryRequest listRequest = new DynamicQueryRequest();
        listRequest.addCondition(QueryCondition.eq("username", "alice"));
        List<TemplateUserVO> list = dynamicQueryTemplate.selectList("selectActiveUsers", listRequest, TemplateUserVO.class);
        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("alice", list.get(0).getUsername());

        // 单条
        TemplateUserVO vo = dynamicQueryTemplate.selectOne("selectActiveUsers", listRequest, TemplateUserVO.class);
        assertNotNull(vo);
        assertEquals("alice", vo.getUsername());

        // 统计
        long count = dynamicQueryTemplate.count("selectActiveUsers", listRequest);
        assertEquals(1L, count);
    }

    @Test
    @DisplayName("测试防御性卫语句与保底返回：空入参返回空集合/空分页，不报 NPE")
    void testDefensiveNullAndEmptyHandling() {
        PageResult<TemplateUserVO> emptyPage = dynamicQueryTemplate.selectPage((DynamicQueryRequest) null, TemplateUserEntity.class, TemplateUserVO.class);
        assertNotNull(emptyPage);
        assertEquals(0L, emptyPage.getTotal());
        assertTrue(emptyPage.getRecords().isEmpty());

        List<TemplateUserVO> emptyList = dynamicQueryTemplate.selectList((DynamicQueryRequest) null, TemplateUserEntity.class, TemplateUserVO.class);
        assertNotNull(emptyList);
        assertTrue(emptyList.isEmpty());

        TemplateUserVO nullVo = dynamicQueryTemplate.selectOne((DynamicQueryRequest) null, TemplateUserEntity.class, TemplateUserVO.class);
        assertNull(nullVo);

        // StatementId 空入参保底
        PageResult<TemplateUserVO> stmtEmptyPage = dynamicQueryTemplate.selectPage("selectActiveUsers", (DynamicQueryRequest) null, TemplateUserVO.class);
        assertNotNull(stmtEmptyPage);
        assertTrue(stmtEmptyPage.getRecords().isEmpty());

        List<TemplateUserVO> stmtEmptyList = dynamicQueryTemplate.selectList("selectActiveUsers", (DynamicQueryRequest) null, TemplateUserVO.class);
        assertNotNull(stmtEmptyList);
        assertTrue(stmtEmptyList.isEmpty());
    }

    @Test
    @DisplayName("测试基于 StatementId 执行存在性判断：exists(statementName, request / queryDto)")
    void testExistsStatement() {
        // 1. 命中存在记录 (Request)
        DynamicQueryRequest hitRequest = new DynamicQueryRequest();
        hitRequest.addCondition(QueryCondition.eq("username", "alice"));
        boolean exists1 = dynamicQueryTemplate.exists("selectActiveUsers", hitRequest);
        assertTrue(exists1);

        // 2. 未命中记录 (Request)
        DynamicQueryRequest missRequest = new DynamicQueryRequest();
        missRequest.addCondition(QueryCondition.eq("username", "non_existent_user"));
        boolean exists2 = dynamicQueryTemplate.exists("selectActiveUsers", missRequest);
        assertFalse(exists2);

        // 3. 空入参保底 (Request 为 null 返回 false)
        assertFalse(dynamicQueryTemplate.exists("selectActiveUsers", (DynamicQueryRequest) null));

        // 4. 命中存在记录 (DTO)
        TemplateUserQueryDTO hitDto = new TemplateUserQueryDTO();
        hitDto.setUsername("bob");
        hitDto.setStatus("ACTIVE");
        boolean exists3 = dynamicQueryTemplate.exists("selectActiveUsers", hitDto);
        assertTrue(exists3);

        // 5. 未命中记录 (DTO)
        TemplateUserQueryDTO missDto = new TemplateUserQueryDTO();
        missDto.setUsername("ghost");
        boolean exists4 = dynamicQueryTemplate.exists("selectActiveUsers", missDto);
        assertFalse(exists4);

        // 6. 空入参保底 (DTO 为 null 返回 false)
        assertFalse(dynamicQueryTemplate.exists("selectActiveUsers", (Object) null));
    }

    @Test
    @DisplayName("测试基于单表实体类型执行存在性判断：exists(request / queryDto, entityClass)")
    void testExistsEntity() {
        // 1. 命中存在记录 (Request)
        DynamicQueryRequest hitRequest = new DynamicQueryRequest();
        hitRequest.addCondition(QueryCondition.eq("username", "david"));
        hitRequest.addCondition(QueryCondition.ge("age", 30));
        boolean exists1 = dynamicQueryTemplate.exists(hitRequest, TemplateUserEntity.class);
        assertTrue(exists1);

        // 2. 未命中记录 (Request)
        DynamicQueryRequest missRequest = new DynamicQueryRequest();
        missRequest.addCondition(QueryCondition.eq("username", "david"));
        missRequest.addCondition(QueryCondition.gt("age", 50));
        boolean exists2 = dynamicQueryTemplate.exists(missRequest, TemplateUserEntity.class);
        assertFalse(exists2);

        // 3. 空入参保底
        assertFalse(dynamicQueryTemplate.exists((DynamicQueryRequest) null, TemplateUserEntity.class));

        // 4. 命中存在记录 (DTO)
        TemplateUserQueryDTO hitDto = new TemplateUserQueryDTO();
        hitDto.setUsername("charlie");
        hitDto.setAge(25);
        boolean exists3 = dynamicQueryTemplate.exists(hitDto, TemplateUserEntity.class);
        assertTrue(exists3);

        // 5. 未命中记录 (DTO)
        TemplateUserQueryDTO missDto = new TemplateUserQueryDTO();
        missDto.setUsername("charlie");
        missDto.setAge(50);
        boolean exists4 = dynamicQueryTemplate.exists(missDto, TemplateUserEntity.class);
        assertFalse(exists4);

        // 6. 空入参保底
        assertFalse(dynamicQueryTemplate.exists((Object) null, TemplateUserEntity.class));
    }
}
