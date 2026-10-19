package io.github.atengk.boot.mybatisplus.dynamicquery;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.mybatisplus.annotation.FieldEncrypt;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.DynamicQueryIgnore;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态查询与安全转译引擎全真 H2 对抗单元测试
 * 覆盖 13 种受控操作符全集在真实数据库执行、声明式 DTO 映射、多条件与排序组合、
 * 以及非法操作符、未公开列探测、非物理列、敏感字段、密文字段与全局开关拦截六重安全防卫
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("DynamicQueryHelper 动态查询安全转译引擎单元测试")
class DynamicQueryHelperTest {

    @Getter
    @Setter
    @ToString
    @TableName("t_dynamic_query_user")
    public static class DynamicTestUserEntity {
        @TableId
        private Long id;
        private String username;
        private Integer age;
        private String email;
        private Double score;

        // 敏感字段（启发式安全拦截）
        private String password;

        // 加密存储字段（密文不可动态运算）
        @FieldEncrypt
        private String phone;

        // 显式忽略防卫字段
        @DynamicQueryIgnore
        private String secretToken;

        // 逻辑删除字段（框架托管，禁止外部越权）
        @TableLogic
        private Integer deleted;

        // 非物理映射字段（exist = false）
        @TableField(exist = false)
        private String memoryExtra;
    }

    public interface DynamicTestUserMapper extends AtengBaseMapper<DynamicTestUserEntity> {
    }

    @Getter
    @Setter
    public static class TestUserQueryDTO extends PageQuery {
        @QueryField(property = "username", operator = QueryOperator.LIKE)
        private String name;

        @QueryField(property = "age", operator = QueryOperator.GE)
        private Integer minAge;

        @QueryField(property = "email", operator = QueryOperator.IS_NOT_NULL)
        private Boolean emailNotNull;

        // 试图探测敏感字段
        @QueryField(property = "password")
        private String pwd;
    }

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void initH2Database() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_dynamic_query;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_dynamic_query_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    age INT,
                    email VARCHAR(128),
                    score DOUBLE,
                    password VARCHAR(128),
                    phone VARCHAR(128),
                    secret_token VARCHAR(128),
                    deleted INT DEFAULT 0
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(DynamicTestUserMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @AfterAll
    static void destroyH2Database() throws Exception {
        if (dataSource != null) {
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                stmt.execute("DROP TABLE IF EXISTS t_dynamic_query_user");
            }
        }
        DynamicQueryHelper.clearCache();
    }

