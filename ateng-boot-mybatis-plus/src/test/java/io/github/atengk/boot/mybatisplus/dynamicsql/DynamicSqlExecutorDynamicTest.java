package io.github.atengk.boot.mybatisplus.dynamicsql;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryHelper;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryTemplate;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryCondition;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.SqlCommandType;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 外部动态 SQL 统一集成与 DynamicSqlExecutor 重载全功能集成测试
 * 覆盖外部动态 SQL 注册、多表 AST 投影自愈、DynamicQueryRequest 条件构造、
 * 业务 DTO 驱动、流式分页、强类型与 Map 映射、热替换即时生效与注销
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("DynamicSqlExecutor 外部动态 SQL 与动态查询扩展集成测试")
class DynamicSqlExecutorDynamicTest {

    private static DataSource dataSource;
    private static MybatisConfiguration configuration;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static DynamicSqlRegistry registry;
    private static DynamicQueryTemplate dynamicQueryTemplate;
    private static DynamicSqlExecutor executor;

    @Data
    public static class UserDeptVO implements Serializable {
        private Long userId;
        private String userName;
        private Integer age;
        private String status;
        private String deptName;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UserQueryDTO extends PageQuery {
        @QueryField(operator = QueryOperator.LIKE)
        private String userName;

        @QueryField(operator = QueryOperator.GE)
        private Integer age;

        @QueryField(column = "d.dept_name", operator = QueryOperator.EQ)
        private String deptName;
    }

    @BeforeAll
    static void init() throws Exception {
        DynamicQueryHelper.setEnabled(true);

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dynamic_sql_executor_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE t_exec_dept (" +
                    "id BIGINT PRIMARY KEY, " +
                    "dept_name VARCHAR(64)" +
                    ")");

            stmt.execute("CREATE TABLE t_exec_user (" +
                    "id BIGINT PRIMARY KEY, " +
                    "user_name VARCHAR(64), " +
                    "age INT, " +
                    "dept_id BIGINT, " +
                    "status VARCHAR(16)" +
                    ")");

            stmt.execute("INSERT INTO t_exec_dept VALUES (10, '研发部'), (20, '市场部')");

            stmt.execute("INSERT INTO t_exec_user VALUES " +
                    "(1, 'Alice', 22, 10, 'ACTIVE'), " +
                    "(2, 'Bob', 28, 10, 'ACTIVE'), " +
                    "(3, 'Charlie', 35, 20, 'ACTIVE'), " +
                    "(4, 'David', 40, 20, 'INACTIVE')");
        }

        configuration = new MybatisConfiguration();
        Environment environment = new Environment("executor_test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        configuration.addInterceptor(interceptor);

        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        registry = new DynamicSqlRegistry(configuration);
        dynamicQueryTemplate = new DynamicQueryTemplate(sqlSessionTemplate, registry);
        executor = new DynamicSqlExecutor(sqlSessionTemplate, registry, dynamicQueryTemplate);

        // 默认预注册多表关联外部动态 SQL
        String defaultSql = "SELECT u.id AS user_id, u.user_name, u.age, u.status, d.dept_name " +
                "FROM t_exec_user u " +
                "LEFT JOIN t_exec_dept d ON u.dept_id = d.id " +
                "${ew.customSqlSegment}";
        registry.register(DynamicSqlDefinition.builder()
                .id("exec.userWithDept")
                .sqlScript(defaultSql)
                .commandType(SqlCommandType.SELECT)
                .build());
    }

