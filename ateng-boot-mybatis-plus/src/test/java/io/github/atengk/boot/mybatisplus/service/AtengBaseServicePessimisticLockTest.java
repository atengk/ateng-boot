package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.mybatisplus.enums.LockMode;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.PessimisticLockUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
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

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 悲观行级锁 Service 门面与 VO 投影重载单元测试
 * 验证 getByIdForUpdate / listForUpdate / getOneForUpdate 的 VO 投影重载、默认锁模式、空安全保底及参数防御
 *
 * @author Ateng
 * @since 2026-11-13
 */
@DisplayName("AtengBaseService 悲观行锁与 VO 投影单元测试")
class AtengBaseServicePessimisticLockTest {

    private static DataSource dataSource;
    private static SqlSession sqlSession;
    private static TestLockTaskMapper taskMapper;
    private static TestLockTaskService taskService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @TableName("t_lock_service_task")
    static class TestLockTaskEntity implements Serializable {
        @TableId
        private Long id;
        private String taskCode;
        private String status;
        private Integer priority;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestLockTaskVO implements Serializable {
        private Long id;
        private String taskCode;
        private String status;
        private Integer priority;
    }

    interface TestLockTaskMapper extends AtengBaseMapper<TestLockTaskEntity> {
    }

    interface TestLockTaskService extends AtengBaseService<TestLockTaskEntity> {
    }

    static class TestLockTaskServiceImpl extends AtengServiceImpl<TestLockTaskMapper, TestLockTaskEntity>
            implements TestLockTaskService {
        public TestLockTaskServiceImpl(TestLockTaskMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_pessimistic_lock_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_lock_service_task");
            stmt.execute("""
                CREATE TABLE t_lock_service_task (
                    id BIGINT PRIMARY KEY,
                    task_code VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    priority INT NOT NULL
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("pessimistic_lock_test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(TestLockTaskMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession(true);

        taskMapper = sqlSession.getMapper(TestLockTaskMapper.class);
        taskService = new TestLockTaskServiceImpl(taskMapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        PessimisticLockUtil.setDbType(DbType.H2);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_lock_service_task");
        }
        taskMapper.insert(new TestLockTaskEntity(1L, "TASK-001", "PENDING", 10));
        taskMapper.insert(new TestLockTaskEntity(2L, "TASK-002", "PENDING", 20));
        taskMapper.insert(new TestLockTaskEntity(3L, "TASK-003", "DONE", 30));
    }

    @AfterEach
    void tearDown() {
        PessimisticLockUtil.resetDbType();
    }

    @Test
    @DisplayName("1. 验证 getByIdForUpdate VO 投影重载、空安全与参数快速失败")
    void shouldProjectVoForGetByIdForUpdate() {
        TestLockTaskVO voDefault = taskService.getByIdForUpdate(1L, TestLockTaskVO.class);
        assertThat(voDefault).isNotNull();
        assertThat(voDefault.getTaskCode()).isEqualTo("TASK-001");
        assertThat(voDefault.getPriority()).isEqualTo(10);

        TestLockTaskVO voExplicit = taskService.getByIdForUpdate(2L, LockMode.FOR_UPDATE, TestLockTaskVO.class);
        assertThat(voExplicit).isNotNull();
        assertThat(voExplicit.getTaskCode()).isEqualTo("TASK-002");

        // 空 ID 或未命中安全返回 null
        assertThat(taskService.getByIdForUpdate(null, TestLockTaskVO.class)).isNull();
        assertThat(taskService.getByIdForUpdate(999L, LockMode.FOR_UPDATE, TestLockTaskVO.class)).isNull();

        // 参数合法性校验快速失败
        assertThatThrownBy(() -> taskService.getByIdForUpdate(1L, (Class<TestLockTaskVO>) null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> taskService.getByIdForUpdate(1L, null, TestLockTaskVO.class))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("2. 验证 listForUpdate VO 投影重载、空集合保底与条件排重")
    void shouldProjectVoListForListForUpdate() {
        LambdaQueryWrapper<TestLockTaskEntity> pendingWrapper = Wrappers.lambdaQuery(TestLockTaskEntity.class)
                .eq(TestLockTaskEntity::getStatus, "PENDING")
                .orderByAsc(TestLockTaskEntity::getId);

        List<TestLockTaskVO> defaultVoList = taskService.listForUpdate(pendingWrapper, TestLockTaskVO.class);
        assertThat(defaultVoList).hasSize(2);
        assertThat(defaultVoList).extracting(TestLockTaskVO::getTaskCode)
                .containsExactly("TASK-001", "TASK-002");

        LambdaQueryWrapper<TestLockTaskEntity> lockModeWrapper = Wrappers.lambdaQuery(TestLockTaskEntity.class)
                .eq(TestLockTaskEntity::getStatus, "PENDING")
                .orderByDesc(TestLockTaskEntity::getPriority);
        List<TestLockTaskVO> explicitVoList = taskService.listForUpdate(lockModeWrapper, LockMode.FOR_UPDATE, TestLockTaskVO.class);
        assertThat(explicitVoList).hasSize(2);
        assertThat(explicitVoList.get(0).getTaskCode()).isEqualTo("TASK-002");

        // 未命中保底返回空集合
        List<TestLockTaskVO> emptyList = taskService.listForUpdate(
                Wrappers.lambdaQuery(TestLockTaskEntity.class).eq(TestLockTaskEntity::getStatus, "NON_EXISTENT"),
                LockMode.FOR_UPDATE,
                TestLockTaskVO.class
        );
        assertThat(emptyList).isNotNull().isEmpty();

        assertThatThrownBy(() -> taskService.listForUpdate(pendingWrapper, (Class<TestLockTaskVO>) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("3. 验证 getOneForUpdate 默认 LockMode VO 投影重载与未命中保底 null")
    void shouldProjectVoForGetOneForUpdate() {
        TestLockTaskVO hitVo = taskService.getOneForUpdate(
                Wrappers.lambdaQuery(TestLockTaskEntity.class).eq(TestLockTaskEntity::getTaskCode, "TASK-003"),
                TestLockTaskVO.class
        );
        assertThat(hitVo).isNotNull();
        assertThat(hitVo.getStatus()).isEqualTo("DONE");

        TestLockTaskVO missVo = taskService.getOneForUpdate(
                Wrappers.lambdaQuery(TestLockTaskEntity.class).eq(TestLockTaskEntity::getTaskCode, "NONE"),
                TestLockTaskVO.class
        );
        assertThat(missVo).isNull();

        assertThatThrownBy(() -> taskService.getOneForUpdate(
                Wrappers.lambdaQuery(TestLockTaskEntity.class),
                (Class<TestLockTaskVO>) null
        )).isInstanceOf(NullPointerException.class);
    }
}