    @BeforeEach
    void seedData() throws Exception {
        DynamicQueryHelper.setEnabled(true);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_dynamic_query_user");
            // 插入 5 条全真测试基线数据
            stmt.execute("INSERT INTO t_dynamic_query_user VALUES (1, 'alice', 20, 'alice@test.com', 85.5, 'pwd1', '13800000001', 'token1', 0)");
            stmt.execute("INSERT INTO t_dynamic_query_user VALUES (2, 'bob', 25, 'bob@test.com', 92.0, 'pwd2', '13800000002', 'token2', 0)");
            stmt.execute("INSERT INTO t_dynamic_query_user VALUES (3, 'charlie', 30, NULL, 78.0, 'pwd3', '13800000003', 'token3', 0)");
            stmt.execute("INSERT INTO t_dynamic_query_user VALUES (4, 'david', 35, 'david@company.org', 60.0, 'pwd4', '13800000004', 'token4', 0)");
            stmt.execute("INSERT INTO t_dynamic_query_user VALUES (5, 'eve', 40, 'eve@test.com', 99.0, 'pwd5', '13800000005', 'token5', 0)");
        }
    }

    @AfterEach
    void cleanState() {
        DynamicQueryHelper.setEnabled(true);
    }

    // ==========================================
    // 1. 13 种受控操作符全集在真实 H2 数据库执行验证
    // ==========================================

    @Test
    @DisplayName("1. 操作符 EQ: age = 25 查出 bob")
    void shouldExecuteEq() {
        DynamicQueryRequest request = new DynamicQueryRequest().eq("age", 25);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).getUsername()).isEqualTo("bob");
        }
    }

    @Test
    @DisplayName("2. 操作符 NE: username <> 'alice' 查出 4 条记录")
    void shouldExecuteNe() {
        DynamicQueryRequest request = new DynamicQueryRequest().ne("username", "alice");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).hasSize(4);
            assertThat(list).noneMatch(u -> "alice".equals(u.getUsername()));
        }
    }

    @Test
    @DisplayName("3. 操作符 GT: age > 30 查出 david 与 eve")
    void shouldExecuteGt() {
        DynamicQueryRequest request = new DynamicQueryRequest().gt("age", 30);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("david", "eve");
        }
    }

    @Test
    @DisplayName("4. 操作符 GE: age >= 30 查出 charlie, david, eve")
    void shouldExecuteGe() {
        DynamicQueryRequest request = new DynamicQueryRequest().ge("age", 30);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("charlie", "david", "eve");
        }
    }

    @Test
    @DisplayName("5. 操作符 LT: age < 25 查出 alice")
    void shouldExecuteLt() {
        DynamicQueryRequest request = new DynamicQueryRequest().lt("age", 25);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactly("alice");
        }
    }

    @Test
    @DisplayName("6. 操作符 LE: age <= 25 查出 alice 与 bob")
    void shouldExecuteLe() {
        DynamicQueryRequest request = new DynamicQueryRequest().le("age", 25);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "bob");
        }
    }

    @Test
    @DisplayName("7. 操作符 LIKE: email LIKE '%test%' 查出 alice, bob, eve")
    void shouldExecuteLike() {
        DynamicQueryRequest request = new DynamicQueryRequest().like("email", "test");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "bob", "eve");
        }
    }

    @Test
    @DisplayName("8. 操作符 LIKE_LEFT: email LIKE '%org' 查出 david")
    void shouldExecuteLikeLeft() {
        DynamicQueryRequest request = new DynamicQueryRequest().likeLeft("email", "org");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactly("david");
        }
    }

    @Test
    @DisplayName("9. 操作符 LIKE_RIGHT: username LIKE 'al%' 查出 alice")
    void shouldExecuteLikeRight() {
        DynamicQueryRequest request = new DynamicQueryRequest().likeRight("username", "al");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactly("alice");
        }
    }

    @Test
    @DisplayName("10. 操作符 IN: age IN (20, 30) 查出 alice 与 charlie")
    void shouldExecuteIn() {
        DynamicQueryRequest request = new DynamicQueryRequest().in("age", Arrays.asList(20, 30));
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "charlie");
        }
    }

    @Test
    @DisplayName("11. 操作符 NOT_IN: age NOT IN (20, 25, 30) 查出 david 与 eve")
    void shouldExecuteNotIn() {
        DynamicQueryRequest request = new DynamicQueryRequest().notIn("age", Arrays.asList(20, 25, 30));
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("david", "eve");
        }
    }

    @Test
    @DisplayName("12. 操作符 BETWEEN: score BETWEEN 80.0 AND 95.0 查出 alice 与 bob")
    void shouldExecuteBetween() {
        DynamicQueryRequest request = new DynamicQueryRequest().between("score", 80.0, 95.0);
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "bob");
        }
    }

    @Test
    @DisplayName("13. 操作符 IS_NULL 与 IS_NOT_NULL")
    void shouldExecuteIsNullAndIsNotNull() {
        // IS_NULL: 只有 charlie 的 email 为 NULL
        DynamicQueryRequest nullReq = new DynamicQueryRequest().isNull("email");
        QueryWrapper<DynamicTestUserEntity> nullWrapper = DynamicQueryHelper.buildWrapper(nullReq, DynamicTestUserEntity.class);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> nullList = mapper.selectList(nullWrapper);
            assertThat(nullList).extracting(DynamicTestUserEntity::getUsername).containsExactly("charlie");
        }

        // IS_NOT_NULL: 其余 4 条
        DynamicQueryRequest notNullReq = new DynamicQueryRequest().isNotNull("email");
        QueryWrapper<DynamicTestUserEntity> notNullWrapper = DynamicQueryHelper.buildWrapper(notNullReq, DynamicTestUserEntity.class);
        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> notNullList = mapper.selectList(notNullWrapper);
            assertThat(notNullList).hasSize(4);
            assertThat(notNullList).noneMatch(u -> "charlie".equals(u.getUsername()));
        }
    }

    // ==========================================
    // 2. 多条件组合与动态排序验证
    // ==========================================

    @Test
    @DisplayName("多条件组合与排序：age > 20 且 email LIKE '%test%'，按 score DESC 排序")
    void shouldCombineConditionsAndSorts() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.gt("age", 20);
        request.like("email", "test");
        request.addDesc("score");

        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);

            // 匹配到的记录为 bob(score=92) 与 eve(score=99)，降序排第一应为 eve
            assertThat(list).hasSize(2);
            assertThat(list.get(0).getUsername()).isEqualTo("eve");
            assertThat(list.get(1).getUsername()).isEqualTo("bob");
        }
    }

    // ==========================================
    // 3. 声明式 @QueryField DTO 驱动测试
    // ==========================================

    @Test
    @DisplayName("DTO 声明式驱动：根据 @QueryField 注解自动解析条件与忽略 null")
    void shouldBuildWrapperFromDto() {
        TestUserQueryDTO dto = new TestUserQueryDTO();
        dto.setName("li"); // 匹配 alice 与 charlie
        dto.setMinAge(25); // age >= 25 -> alice(20) 排除，剩 charlie(30)
        dto.setEmailNotNull(null); // ignoreNull 为 true，应自动跳过
        dto.setPwd("123456"); // password 为敏感字段，必须被安全拦截不参与 SQL

        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(dto, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);

            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactly("charlie");
        }
    }

    // ==========================================
    // 4. 六重安全防卫与对抗性测试
    // ==========================================

    @ParameterizedTest
    @ValueSource(strings = {
            "username; DROP TABLE t_dynamic_query_user;",
            "name' OR '1'='1",
            "age--",
            "user.name",
            "func()"
    })
    @DisplayName("防线一/二：非法特殊字符与 SQL 注入语法必须被平滑过滤")
    void shouldFilterIllegalOrInjectedProperties(String dirtyField) {
        DynamicQueryRequest request = new DynamicQueryRequest().eq(dirtyField, "val");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        // 注入字段被过滤，wrapper 为空，不会包含该 WHERE 条件
        assertNotNull(wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().isEmpty());
    }

    @Test
    @DisplayName("防线三：未在实体物理字段中声明的未知列直接拦截忽略")
    void shouldIgnoreUnmappedColumns() {
        DynamicQueryRequest request = new DynamicQueryRequest().eq("unknownCreditCard", "123");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);
        assertTrue(wrapper.getSqlSegment().isEmpty());
    }

    @Test
    @DisplayName("防线四：@TableField(exist = false) 内存属性禁止参与动态条件")
    void shouldIgnoreNonPhysicalTableFields() {
        DynamicQueryRequest request = new DynamicQueryRequest().eq("memoryExtra", "extraVal");
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);
        assertTrue(wrapper.getSqlSegment().isEmpty());
    }

    @Test
    @DisplayName("防线五：敏感字段拦截（@DynamicQueryIgnore、@FieldEncrypt、逻辑删除与启发式 password）")
    void shouldBlockSensitiveColumns() {
        DynamicQueryRequest request = new DynamicQueryRequest()
                .eq("secretToken", "secret") // @DynamicQueryIgnore
                .eq("phone", "13800000000")   // @FieldEncrypt 密文
                .eq("password", "123456")     // 启发式 password 拦截
                .eq("deleted", 1);           // 逻辑删除托管列拦截

        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class);

        // 所有 4 个敏感列全部被安全拦截，生成的 SQL 条件必须为空
        assertTrue(wrapper.getSqlSegment().isEmpty());
    }

    @Test
    @DisplayName("防线六：全局开关禁用时防御性抛出 BusinessException")
    void shouldThrowExceptionWhenGloballyDisabled() {
        DynamicQueryHelper.setEnabled(false);
        assertFalse(DynamicQueryHelper.isEnabled());

        DynamicQueryRequest request = new DynamicQueryRequest().eq("age", 20);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> DynamicQueryHelper.buildWrapper(request, DynamicTestUserEntity.class));
        assertThat(ex.getMessage()).contains("动态查询引擎已全局禁用");
    }

    @Test
    @DisplayName("操作符解析容错与非法值拦截")
    void shouldParseQueryOperators() {
        assertThat(QueryOperator.fromString("eq")).isEqualTo(QueryOperator.EQ);
        assertThat(QueryOperator.fromString("EQ")).isEqualTo(QueryOperator.EQ);
        assertThat(QueryOperator.fromString("like_left")).isEqualTo(QueryOperator.LIKE_LEFT);
        assertThat(QueryOperator.fromString("LIKE-RIGHT")).isEqualTo(QueryOperator.LIKE_RIGHT);
        assertThat(QueryOperator.fromString(null)).isEqualTo(QueryOperator.EQ);

        assertThrows(IllegalArgumentException.class, () -> QueryOperator.fromString("DROP_TABLE"));
    }

    // ==========================================
    // 5. URL Query 字符串与作用域 OR 嵌套真实 H2 执行
    // ==========================================

    @Test
    @DisplayName("URL Query 冒号语法真实 H2 执行：username:like=li 查出 alice 与 charlie")
    void shouldExecuteUrlQueryString() {
        String query = "username:like=li";
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(query, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "charlie");
        }
    }

    @Test
    @DisplayName("URL Query 波浪线 between 语法真实 H2 执行：score:between=80.0~95.0")
    void shouldExecuteUrlQueryBetweenWithTilde() {
        String query = "score:between=80.0~95.0";
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(query, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "bob");
        }
    }

    @Test
    @DisplayName("作用域 OR 分组真实 H2 执行：or_1:username:like=li&or_1:email:like=org")
    void shouldExecuteOrGroupInH2() {
        // (username LIKE '%li%' OR email LIKE '%org%') -> alice, charlie, david
        String query = "or_1:username:like=li&or_1:email:like=org";
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(query, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("alice", "charlie", "david");
        }
    }

    @Test
    @DisplayName("复合嵌套查询真实 H2 执行：常规 AND + 作用域 OR 嵌套")
    void shouldExecuteCombinedAndWithOrGroupInH2() {
        // age > 20 AND (username LIKE '%li%' OR email LIKE '%org%')
        // alice(20) 虽满足 username LIKE '%li%' 但不满足 age > 20，故排除
        // 最终仅查出 charlie(30) 与 david(35)
        String query = "age:gt=20&or_1:username:like=li&or_1:email:like=org";
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(query, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactlyInAnyOrder("charlie", "david");
        }
    }

    @Test
    @DisplayName("多组作用域 OR 分组真实 H2 执行：or_1 与 or_2 逻辑交叉")
    void shouldExecuteMultipleOrGroupsInH2() {
        // (username = 'alice' OR username = 'bob') AND (age = 20 OR age = 30)
        // 只有 alice 满足 age=20
        String query = "or_1:username:eq=alice&or_1:username:eq=bob&or_2:age:eq=20&or_2:age:eq=30";
        QueryWrapper<DynamicTestUserEntity> wrapper = DynamicQueryHelper.buildWrapper(query, DynamicTestUserEntity.class);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            DynamicTestUserMapper mapper = session.getMapper(DynamicTestUserMapper.class);
            List<DynamicTestUserEntity> list = mapper.selectList(wrapper);
            assertThat(list).extracting(DynamicTestUserEntity::getUsername).containsExactly("alice");
        }
    }
}
