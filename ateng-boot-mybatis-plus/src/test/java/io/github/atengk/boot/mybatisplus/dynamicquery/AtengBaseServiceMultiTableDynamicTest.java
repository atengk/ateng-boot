package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AtengBaseService 多表联表方法引用与短方法名动态直出全真 H2 测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
class AtengBaseServiceMultiTableDynamicTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static DynamicQueryTemplate dynamicQueryTemplate;
    private static TestUserService userService;

    @Getter
    @Setter
    @ToString
    @TableName("t_multi_user")
    public static class TestUserEntity {
        @TableId
        private Long id;
        private String username;
        private Long deptId;
        private Integer deleted;
    }

    @Getter
    @Setter
    @ToString
    public static class UserDeptVO {
        private Long id;
        private String username;
        private String deptName; // 纯 POJO 字段，由 AST 投影推导为 d.name
        private String deptCode; // 纯 POJO 字段，由 AST 投影推导为 d.code
    }

    public interface TestUserMapper extends AtengBaseMapper<TestUserEntity> {
        IPage<UserDeptVO> selectUserDeptPage(IPage<UserDeptVO> page, @Param(Constants.WRAPPER) Wrapper<UserDeptVO> wrapper);

        List<UserDeptVO> selectUserDeptList(@Param(Constants.WRAPPER) Wrapper<UserDeptVO> wrapper);

        // 未声明占位符的原生联表查询方法
        List<UserDeptVO> selectPlainJoinList(@Param(Constants.WRAPPER) Wrapper<UserDeptVO> wrapper);
    }

    public interface TestUserService extends AtengBaseService<TestUserEntity> {
    }

    public static class TestUserServiceImpl extends AtengServiceImpl<TestUserMapper, TestUserEntity> implements TestUserService {
        public TestUserServiceImpl(TestUserMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void init() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:multi_dynamic_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t_dept (id BIGINT PRIMARY KEY, name VARCHAR(64), code VARCHAR(64))");
            stmt.execute("CREATE TABLE t_multi_user (id BIGINT PRIMARY KEY, username VARCHAR(64), dept_id BIGINT, deleted INT DEFAULT 0)");

            stmt.execute("INSERT INTO t_dept VALUES (1, '研发中心', 'DEV'), (2, '运营中心', 'OPS')");
            stmt.execute("INSERT INTO t_multi_user VALUES (101, 'alice', 1, 0), (102, 'bob', 1, 0), (103, 'charlie', 2, 0)");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        configuration.setMapUnderscoreToCamelCase(true);

        // 注册分页拦截器
        com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor());
        configuration.addInterceptor(interceptor);

        // 注册 Mapper 接口
        configuration.addMapper(TestUserMapper.class);

        // 注册带 ${ew.customSqlSegment} 的 Mapper XML 模拟语句
        String mapperName = TestUserMapper.class.getName();
        String sqlWithEw = "<script>SELECT u.id, u.username, d.name AS dept_name, d.code AS dept_code " +
                "FROM t_multi_user u LEFT JOIN t_dept d ON u.dept_id = d.id ${ew.customSqlSegment}</script>";
        registerMappedStatement(configuration, mapperName + ".selectUserDeptPage", sqlWithEw, UserDeptVO.class);
        registerMappedStatement(configuration, mapperName + ".selectUserDeptList", sqlWithEw, UserDeptVO.class);

        // 注册未声明占位符的普通联表 SQL 模拟语句（验证 AST 自动追加）
        String plainSql = "SELECT u.id, u.username, d.name AS dept_name, d.code AS dept_code " +
                "FROM t_multi_user u LEFT JOIN t_dept d ON u.dept_id = d.id";
        registerMappedStatement(configuration, mapperName + ".selectPlainJoinList", plainSql, UserDeptVO.class);

        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        dynamicQueryTemplate = new DynamicQueryTemplate(sqlSessionTemplate);

        TestUserMapper mapper = sqlSessionTemplate.getMapper(TestUserMapper.class);
        TestUserServiceImpl serviceImpl = new TestUserServiceImpl(mapper);
        serviceImpl.setDynamicQueryTemplate(dynamicQueryTemplate);
        userService = serviceImpl;
    }

    @AfterAll
    static void destroy() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_multi_user");
            stmt.execute("DROP TABLE IF EXISTS t_dept");
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
    @DisplayName("测试基于方法引用的多表联表分页直出：pageDynamic(baseMapper::selectUserDeptPage, request, UserDeptVO.class)")
    void testPageDynamicWithMethodReference() {
        TestUserMapper mapper = sqlSessionTemplate.getMapper(TestUserMapper.class);

        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        // 前端只传 VO 属性名 deptName 与 username 排序，纯 POJO 零注解
        request.addCondition(QueryCondition.like("deptName", "研发"));
        request.addSort(SortItem.desc("username"));

        PageResult<UserDeptVO> result = userService.pageDynamic(mapper::selectUserDeptPage, request, UserDeptVO.class);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());

        // 验证排序：username DESC -> bob(102), alice(101)
        assertEquals("bob", result.getRecords().get(0).getUsername());
        assertEquals("研发中心", result.getRecords().get(0).getDeptName());
        assertEquals("DEV", result.getRecords().get(0).getDeptCode());

        assertEquals("alice", result.getRecords().get(1).getUsername());
        assertEquals("研发中心", result.getRecords().get(1).getDeptName());
    }

    @Test
    @DisplayName("测试基于短方法名的多表联表分页直出：pageDynamic(\"selectUserDeptPage\", request, UserDeptVO.class)")
    void testPageDynamicWithShortStatementName() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.addCondition(QueryCondition.eq("deptCode", "OPS"));

        PageResult<UserDeptVO> result = userService.pageDynamic("selectUserDeptPage", request, UserDeptVO.class);

        assertNotNull(result);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("charlie", result.getRecords().get(0).getUsername());
        assertEquals("运营中心", result.getRecords().get(0).getDeptName());
        assertEquals("OPS", result.getRecords().get(0).getDeptCode());
    }

    @Test
    @DisplayName("测试基于方法引用的多表联表列表直出：listDynamic(baseMapper::selectUserDeptList, request, UserDeptVO.class)")
    void testListDynamicWithMethodReference() {
        TestUserMapper mapper = sqlSessionTemplate.getMapper(TestUserMapper.class);

        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.like("username", "li")); // alice, charlie
        request.addSort(SortItem.asc("username"));

        List<UserDeptVO> list = userService.listDynamic(mapper::selectUserDeptList, request, UserDeptVO.class);

        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("alice", list.get(0).getUsername());
        assertEquals("charlie", list.get(1).getUsername());
    }

    @Test
    @DisplayName("测试基于短方法名的列表与单条直出：listDynamic 与 getOneDynamic")
    void testListAndGetOneDynamicWithStatementName() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("username", "charlie"));

        UserDeptVO vo = userService.getOneDynamic("selectUserDeptList", request, UserDeptVO.class);
        assertNotNull(vo);
        assertEquals("charlie", vo.getUsername());
        assertEquals("运营中心", vo.getDeptName());
    }

    @Test
    @DisplayName("测试未声明占位符的普通 SQL 语句通过 JSqlParser AST 自适应条件追加")
    void testAdaptiveSqlWithoutPlaceholder() {
        // selectPlainJoinList 原始没有 ${ew.customSqlSegment}
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("username", "alice"));

        List<UserDeptVO> list = dynamicQueryTemplate.selectList("TestUserMapper.selectPlainJoinList", request, UserDeptVO.class);

        assertNotNull(list);
        assertEquals(1, list.size());
        assertEquals("alice", list.get(0).getUsername());
        assertEquals("研发中心", list.get(0).getDeptName());
    }

    @Test
    @DisplayName("测试基于 DTO 对象的联表分页直出")
    void testPageDynamicWithDto() {
        TestUserMapper mapper = sqlSessionTemplate.getMapper(TestUserMapper.class);

        TestUserDeptDTO dto = new TestUserDeptDTO();
        dto.setDeptName("研发");
        dto.setCurrent(1L);
        dto.setSize(5L);
        dto.setSorts(List.of(SortItem.asc("username")));

        PageResult<UserDeptVO> result = userService.pageDynamic(mapper::selectUserDeptPage, dto, UserDeptVO.class);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals("alice", result.getRecords().get(0).getUsername());
        assertEquals("bob", result.getRecords().get(1).getUsername());
    }

    @Getter
    @Setter
    public static class TestUserDeptDTO extends io.github.atengk.boot.core.model.PageQuery {
        @io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField(operator = QueryOperator.LIKE)
        private String deptName;
    }
}
