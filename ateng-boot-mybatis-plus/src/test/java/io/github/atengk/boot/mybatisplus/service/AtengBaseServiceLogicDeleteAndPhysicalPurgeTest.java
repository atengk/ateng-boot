package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.spi.CurrentUserProvider;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
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
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 逻辑删除全生命周期对称门面与物理硬删除单元测试 (Issue #820)
 *
 * @author Ateng
 * @since 2026-11-09
 */
@DisplayName("AtengBaseService 逻辑删除与物理硬删除门面单元测试")
class AtengBaseServiceLogicDeleteAndPhysicalPurgeTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSession sqlSession;
    private static TestPurgeMapper purgeMapper;
    private static TestPurgeServiceImpl purgeService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    @TableName("t_purge_service_item")
    static class TestPurgeEntity extends BaseFullEntity implements Serializable {
        @TableId
        private Long id;
        private String code;
        private String name;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TestPurgeVO implements Serializable {
        private Long id;
        private String code;
        private String name;
    }

    interface TestPurgeMapper extends AtengBaseMapper<TestPurgeEntity> {
    }

    static class TestPurgeServiceImpl extends AtengServiceImpl<TestPurgeMapper, TestPurgeEntity> {
        public TestPurgeServiceImpl(TestPurgeMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_purge_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_purge_service_item");
            stmt.execute("""
                CREATE TABLE t_purge_service_item (
                    id BIGINT PRIMARY KEY,
                    code VARCHAR(64) NOT NULL,
                    name VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by VARCHAR(64),
                    version INT DEFAULT 1,
                    deleted INT DEFAULT 0
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("purge_service_test", new JdbcTransactionFactory(), dataSource));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        // 挂载逻辑删除 AST 拦截器与分页拦截器
        MybatisPlusInterceptor mpInterceptor = new MybatisPlusInterceptor();
        mpInterceptor.addInnerInterceptor(new AtengLogicDeleteInnerInterceptor());
        mpInterceptor.addInnerInterceptor(new PaginationInnerInterceptor(com.baomidou.mybatisplus.annotation.DbType.MYSQL));
        configuration.addInterceptor(mpInterceptor);

        configuration.addMapper(TestPurgeMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);

        purgeMapper = sqlSession.getMapper(TestPurgeMapper.class);
        purgeService = new TestPurgeServiceImpl(purgeMapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_purge_service_item");
        }
        UserContextHolder.clear();
        LogicDeleteContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        LogicDeleteContextHolder.clear();
        io.github.atengk.boot.mybatisplus.util.BatchUtil.setAuditEnabled(true);
        io.github.atengk.boot.mybatisplus.util.BatchUtil.setDefaultOperator(io.github.atengk.boot.mybatisplus.util.BatchUtil.DEFAULT_OPERATOR);
    }

    private int countRowsInTableDirectly(Long id) throws Exception {
        String sql = id == null
                ? "SELECT COUNT(*) FROM t_purge_service_item"
                : "SELECT COUNT(*) FROM t_purge_service_item WHERE id = " + id;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String getUpdateByDirectly(Long id) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT update_by FROM t_purge_service_item WHERE id = " + id)) {
            if (rs.next()) {
                return rs.getString(1);
            }
            return null;
        }
    }

    // ==========================================
    // 1. 前置 Fail-Fast 边界断言测试
    // ==========================================

    @Test
    @DisplayName("1. 物理删除前置边界校验：入参为空或 Wrapper 无条件时快速失败抛出 IllegalArgumentException")
    void shouldFailFastOnInvalidPhysicalDeleteInputs() {
        assertThatThrownBy(() -> purgeService.removePhysicallyById(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 不能为 null");

        assertThatThrownBy(() -> purgeService.removePhysicallyByIds(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idList 不能为空");

        assertThatThrownBy(() -> purgeService.removePhysicallyByIds(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idList 不能为空");

        assertThatThrownBy(() -> purgeService.removePhysicallyBatchByIds(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idList 不能为空");

        assertThatThrownBy(() -> purgeService.removePhysicallyBatchByIds(Collections.emptyList()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idList 不能为空");

        assertThatThrownBy(() -> purgeService.removePhysically(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queryWrapper 不能为 null");

        assertThatThrownBy(() -> purgeService.removePhysically(new QueryWrapper<>()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须包含有效 WHERE 条件");
    }

    // ==========================================
    // 2. 对称穿透查询与 VO 投影测试
    // ==========================================

    @Test
    @DisplayName("2. 穿透逻辑删除统计、判定与 VO 分页投影：上下文生命周期确定性清理")
    void shouldSupportSymmetricWithDeletedQueriesAndCleanContext() {
        TestPurgeEntity item1 = new TestPurgeEntity(101L, "C001", "活跃记录");
        item1.setDeleted(0);
        TestPurgeEntity item2 = new TestPurgeEntity(102L, "C002", "已删记录");
        item2.setDeleted(1);
        purgeMapper.insert(item1);
        purgeMapper.insert(item2);

        // 普通 count 仅能查到未删除记录（1条）
        assertThat(purgeService.count()).isEqualTo(1L);

        // countWithDeleted 穿透统计（包含已删与未删，共2条）
        assertThat(purgeService.countWithDeleted(null)).isEqualTo(2L);
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();

        // existsWithDeleted 判定已删记录是否存在
        assertThat(purgeService.existsWithDeleted(new LambdaQueryWrapper<TestPurgeEntity>().eq(TestPurgeEntity::getCode, "C002"))).isTrue();
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();

        // pageWithDeleted VO 强类型分页投影 (PageQuery 重载)
        PageQuery pq = new PageQuery();
        pq.setCurrent(1L);
        pq.setSize(10L);
        PageResult<TestPurgeVO> voPage = purgeService.pageWithDeleted(pq, new LambdaQueryWrapper<TestPurgeEntity>().orderByAsc(TestPurgeEntity::getId), TestPurgeVO.class);
        assertThat(voPage).isNotNull();
        assertThat(voPage.getTotal()).isEqualTo(2L);
        assertThat(voPage.getRecords()).hasSize(2);
        assertThat(voPage.getRecords().get(0)).isInstanceOf(TestPurgeVO.class);
        assertThat(voPage.getRecords().get(0).getCode()).isEqualTo("C001");
        assertThat(voPage.getRecords().get(1).getCode()).isEqualTo("C002");
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();

        // pageWithDeleted VO 强类型分页投影 (IPage 重载)
        Page<TestPurgeEntity> page = new Page<>(1, 10);
        PageResult<TestPurgeVO> voPage2 = purgeService.pageWithDeleted(page, new LambdaQueryWrapper<TestPurgeEntity>().orderByAsc(TestPurgeEntity::getId), TestPurgeVO.class);
        assertThat(voPage2.getTotal()).isEqualTo(2L);
        assertThat(voPage2.getRecords()).hasSize(2);
        assertThat(LogicDeleteContextHolder.isWithDeleted()).isFalse();
    }

    // ==========================================
    // 3. CurrentUserProvider SPI 优先恢复测试
    // ==========================================

    @Test
    @DisplayName("3. 数据恢复 CurrentUserProvider SPI 对齐：优先从 SPI 获取操作人，缺省回退 UserContextHolder")
    void shouldPrioritizeCurrentUserProviderOnRestore() throws Exception {
        TestPurgeEntity entity = new TestPurgeEntity(201L, "R001", "待恢复记录");
        entity.setDeleted(1);
        purgeMapper.insert(entity);

        // 3.1 存在 SPI 时，优先提取 SPI 操作人
        CurrentUserProvider spiMock = () -> "spi-operator";
        @SuppressWarnings("unchecked")
        ObjectProvider<CurrentUserProvider> mockProvider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(mockProvider.getIfAvailable()).thenReturn(spiMock);

        purgeService.setCurrentUserProviderProvider(mockProvider);
        UserContextHolder.set("threadlocal-user");

        boolean restored = purgeService.restoreById(201L);
        assertThat(restored).isTrue();
        assertThat(getUpdateByDirectly(201L)).isEqualTo("spi-operator");

        // 3.2 SPI 返回 null 时，平滑回退至 UserContextHolder
        org.mockito.Mockito.when(mockProvider.getIfAvailable()).thenReturn(() -> null);
        entity.setDeleted(1);
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("UPDATE t_purge_service_item SET deleted = 1 WHERE id = 201");
        }

        boolean restoredFallback = purgeService.restoreById(201L);
        assertThat(restoredFallback).isTrue();
        assertThat(getUpdateByDirectly(201L)).isEqualTo("threadlocal-user");

        // 清理 SPI 引用
        purgeService.setCurrentUserProviderProvider(null);
    }

    @Test
    @DisplayName("3.1 audit.enabled=false 时逻辑删除恢复跳过 update_time 与 update_by 审计填充")
    void shouldSkipAuditFieldsOnRestoreWhenAuditDisabled() throws Exception {
        TestPurgeEntity entity = new TestPurgeEntity(202L, "R002", "关闭审计待恢复记录");
        entity.setDeleted(1);
        purgeMapper.insert(entity);

        io.github.atengk.boot.mybatisplus.util.BatchUtil.setAuditEnabled(false);
        UserContextHolder.set("should-not-fill");

        boolean restored = purgeService.restoreById(202L);
        assertThat(restored).isTrue();
        assertThat(getUpdateByDirectly(202L)).isNull();
    }

    // ==========================================
    // 4. 物理硬删除执行与彻底擦除验证
    // ==========================================

    @Test
    @DisplayName("4. 原生物理硬删除执行验证：无视 @TableLogic，真正从物理表中彻底删除行")
    void shouldPhysicallyDeleteRowsFromDatabase() throws Exception {
        TestPurgeEntity p1 = new TestPurgeEntity(301L, "P001", "记录1");
        p1.setDeleted(1); // 模拟已经在回收站中的软删除记录
        TestPurgeEntity p2 = new TestPurgeEntity(302L, "P002", "记录2");
        p2.setDeleted(1);
        TestPurgeEntity p3 = new TestPurgeEntity(303L, "P003", "记录3");
        p3.setDeleted(0);
        TestPurgeEntity p4 = new TestPurgeEntity(304L, "P004", "记录4");
        p4.setDeleted(0);

        purgeMapper.insert(p1);
        purgeMapper.insert(p2);
        purgeMapper.insert(p3);
        purgeMapper.insert(p4);

        assertThat(countRowsInTableDirectly(null)).isEqualTo(4);

        // 4.1 单条物理硬删除（即使 deleted=1，也彻底删除）
        boolean deletedOne = purgeService.removePhysicallyById(301L);
        assertThat(deletedOne).isTrue();
        assertThat(countRowsInTableDirectly(301L)).isEqualTo(0);
        assertThat(countRowsInTableDirectly(null)).isEqualTo(3);

        // 4.2 批量物理硬删除（验证 removePhysicallyByIds 与 removePhysicallyBatchByIds 规范别名）
        boolean deletedBatch = purgeService.removePhysicallyByIds(List.of(302L));
        assertThat(deletedBatch).isTrue();
        boolean deletedBatchAlias = purgeService.removePhysicallyBatchByIds(List.of(303L));
        assertThat(deletedBatchAlias).isTrue();
        assertThat(countRowsInTableDirectly(302L)).isEqualTo(0);
        assertThat(countRowsInTableDirectly(303L)).isEqualTo(0);
        assertThat(countRowsInTableDirectly(null)).isEqualTo(1);

        // 4.3 条件物理硬删除
        boolean deletedWrapper = purgeService.removePhysically(
                new LambdaQueryWrapper<TestPurgeEntity>().eq(TestPurgeEntity::getCode, "P004")
        );
        assertThat(deletedWrapper).isTrue();
        assertThat(countRowsInTableDirectly(304L)).isEqualTo(0);
        assertThat(countRowsInTableDirectly(null)).isEqualTo(0);
    }
}