    @AfterAll
    static void destroy() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_exec_user");
            stmt.execute("DROP TABLE IF EXISTS t_exec_dept");
        }
    }

    @Test
    @DisplayName("测试外部动态 SQL 预编译注册时自动挂载 AST 投影字典快照")
    void testRegistryExtractsProjections() {
        String statementId = "exec.projectionsTest";
        String sql = "SELECT u.id AS user_id, u.user_name, u.age, u.status, d.dept_name " +
                "FROM t_exec_user u " +
                "LEFT JOIN t_exec_dept d ON u.dept_id = d.id " +
                "${ew.customSqlSegment}";

        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript(sql)
                .commandType(SqlCommandType.SELECT)
                .build();

        boolean registered = registry.register(def);
        assertTrue(registered);

        DynamicSqlDefinition savedDef = registry.getDefinition(statementId);
        assertNotNull(savedDef);
        Map<String, String> projections = savedDef.getProjections();
        assertNotNull(projections, "预编译注册后应当自动提取并挂载 AST 投影字典");
        assertEquals("u.id", projections.get("userId"));
        assertEquals("u.user_name", projections.get("userName"));
        assertEquals("d.dept_name", projections.get("deptName"));
    }

    @Test
    @DisplayName("测试 selectPage(statementId, DynamicQueryRequest, Class<VO>) 动态条件分页与投影")
    void testSelectPageWithDynamicQueryRequest() {
        String statementId = "exec.userWithDept";

        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(2L);
        request.addCondition(QueryCondition.like("userName", "li")); // Alice, Charlie
        request.addCondition(QueryCondition.ge("age", 20));
        request.addSort(SortItem.desc("age"));

        PageResult<UserDeptVO> result = executor.selectPage(statementId, request, UserDeptVO.class);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());

        // Charlie (35) 排在前面，Alice (22) 排在后面
        UserDeptVO first = result.getRecords().get(0);
        assertEquals("Charlie", first.getUserName());
        assertEquals(35, first.getAge());
        assertEquals("市场部", first.getDeptName());
        assertEquals(3L, first.getUserId());

        UserDeptVO second = result.getRecords().get(1);
        assertEquals("Alice", second.getUserName());
        assertEquals(22, second.getAge());
        assertEquals("研发部", second.getDeptName());
        assertEquals(1L, second.getUserId());
    }

    @Test
    @DisplayName("测试 selectPage(statementId, DynamicQueryRequest) 默认返回 Map 列表")
    void testSelectPageDefaultMap() {
        String statementId = "exec.userWithDept";

        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(5L);
        request.addCondition(QueryCondition.eq("deptName", "研发部"));

        PageResult<Map<String, Object>> result = executor.selectPage(statementId, request);

        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertTrue(result.getRecords().stream().allMatch(m -> "研发部".equals(m.get("deptName")) || "研发部".equals(m.get("DEPT_NAME"))));
    }

    @Test
    @DisplayName("测试 selectPage(statementId, queryDto, Class<VO>) 业务 DTO 驱动分页")
    void testSelectPageWithQueryDto() {
        String statementId = "exec.userWithDept";

        UserQueryDTO dto = new UserQueryDTO();
        dto.setCurrent(1L);
        dto.setSize(10L);
        dto.setUserName("Bob");
        dto.setDeptName("研发部");

        PageResult<UserDeptVO> result = executor.selectPage(statementId, dto, UserDeptVO.class);

        assertNotNull(result);
        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        UserDeptVO vo = result.getRecords().get(0);
        assertEquals("Bob", vo.getUserName());
        assertEquals(28, vo.getAge());
        assertEquals("研发部", vo.getDeptName());
    }

    @Test
    @DisplayName("测试 selectList 与 selectListDynamic 批量查询与空结果保底")
    void testSelectListOverloads() {
        String statementId = "exec.userWithDept";

        // 1. selectList with DynamicQueryRequest
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("status", "ACTIVE"));
        request.addSort(SortItem.asc("age"));

        List<UserDeptVO> voList = executor.selectList(statementId, request, UserDeptVO.class);
        assertNotNull(voList);
        assertEquals(3, voList.size());
        assertEquals("Alice", voList.get(0).getUserName());
        assertEquals("Bob", voList.get(1).getUserName());
        assertEquals("Charlie", voList.get(2).getUserName());

        // 2. selectList with DynamicQueryRequest returning Map
        List<Map<String, Object>> mapList = executor.selectList(statementId, request);
        assertNotNull(mapList);
        assertEquals(3, mapList.size());

        // 3. selectListDynamic with DTO
        UserQueryDTO dto = new UserQueryDTO();
        dto.setAge(30);
        List<UserDeptVO> dtoList = executor.selectListDynamic(statementId, dto, UserDeptVO.class);
        assertNotNull(dtoList);
        assertEquals(2, dtoList.size()); // Charlie (35), David (40)

        // 4. 空结果保底返回空 List，严禁返回 null
        DynamicQueryRequest emptyRequest = new DynamicQueryRequest();
        emptyRequest.addCondition(QueryCondition.eq("userName", "NonExistentUser"));
        List<UserDeptVO> emptyResult = executor.selectList(statementId, emptyRequest, UserDeptVO.class);
        assertNotNull(emptyResult);
        assertTrue(emptyResult.isEmpty());
    }

    @Test
    @DisplayName("测试 selectOne 与 selectOneDynamic 单条查询及查无记录防御")
    void testSelectOneOverloads() {
        String statementId = "exec.userWithDept";

        // 1. selectOne with DynamicQueryRequest
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("userName", "Alice"));
        UserDeptVO alice = executor.selectOne(statementId, request, UserDeptVO.class);
        assertNotNull(alice);
        assertEquals("Alice", alice.getUserName());
        assertEquals(22, alice.getAge());

        // 2. selectOne with DynamicQueryRequest returning Map
        Map<String, Object> aliceMap = executor.selectOne(statementId, request);
        assertNotNull(aliceMap);
        assertEquals(22, ((Number) (aliceMap.get("age") != null ? aliceMap.get("age") : aliceMap.get("AGE"))).intValue());

        // 3. selectOneDynamic with DTO
        UserQueryDTO dto = new UserQueryDTO();
        dto.setUserName("Charlie");
        UserDeptVO charlie = executor.selectOneDynamic(statementId, dto, UserDeptVO.class);
        assertNotNull(charlie);
        assertEquals("Charlie", charlie.getUserName());

        // 4. 查无记录安全返回 null
        DynamicQueryRequest nonExistent = new DynamicQueryRequest();
        nonExistent.addCondition(QueryCondition.eq("userName", "NoOne"));
        UserDeptVO missing = executor.selectOne(statementId, nonExistent, UserDeptVO.class);
        assertNull(missing);
    }

    @Test
    @DisplayName("测试 count 与 countDynamic 记录数统计")
    void testCountOverloads() {
        String statementId = "exec.userWithDept";

        // 1. 无参 count
        long totalAll = executor.count(statementId);
        assertEquals(4L, totalAll);

        // 2. DynamicQueryRequest count
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.addCondition(QueryCondition.eq("status", "ACTIVE"));
        long activeCount = executor.count(statementId, request);
        assertEquals(3L, activeCount);

        // 3. DTO count
        UserQueryDTO dto = new UserQueryDTO();
        dto.setAge(30);
        long ageGe30Count = executor.count(statementId, dto);
        assertEquals(2L, ageGe30Count);
    }

    @Test
    @DisplayName("测试 JSqlParser AST 自适应条件追加（无 ${ew.customSqlSegment} 占位符）")
    void testAstAutoAppendWithoutPlaceholder() {
        String statementId = "exec.userWithoutEw";
        String sql = "SELECT u.id AS user_id, u.user_name, u.age, u.status, d.dept_name " +
                "FROM t_exec_user u " +
                "LEFT JOIN t_exec_dept d ON u.dept_id = d.id " +
                "WHERE u.status = 'ACTIVE'";

        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript(sql)
                .commandType(SqlCommandType.SELECT)
                .build());

        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.addCondition(QueryCondition.gt("age", 25)); // Bob (28), Charlie (35)
        request.addSort(SortItem.asc("age"));

        PageResult<UserDeptVO> result = executor.selectPage(statementId, request, UserDeptVO.class);
        assertNotNull(result);
        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals("Bob", result.getRecords().get(0).getUserName());
        assertEquals("Charlie", result.getRecords().get(1).getUserName());
    }

    @Test
    @DisplayName("测试外部动态 SQL 热替换（Hot Reload）与即时刷新生效")
    void testHotReloadAndCacheInvalidation() {
        String statementId = "exec.hotReloadTest";

        // 1. 注册初始版本：仅查研发部
        String sqlV1 = "SELECT u.id AS user_id, u.user_name, u.age, u.status, d.dept_name " +
                "FROM t_exec_user u " +
                "JOIN t_exec_dept d ON u.dept_id = d.id " +
                "WHERE d.dept_name = '研发部'";

        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript(sqlV1)
                .commandType(SqlCommandType.SELECT)
                .build());

        long v1Count = executor.count(statementId);
        assertEquals(2L, v1Count);

        // 2. 热替换新版本：改为市场部并测试动态追加
        String sqlV2 = "SELECT u.id AS user_id, u.user_name, u.age, u.status, d.dept_name " +
                "FROM t_exec_user u " +
                "JOIN t_exec_dept d ON u.dept_id = d.id " +
                "WHERE d.dept_name = '市场部'";

        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript(sqlV2)
                .commandType(SqlCommandType.SELECT)
                .build());

        // 验证热刷新后立即生效，旧缓存已被清空
        long v2Count = executor.count(statementId);
        assertEquals(2L, v2Count);

        DynamicQueryRequest req = new DynamicQueryRequest();
        req.addCondition(QueryCondition.eq("status", "ACTIVE"));
        PageResult<UserDeptVO> v2Result = executor.selectPage(statementId, req, UserDeptVO.class);
        assertNotNull(v2Result);
        assertEquals(1L, v2Result.getTotal()); // Charlie 属于市场部且为 ACTIVE
        assertEquals("Charlie", v2Result.getRecords().get(0).getUserName());
    }

    @Test
    @DisplayName("测试动态注销 SQL 后缓存失效与快速失败断言")
    void testRemoveStatementAndFailFast() {
        String statementId = "exec.tempStatement";
        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT u.id AS user_id, u.user_name FROM t_exec_user u ${ew.customSqlSegment}")
                .commandType(SqlCommandType.SELECT)
                .build());

        assertTrue(registry.hasStatement(statementId));
        assertEquals(4L, executor.count(statementId));

        // 注销语句
        boolean removed = registry.remove(statementId);
        assertTrue(removed);
        assertFalse(registry.hasStatement(statementId));

        // 再次调用时应当快速失败抛出 IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> executor.count(statementId));
    }
}
