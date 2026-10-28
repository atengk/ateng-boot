package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryCondition;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtengBaseService 单表动态查询直出引擎全维单元测试与 H2 集成测试
 * 覆盖：
 * 1. pageDynamic 实体分页与 VO 投影强类型直出
 * 2. 普通 DTO 多态入参自动转译与分页排序
 * 3. listDynamic 列表查询、保底空集合与 VO 投影
 * 4. getOneDynamic 单条查询与空值防卫
 * 5. countDynamic 条件记录数统计
 * 6. 异常边界防御（空 VO 类型抛出 NPE）
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("AtengBaseService 单表动态查询直出测试")
class AtengBaseServiceDynamicTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession sqlSession;
    private static TestUserService userService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @TableName("t_service_dynamic_user")
    static class TestUserEntity implements Serializable {
        @TableId
        private Long id;
        private String username;
        private String email;
        private Integer age;
        private Integer status;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestUserVO implements Serializable {
        private Long id;
        private String username;
        private String email;
        private Integer age;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @lombok.EqualsAndHashCode(callSuper = false)
    static class TestUserSearchDTO extends PageQuery {
        @QueryField(operator = QueryOperator.LIKE)
        private String username;

        @QueryField(operator = QueryOperator.GE, property = "age")
        private Integer minAge;
    }

    interface TestUserMapper extends AtengBaseMapper<TestUserEntity> {
    }

    interface TestUserService extends AtengBaseService<TestUserEntity> {
    }

    static class TestUserServiceImpl extends AtengServiceImpl<TestUserMapper, TestUserEntity> implements TestUserService {
        public TestUserServiceImpl(TestUserMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_dynamic_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_service_dynamic_user");
            stmt.execute("""
                CREATE TABLE t_service_dynamic_user (
                    id BIGINT PRIMARY KEY,
                    username VARCHAR(64) NOT NULL,
                    email VARCHAR(128) NOT NULL,
                    age INT NOT NULL,
                    status INT NOT NULL
                )
            """);
            stmt.execute("INSERT INTO t_service_dynamic_user VALUES (1, 'admin', 'admin@example.com', 30, 1)");
            stmt.execute("INSERT INTO t_service_dynamic_user VALUES (2, 'alice', 'alice@example.com', 22, 1)");
            stmt.execute("INSERT INTO t_service_dynamic_user VALUES (3, 'bob', 'bob@example.com', 28, 0)");
            stmt.execute("INSERT INTO t_service_dynamic_user VALUES (4, 'alex', 'alex@example.com', 35, 1)");
            stmt.execute("INSERT INTO t_service_dynamic_user VALUES (5, 'charlie', 'charlie@example.com', 19, 1)");
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("service_test", new JdbcTransactionFactory(), dataSource));

        com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor interceptor = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));
        configuration.addInterceptor(interceptor);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(TestUserMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);

        TestUserMapper mapper = sqlSession.getMapper(TestUserMapper.class);
        userService = new TestUserServiceImpl(mapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @Test
    @DisplayName("1. pageDynamic 实体分页：支持结构化请求，准确应用条件与排序")
    void shouldPageDynamicWithEntities() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(2L);
        request.setSorts(List.of(SortItem.desc("age")));
        request.setConditions(List.of(
                new QueryCondition("status", QueryOperator.EQ, 1)
        ));

        PageResult<TestUserEntity> result = userService.pageDynamic(request);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(4L);
        assertThat(result.getCurrent()).isEqualTo(1L);
        assertThat(result.getSize()).isEqualTo(2L);
        assertThat(result.getRecords()).hasSize(2);
        // 按年龄倒序：alex(35), admin(30)
        assertThat(result.getRecords().get(0).getUsername()).isEqualTo("alex");
        assertThat(result.getRecords().get(1).getUsername()).isEqualTo("admin");
    }

    @Test
    @DisplayName("2. pageDynamic VO 投影直出：一步到位转换为强类型 VO，数据脱敏解耦")
    void shouldPageDynamicWithVOProjection() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setCurrent(1L);
        request.setSize(10L);
        request.setConditions(List.of(
                new QueryCondition("username", QueryOperator.LIKE, "al")
        ));
        request.setSorts(List.of(SortItem.asc("age")));

        PageResult<TestUserVO> voPage = userService.pageDynamic(request, TestUserVO.class);

        assertThat(voPage).isNotNull();
        assertThat(voPage.getTotal()).isEqualTo(2L); // alice(22), alex(35)
        assertThat(voPage.getRecords()).hasSize(2);
        assertThat(voPage.getRecords().get(0)).isInstanceOf(TestUserVO.class);
        assertThat(voPage.getRecords().get(0).getUsername()).isEqualTo("alice");
        assertThat(voPage.getRecords().get(0).getAge()).isEqualTo(22);
        assertThat(voPage.getRecords().get(1).getUsername()).isEqualTo("alex");
        assertThat(voPage.getRecords().get(1).getAge()).isEqualTo(35);
    }

    @Test
    @DisplayName("3. pageDynamic DTO 多态支持：直接接收普通 @QueryField DTO 对象")
    void shouldPageDynamicWithSearchDTO() {
        TestUserSearchDTO dto = new TestUserSearchDTO();
        dto.setCurrent(1L);
        dto.setSize(5L);
        dto.setUsername("a"); // 包含 a：admin, alice, alex, charlie
        dto.setMinAge(25);    // 年龄 >= 25：admin(30), alex(35)

        PageResult<TestUserVO> result = userService.pageDynamic(dto, TestUserVO.class);

        assertThat(result).isNotNull();
        assertThat(result.getTotal()).isEqualTo(2L);
        assertThat(result.getRecords())
                .extracting(TestUserVO::getUsername)
                .containsExactlyInAnyOrder("admin", "alex");
    }

    @Test
    @DisplayName("4. listDynamic 列表查询：实体列表与强类型 VO 列表")
    void shouldListDynamicWithEntitiesAndVOs() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setConditions(List.of(
                new QueryCondition("status", QueryOperator.EQ, 0)
        ));

        List<TestUserEntity> entities = userService.listDynamic(request);
        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).getUsername()).isEqualTo("bob");

        List<TestUserVO> vos = userService.listDynamic(request, TestUserVO.class);
        assertThat(vos).hasSize(1);
        assertThat(vos.get(0).getUsername()).isEqualTo("bob");
        assertThat(vos.get(0).getEmail()).isEqualTo("bob@example.com");
    }

    @Test
    @DisplayName("5. listDynamic 空数据保底：无匹配记录时返回空集合，严禁返回 null")
    void shouldReturnEmptyListWhenNoMatches() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setConditions(List.of(
                new QueryCondition("age", QueryOperator.GT, 100)
        ));

        List<TestUserEntity> entities = userService.listDynamic(request);
        assertThat(entities).isNotNull().isEmpty();

        List<TestUserVO> vos = userService.listDynamic(request, TestUserVO.class);
        assertThat(vos).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("6. getOneDynamic 单条查询：命中返回单个对象，未命中安全返回 null")
    void shouldGetOneDynamicCorrectly() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "admin")
        ));

        TestUserEntity entity = userService.getOneDynamic(request);
        assertThat(entity).isNotNull();
        assertThat(entity.getEmail()).isEqualTo("admin@example.com");

        TestUserVO vo = userService.getOneDynamic(request, TestUserVO.class);
        assertThat(vo).isNotNull();
        assertThat(vo.getUsername()).isEqualTo("admin");

        // 未命中测试
        DynamicQueryRequest notFoundRequest = new DynamicQueryRequest();
        notFoundRequest.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "non_existing_user")
        ));

        assertThat(userService.getOneDynamic(notFoundRequest)).isNull();
        assertThat(userService.getOneDynamic(notFoundRequest, TestUserVO.class)).isNull();
    }

    @Test
    @DisplayName("7. countDynamic 记录数统计：精准返回满足条件的记录总数")
    void shouldCountDynamicCorrectly() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        request.setConditions(List.of(
                new QueryCondition("age", QueryOperator.GE, 25)
        ));

        long count = userService.countDynamic(request);
        // age >= 25: admin(30), bob(28), alex(35) -> 3条
        assertThat(count).isEqualTo(3L);

        TestUserSearchDTO dto = new TestUserSearchDTO();
        dto.setMinAge(30);
        long countDto = userService.countDynamic(dto);
        // age >= 30: admin(30), alex(35) -> 2条
        assertThat(countDto).isEqualTo(2L);
    }

    @Test
    @DisplayName("8. 防御性断言：传入 null voClass 时显式抛出 NPE")
    void shouldThrowNullPointerExceptionWhenVoClassIsNull() {
        DynamicQueryRequest request = new DynamicQueryRequest();
        assertThatThrownBy(() -> userService.pageDynamic(request, (Class<TestUserVO>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voClass 不能为 null");

        assertThatThrownBy(() -> userService.listDynamic(request, (Class<TestUserVO>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voClass 不能为 null");

        assertThatThrownBy(() -> userService.getOneDynamic(request, (Class<TestUserVO>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voClass 不能为 null");
    }

    @Test
    @DisplayName("9. existsDynamic 存在性判断：物理短路 LIMIT 1 判定命中与未命中")
    void shouldExistsDynamicCorrectly() {
        // 1. 基于 Request 存在性判断：命中记录
        DynamicQueryRequest hitRequest = new DynamicQueryRequest();
        hitRequest.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "admin"),
                new QueryCondition("status", QueryOperator.EQ, 1)
        ));
        assertThat(userService.existsDynamic(hitRequest)).isTrue();

        // 2. 基于 Request 存在性判断：未命中记录
        DynamicQueryRequest missRequest = new DynamicQueryRequest();
        missRequest.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "ghost_user")
        ));
        assertThat(userService.existsDynamic(missRequest)).isFalse();

        // 3. 基于 Request 为 null 返回 false
        assertThat(userService.existsDynamic((DynamicQueryRequest) null)).isFalse();

        // 4. 基于 DTO 存在性判断：命中记录
        TestUserSearchDTO hitDto = new TestUserSearchDTO();
        hitDto.setUsername("al"); // LIKE %al% -> alice, alex
        hitDto.setMinAge(30);     // alex(35)
        assertThat(userService.existsDynamic(hitDto)).isTrue();

        // 5. 基于 DTO 存在性判断：未命中记录
        TestUserSearchDTO missDto = new TestUserSearchDTO();
        missDto.setUsername("ghost");
        assertThat(userService.existsDynamic(missDto)).isFalse();

        // 6. 基于 DTO 为 null 返回 false
        assertThat(userService.existsDynamic((Object) null)).isFalse();
    }

    @Test
    @DisplayName("10. getById(id, voClass) 强类型 VO 一步直出：存在映射 VO，不存在返回 null")
    void shouldGetByIdVoCorrectly() {
        // 1. 命中记录
        TestUserVO vo = userService.getById(1L, TestUserVO.class);
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getUsername()).isEqualTo("admin");
        assertThat(vo.getEmail()).isEqualTo("admin@example.com");
        assertThat(vo.getAge()).isEqualTo(30);

        // 2. 不存在记录安全返回 null
        assertThat(userService.getById(999L, TestUserVO.class)).isNull();

        // 3. id 为 null 安全返回 null
        assertThat(userService.getById(null, TestUserVO.class)).isNull();

        // 4. voClass 为 null 抛出 NPE
        assertThatThrownBy(() -> userService.getById(1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voClass 不能为 null");
    }

    @Test
    @DisplayName("11. listByIds(idList, voClass) 批量 VO 投影：保底返回空列表，严禁返回 null")
    void shouldListByIdsVoCorrectly() {
        // 1. 批量命中
        List<TestUserVO> voList = userService.listByIds(List.of(1L, 2L, 4L), TestUserVO.class);
        assertThat(voList).hasSize(3);
        assertThat(voList.stream().map(TestUserVO::getUsername).toList())
                .containsExactlyInAnyOrder("admin", "alice", "alex");

        // 2. 查无匹配记录保底返回空列表
        List<TestUserVO> emptyResult = userService.listByIds(List.of(998L, 999L), TestUserVO.class);
        assertThat(emptyResult).isNotNull().isEmpty();

        // 3. 入参为空集合保底返回空列表
        assertThat(userService.listByIds(List.of(), TestUserVO.class)).isNotNull().isEmpty();

        // 4. 入参为 null 保底返回空列表
        assertThat(userService.listByIds(null, TestUserVO.class)).isNotNull().isEmpty();

        // 5. voClass 为 null 抛出 NPE
        assertThatThrownBy(() -> userService.listByIds(List.of(1L), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("voClass 不能为 null");
    }

    @Test
    @DisplayName("12. list / getOne Wrapper 扩展 VO 投影与单条控制")
    void shouldListAndGetOneWrapperVoCorrectly() {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TestUserEntity> wrapper =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TestUserEntity>lambdaQuery()
                        .eq(TestUserEntity::getStatus, 1);

        // 1. list(wrapper, voClass)
        List<TestUserVO> activeList = userService.list(wrapper, TestUserVO.class);
        assertThat(activeList).hasSize(4);

        // 2. getOne(wrapper, voClass, false) 宽容模式（多条返回首条）
        TestUserVO firstVo = userService.getOne(wrapper, TestUserVO.class, false);
        assertThat(firstVo).isNotNull();

        // 3. getOne(wrapper, voClass, true) 严格模式（多条严正抛出 TooManyResultsException）
        assertThatThrownBy(() -> userService.getOne(wrapper, TestUserVO.class, true))
                .isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);

        // 4. getOne 唯一匹配成功返回
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TestUserEntity> singleWrapper =
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TestUserEntity>lambdaQuery()
                        .eq(TestUserEntity::getUsername, "charlie");
        TestUserVO charlieVo = userService.getOne(singleWrapper, TestUserVO.class, true);
        assertThat(charlieVo).isNotNull();
        assertThat(charlieVo.getUsername()).isEqualTo("charlie");
    }

    @Test
    @DisplayName("13. getOneDynamic 支持 throwEx 开关：严格快速失败与宽容返回首条")
    void shouldHandleGetOneDynamicWithThrowExSwitch() {
        // 条件匹配多条 (status = 1)
        DynamicQueryRequest multiRequest = new DynamicQueryRequest();
        multiRequest.setConditions(List.of(
                new QueryCondition("status", QueryOperator.EQ, 1)
        ));

        // 1. Request + throwEx = true 严正抛出异常
        assertThatThrownBy(() -> userService.getOneDynamic(multiRequest, true))
                .isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);
        assertThatThrownBy(() -> userService.getOneDynamic(multiRequest, TestUserVO.class, true))
                .isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);

        // 2. Request + throwEx = false 宽松返回首条
        TestUserEntity firstEntity = userService.getOneDynamic(multiRequest, false);
        assertThat(firstEntity).isNotNull();
        TestUserVO firstVo = userService.getOneDynamic(multiRequest, TestUserVO.class, false);
        assertThat(firstVo).isNotNull();

        // 3. DTO + throwEx = true 匹配多条抛出异常
        TestUserSearchDTO multiDto = new TestUserSearchDTO();
        multiDto.setMinAge(20); // admin(30), alice(22), bob(28), alex(35) -> 4条
        assertThatThrownBy(() -> userService.getOneDynamic(multiDto, true))
                .isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);
        assertThatThrownBy(() -> userService.getOneDynamic(multiDto, TestUserVO.class, true))
                .isInstanceOf(org.apache.ibatis.exceptions.TooManyResultsException.class);

        // 4. DTO + throwEx = false 宽松返回首条
        assertThat(userService.getOneDynamic(multiDto, false)).isNotNull();
        assertThat(userService.getOneDynamic(multiDto, TestUserVO.class, false)).isNotNull();

        // 5. 单条匹配成功
        DynamicQueryRequest singleRequest = new DynamicQueryRequest();
        singleRequest.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "admin")
        ));
        TestUserVO adminVo = userService.getOneDynamic(singleRequest, TestUserVO.class, true);
        assertThat(adminVo).isNotNull();
        assertThat(adminVo.getUsername()).isEqualTo("admin");

        // 6. 无匹配记录返回 null
        DynamicQueryRequest notFoundRequest = new DynamicQueryRequest();
        notFoundRequest.setConditions(List.of(
                new QueryCondition("username", QueryOperator.EQ, "ghost")
        ));
        assertThat(userService.getOneDynamic(notFoundRequest, true)).isNull();
        assertThat(userService.getOneDynamic(notFoundRequest, TestUserVO.class, true)).isNull();
        assertThat(userService.getOneDynamic(notFoundRequest, false)).isNull();
        assertThat(userService.getOneDynamic(notFoundRequest, TestUserVO.class, false)).isNull();
    }
}
