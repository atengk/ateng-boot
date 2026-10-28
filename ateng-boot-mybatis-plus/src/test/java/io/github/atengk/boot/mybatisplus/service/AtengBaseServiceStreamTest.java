package io.github.atengk.boot.mybatisplus.service;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.mybatisplus.dynamicquery.annotation.QueryField;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import io.github.atengk.boot.mybatisplus.entity.TenantEntity;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AtengBaseService 与 AtengBaseMapper 原生受管游标流式与动态查询体系集成测试 (Issue #126)
 * 覆盖百万级游标逐条流式、分批切片、Predicate 提前短路退出、强类型 VO 一步直出投影、
 * 动态查询支持、未注入模板快速失败防卫、确定性关闭游标与连接防泄漏
 *
 * @author Ateng
 * @since 2026-10-19
 */
@DisplayName("AtengBaseService 原生受管游标流式与动态查询门面测试")
class AtengBaseServiceStreamTest {

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static SqlSessionTemplate sqlSessionTemplate;
    private static DataSourceTransactionManager transactionManager;
    private static StreamingQueryTemplate streamingQueryTemplate;
    private static TestStreamItemMapper itemMapper;
    private static TestStreamItemServiceImpl itemService;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = true)
    @TableName("t_stream_service_item")
    static class TestStreamItemEntity extends BaseDeleteIdEntity implements TenantEntity, Serializable {
        @TableId
        private Long id;
        private String name;
        private String status;
        private Integer count;
        private String tenantId;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestStreamItemVO {
        private Long id;
        private String name;
        private String status;
        private Integer count;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class TestStreamQueryDto {
        @QueryField(operator = QueryOperator.LIKE)
        private String name;
        @QueryField(operator = QueryOperator.EQ)
        private String status;
    }

    interface TestStreamItemMapper extends AtengBaseMapper<TestStreamItemEntity> {
    }

    interface TestStreamItemService extends AtengBaseService<TestStreamItemEntity> {
    }

    static class TestStreamItemServiceImpl extends AtengServiceImpl<TestStreamItemMapper, TestStreamItemEntity> implements TestStreamItemService {
        public TestStreamItemServiceImpl(TestStreamItemMapper mapper, StreamingQueryTemplate template) {
            this.baseMapper = mapper;
            setStreamingQueryTemplate(template);
        }
    }

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:service_stream_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_stream_service_item");
            stmt.execute("""
                CREATE TABLE t_stream_service_item (
                    id BIGINT PRIMARY KEY,
                    name VARCHAR(64) NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    count INT NOT NULL,
                    tenant_id VARCHAR(64),
                    create_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_time TIMESTAMP,
                    update_by VARCHAR(64),
                    version INT DEFAULT 1,
                    deleted BIGINT DEFAULT 0
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("stream_service_test", new SpringManagedTransactionFactory(), dataSource));

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(TestStreamItemMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSessionTemplate = new SqlSessionTemplate(sqlSessionFactory);

        transactionManager = new DataSourceTransactionManager(dataSource);
        streamingQueryTemplate = new StreamingQueryTemplate(transactionManager);

        itemMapper = sqlSessionTemplate.getMapper(TestStreamItemMapper.class);
        itemService = new TestStreamItemServiceImpl(itemMapper, streamingQueryTemplate);

        // 初始化 2500 条测试数据：奇数条 ACTIVE，偶数条 DISABLED
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            StringBuilder sb = new StringBuilder();
            sb.append("INSERT INTO t_stream_service_item (id, name, status, count, tenant_id, deleted) VALUES ");
            for (int i = 1; i <= 2500; i++) {
                String status = (i % 2 == 1) ? "ACTIVE" : "DISABLED";
                sb.append(String.format("(%d, 'Item-%d', '%s', %d, 'T001', 0)", i, i, status, i * 10));
                if (i < 2500) {
                    sb.append(", ");
                }
            }
            stmt.execute(sb.toString());
        }
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS t_stream_service_item");
        }
    }

    // ==========================================
    // 1. 基础逐条流式与条件流式 (stream / streamWhile)
    // ==========================================

    @Test
    @DisplayName("1. stream 逐条流式遍历：完整读取 2500 条数据且顺序与数据一致")
    void shouldStreamAllRecordsSequentially() {
        List<Long> readIds = new ArrayList<>(2500);
        AtomicLong sumCount = new AtomicLong(0);

        itemService.stream(null, item -> {
            readIds.add(item.getId());
            sumCount.addAndGet(item.getCount());
        });

        assertThat(readIds).hasSize(2500);
        assertThat(readIds.get(0)).isEqualTo(1L);
        assertThat(readIds.get(2499)).isEqualTo(2500L);
        // 等差数列求和：(10 + 25000) * 2500 / 2 = 31262500
        assertThat(sumCount.get()).isEqualTo(31262500L);
    }

    @Test
    @DisplayName("2. stream 条件过滤流式：结合 QueryWrapper 条件只读取 ACTIVE 记录（1250条）")
    void shouldStreamFilteredRecordsWithQueryWrapper() {
        List<Long> activeIds = new ArrayList<>(1250);
        QueryWrapper<TestStreamItemEntity> qw = new QueryWrapper<TestStreamItemEntity>()
                .eq("status", "ACTIVE")
                .orderByAsc("id");

        itemService.stream(qw, item -> {
            assertThat(item.getStatus()).isEqualTo("ACTIVE");
            activeIds.add(item.getId());
        });

        assertThat(activeIds).hasSize(1250);
        assertThat(activeIds.get(0)).isEqualTo(1L);
        assertThat(activeIds.get(1)).isEqualTo(3L);
        assertThat(activeIds.get(1249)).isEqualTo(2499L);
    }

    @Test
    @DisplayName("3. streamWhile 提前短路退出：遍历至第 80 条时返回 false 立即中断并确定性关闭游标")
    void shouldShortCircuitWhenPredicateReturnsFalse() {
        List<Long> ids = new ArrayList<>();

        itemService.streamWhile(null, item -> {
            ids.add(item.getId());
            return item.getId() < 80;
        });

        assertThat(ids).hasSize(80);
        assertThat(ids.get(0)).isEqualTo(1L);
        assertThat(ids.get(79)).isEqualTo(80L);
    }

    // ==========================================
    // 2. 分批切片流式消费 (streamBatch)
    // ==========================================

    @Test
    @DisplayName("4. streamBatch 默认切片大小：按默认 1000 正确切分为 3 批（1000, 1000, 500）且只读不可变")
    void shouldStreamBatchWithDefaultSize() {
        List<Integer> batchSizes = new ArrayList<>();
        List<Long> allIds = new ArrayList<>();

        itemService.streamBatch(null, batch -> {
            batchSizes.add(batch.size());
            batch.forEach(item -> allIds.add(item.getId()));
            // 验证只读不可变防御
            assertThatThrownBy(() -> batch.add(new TestStreamItemEntity()))
                    .isInstanceOf(UnsupportedOperationException.class);
        });

        assertThat(batchSizes).containsExactly(1000, 1000, 500);
        assertThat(allIds).hasSize(2500);
        assertThat(allIds.get(0)).isEqualTo(1L);
        assertThat(allIds.get(2499)).isEqualTo(2500L);
    }

    @Test
    @DisplayName("5. streamBatch 自定义切片大小：按 600 正确切分为 5 批（600, 600, 600, 600, 100）")
    void shouldStreamBatchWithCustomSize() {
        List<Integer> batchSizes = new ArrayList<>();

        itemService.streamBatch(null, 600, batch -> batchSizes.add(batch.size()));

        assertThat(batchSizes).containsExactly(600, 600, 600, 600, 100);
    }

    // ==========================================
    // 3. 强类型 VO 一步直出流式投影 (Zero-Entity-Leakage)
    // ==========================================

    @Test
    @DisplayName("6. stream 投影为 VO：单条流式直出强类型 VO，持久层实体零泄露")
    void shouldStreamDirectlyToVo() {
        List<TestStreamItemVO> voList = new ArrayList<>(50);

        QueryWrapper<TestStreamItemEntity> qw = new QueryWrapper<TestStreamItemEntity>()
                .le("id", 50)
                .orderByAsc("id");

        itemService.stream(qw, TestStreamItemVO.class, vo -> {
            assertThat(vo).isInstanceOf(TestStreamItemVO.class);
            voList.add(vo);
        });

        assertThat(voList).hasSize(50);
        assertThat(voList.get(0).getName()).isEqualTo("Item-1");
        assertThat(voList.get(49).getName()).isEqualTo("Item-50");
    }

    @Test
    @DisplayName("7. streamBatch 投影为 VO 切片：分批直出强类型 VO 列表切片")
    void shouldStreamBatchDirectlyToVoBatches() {
        List<Integer> batchSizes = new ArrayList<>();
        List<TestStreamItemVO> allVos = new ArrayList<>();

        itemService.streamBatch(null, TestStreamItemVO.class, 800, batch -> {
            batchSizes.add(batch.size());
            allVos.addAll(batch);
        });

        assertThat(batchSizes).containsExactly(800, 800, 800, 100);
        assertThat(allVos).hasSize(2500);
        assertThat(allVos.get(0)).isInstanceOf(TestStreamItemVO.class);
    }

    @Test
    @DisplayName("8. streamWhile 投影为 VO 断言：基于 VO 属性进行 Predicate 提前短路退出")
    void shouldStreamWhileDirectlyToVoPredicate() {
        List<TestStreamItemVO> list = new ArrayList<>();

        itemService.streamWhile(null, TestStreamItemVO.class, vo -> {
            list.add(vo);
            return vo.getId() < 35;
        });

        assertThat(list).hasSize(35);
        assertThat(list.get(34).getId()).isEqualTo(35L);
    }

    // ==========================================
    // 4. 动态查询流式直出 (streamDynamic / streamDynamicBatch / streamDynamicWhile)
    // ==========================================

    @Test
    @DisplayName("9. streamDynamic 结构化请求流式直出 VO")
    void shouldStreamDynamicWithRequestToVo() {
        DynamicQueryRequest request = new DynamicQueryRequest()
                .eq("status", "ACTIVE")
                .le("id", 100);
        request.addAsc("id");

        List<TestStreamItemVO> activeVos = new ArrayList<>();
        itemService.streamDynamic(request, TestStreamItemVO.class, activeVos::add);

        // 1..100 内奇数共 50 个
        assertThat(activeVos).hasSize(50);
        assertThat(activeVos.get(0).getId()).isEqualTo(1L);
        assertThat(activeVos.get(49).getId()).isEqualTo(99L);
    }

    @Test
    @DisplayName("10. streamDynamicBatch 结构化请求分批流式直出 VO")
    void shouldStreamDynamicBatchWithRequestToVo() {
        DynamicQueryRequest request = new DynamicQueryRequest()
                .eq("status", "DISABLED")
                .le("id", 200);
        request.addAsc("id");

        List<Integer> batchSizes = new ArrayList<>();
        itemService.streamDynamicBatch(request, TestStreamItemVO.class, 30, batch -> batchSizes.add(batch.size()));

        // 1..200 内偶数共 100 个，按 30 切片：30, 30, 30, 10
        assertThat(batchSizes).containsExactly(30, 30, 30, 10);
    }

    @Test
    @DisplayName("11. streamDynamicWhile 结构化请求条件短路直出 VO")
    void shouldStreamDynamicWhileWithRequestToVo() {
        DynamicQueryRequest request = new DynamicQueryRequest()
                .eq("status", "ACTIVE");
        request.addAsc("id");

        List<TestStreamItemVO> list = new ArrayList<>();
        itemService.streamDynamicWhile(request, TestStreamItemVO.class, vo -> {
            list.add(vo);
            return list.size() < 20;
        });

        assertThat(list).hasSize(20);
        assertThat(list.get(19).getId()).isEqualTo(39L);
    }

    @Test
    @DisplayName("12. streamDynamic 普通 DTO 对象流式直出 VO")
    void shouldStreamDynamicWithDtoToVo() {
        TestStreamQueryDto dto = new TestStreamQueryDto("Item-1", "ACTIVE");
        List<TestStreamItemVO> list = new ArrayList<>();

        itemService.streamDynamic(dto, TestStreamItemVO.class, list::add);

        // name LIKE '%Item-1%' 且 status = 'ACTIVE'
        assertThat(list).isNotEmpty();
        assertThat(list).allMatch(vo -> vo.getName().contains("Item-1") && "ACTIVE".equals(vo.getStatus()));
    }

    // ==========================================
    // 5. 快速失败防卫与异常安全确定性释放 (Robustness & Fail-Fast)
    // ==========================================

    @Test
    @DisplayName("13. 快速失败防卫：未注入 StreamingQueryTemplate 时调用流式方法抛出 IllegalStateException")
    void shouldThrowIllegalStateExceptionWhenStreamingQueryTemplateNotConfigured() {
        TestStreamItemServiceImpl unconfiguredService = new TestStreamItemServiceImpl(itemMapper, null);

        assertThatThrownBy(() -> unconfiguredService.stream(null, item -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前 Service 未注入 StreamingQueryTemplate 组件，无法执行流式游标查询");

        assertThatThrownBy(() -> unconfiguredService.streamBatch(null, 100, batch -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前 Service 未注入 StreamingQueryTemplate 组件，无法执行流式游标查询");

        assertThatThrownBy(() -> unconfiguredService.streamWhile(null, item -> true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前 Service 未注入 StreamingQueryTemplate 组件，无法执行流式游标查询");
    }

    @Test
    @DisplayName("14. 确定性生命周期与连接释放：下游抛出业务异常时事务回滚、游标关闭，后续查询不受影响且连接无泄露")
    void shouldCloseCursorAndReleaseConnectionWhenConsumerThrowsException() {
        // 1. 中途异常抛出
        assertThatThrownBy(() -> itemService.stream(null, item -> {
            if (item.getId() == 50L) {
                throw new BusinessException("流式导出中途故障");
            }
        })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("流式导出中途故障");

        // 2. 异常后立即发起新查询，验证连接未挂死且可正常复用
        AtomicInteger freshCount = new AtomicInteger(0);
        itemService.streamWhile(null, item -> {
            freshCount.incrementAndGet();
            return freshCount.get() < 10;
        });
        assertThat(freshCount.get()).isEqualTo(10);
    }
}
