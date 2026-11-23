package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mybatisplus.context.LogicDeleteContextHolder;
import io.github.atengk.boot.mybatisplus.handler.DefaultMetaObjectHandler;
import io.github.atengk.boot.mybatisplus.injector.AtengSqlInjector;
import io.github.atengk.boot.mybatisplus.logicdelete.AtengLogicDeleteInnerInterceptor;
import io.github.atengk.boot.mybatisplus.mapper.AtengBaseMapper;
import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.mybatisplus.util.BatchUtil;
import lombok.Getter;
import lombok.Setter;
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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 占位软删除（Long/String 主键）与双轨阶梯实体 H2 端到端集成测试
 * <p>
 * 覆盖 {@link BaseLogicEntity}、{@link BaseFullEntity}、{@link BaseGenericLogicEntity} 与
 * {@link BaseGenericFullEntity}（含自定义主键列名），验证 {@link AtengSqlInjector} 对字符串泛型
 * {@code delval = "id"} 的 SQL 重写、唯一索引防冲突复用、乐观锁初始版本号 {@code 0} 及 {@code restoreById} 闭环。
 *
 * @author Ateng
 * @since 2026-11-20
 */
@DisplayName("占位软删除与双轨实体阶梯 H2 端到端集成测试")
class BaseLogicEntityAndGenericSoftDeleteTest {

    @Getter
    @Setter
    @TableName("t_dept_logic_sample")
    static class DeptLogicEntity extends BaseLogicEntity {
        private String deptCode;
        private String deptName;
    }

    interface DeptLogicMapper extends AtengBaseMapper<DeptLogicEntity> {
    }

    @Getter
    @Setter
    @TableName("t_sku_full_sample")
    static class SkuFullEntity extends BaseFullEntity {
        private String skuCode;
        private Integer stock;
    }

    interface SkuFullMapper extends AtengBaseMapper<SkuFullEntity> {
    }

    @Getter
    @Setter
    @TableName("t_voucher_str_logic")
    static class StringVoucherEntity extends BaseGenericLogicEntity<String> {
        @TableId(type = IdType.ASSIGN_UUID)
        private String id;
        private String voucherNo;
    }

    interface StringVoucherMapper extends AtengBaseMapper<StringVoucherEntity> {
    }

    static class StringVoucherServiceImpl extends AtengServiceImpl<StringVoucherMapper, StringVoucherEntity>
            implements AtengBaseService<StringVoucherEntity> {
        StringVoucherServiceImpl(StringVoucherMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    @Getter
    @Setter
    @TableName("t_contract_custom_pk_full")
    static class CustomPkFullEntity extends BaseGenericFullEntity<String> {
        @TableId(value = "custom_pk", type = IdType.INPUT)
        private String customPk;
        private String contractCode;

        @Override
        public String getId() {
            return customPk;
        }

        @Override
        public void setId(String id) {
            this.customPk = id;
        }
    }

    interface CustomPkFullMapper extends AtengBaseMapper<CustomPkFullEntity> {
    }

    static class CustomPkFullServiceImpl extends AtengServiceImpl<CustomPkFullMapper, CustomPkFullEntity>
            implements AtengBaseService<CustomPkFullEntity> {
        CustomPkFullServiceImpl(CustomPkFullMapper mapper) {
            this.baseMapper = mapper;
        }
    }

    private static DataSource dataSource;
    private static SqlSession sqlSession;
    private static DeptLogicMapper deptMapper;
    private static SkuFullMapper skuMapper;
    private static StringVoucherMapper voucherMapper;
    private static StringVoucherServiceImpl voucherService;
    private static CustomPkFullMapper customPkMapper;
    private static CustomPkFullServiceImpl customPkService;

    @BeforeAll
    static void setUpAll() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:dual_ladder_logic_test;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_dept_logic_sample (
                    id BIGINT PRIMARY KEY,
                    dept_code VARCHAR(64) NOT NULL,
                    dept_name VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_by VARCHAR(64),
                    deleted BIGINT NOT NULL DEFAULT 0,
                    CONSTRAINT uk_dept_code_deleted UNIQUE (dept_code, deleted)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_sku_full_sample (
                    id BIGINT PRIMARY KEY,
                    sku_code VARCHAR(64) NOT NULL,
                    stock INT NOT NULL,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_by VARCHAR(64),
                    deleted BIGINT NOT NULL DEFAULT 0,
                    version INT NOT NULL DEFAULT 0,
                    CONSTRAINT uk_sku_full_deleted UNIQUE (sku_code, deleted)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_voucher_str_logic (
                    id VARCHAR(64) PRIMARY KEY,
                    voucher_no VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_by VARCHAR(64),
                    deleted VARCHAR(64) NOT NULL DEFAULT '0',
                    CONSTRAINT uk_voucher_no_deleted UNIQUE (voucher_no, deleted)
                )
            """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_contract_custom_pk_full (
                    custom_pk VARCHAR(64) PRIMARY KEY,
                    contract_code VARCHAR(64) NOT NULL,
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_by VARCHAR(64),
                    deleted VARCHAR(64) NOT NULL DEFAULT '0',
                    version INT NOT NULL DEFAULT 0,
                    CONSTRAINT uk_contract_code_deleted UNIQUE (contract_code, deleted)
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("dual_ladder_test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);

        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new AtengLogicDeleteInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);

        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setMetaObjectHandler(new DefaultMetaObjectHandler());
        globalConfig.setSqlInjector(new AtengSqlInjector(DbType.MYSQL));
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(DeptLogicMapper.class);
        configuration.addMapper(SkuFullMapper.class);
        configuration.addMapper(StringVoucherMapper.class);
        configuration.addMapper(CustomPkFullMapper.class);

        SqlSessionFactory sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        sqlSession = sqlSessionFactory.openSession(true);

        deptMapper = sqlSession.getMapper(DeptLogicMapper.class);
        skuMapper = sqlSession.getMapper(SkuFullMapper.class);
        voucherMapper = sqlSession.getMapper(StringVoucherMapper.class);
        voucherService = new StringVoucherServiceImpl(voucherMapper);
        customPkMapper = sqlSession.getMapper(CustomPkFullMapper.class);
        customPkService = new CustomPkFullServiceImpl(customPkMapper);
    }

    @AfterAll
    static void tearDownAll() {
        if (sqlSession != null) {
            sqlSession.close();
        }
    }

    @BeforeEach
    void resetTables() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_dept_logic_sample");
            stmt.execute("DELETE FROM t_sku_full_sample");
            stmt.execute("DELETE FROM t_voucher_str_logic");
            stmt.execute("DELETE FROM t_contract_custom_pk_full");
        }
        UserContextHolder.clear();
        LogicDeleteContextHolder.clear();
    }

    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        LogicDeleteContextHolder.clear();
    }

    @Test
    @DisplayName("1. BaseLogicEntity 64位雪花软删除（无 version）支持 deleted=id 占位及同名重建")
    void shouldSoftDeleteBaseLogicEntityWithIdPlaceholderAndReuseUniqueKey() throws Exception {
        UserContextHolder.set("tester-logic");
        DeptLogicEntity first = new DeptLogicEntity();
        first.setDeptCode("DEPT-RD");
        first.setDeptName("研发部-初代");
        deptMapper.insert(first);
        assertThat(first.getId()).isNotNull();

        int affected = deptMapper.deleteById(first.getId());
        assertThat(affected).isEqualTo(1);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT deleted FROM t_dept_logic_sample WHERE id = ?")) {
            ps.setLong(1, first.getId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("deleted")).isEqualTo(first.getId());
            }
        }

        // 同 deptCode 再次软删除也不触发唯一索引冲突
        DeptLogicEntity second = new DeptLogicEntity();
        second.setDeptCode("DEPT-RD");
        second.setDeptName("研发部-二代");
        deptMapper.insert(second);
        assertThat(deptMapper.deleteById(second.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("2. BaseFullEntity 统一为 Long deleted=id 且 BatchUtil 自愈默认 version=0 乐观锁递增")
    void shouldAutoHealBaseFullEntityVersionZeroAndSupportLogicDelete() throws Exception {
        SkuFullEntity sku = new SkuFullEntity();
        sku.setSkuCode("SKU-PRO-001");
        sku.setStock(50);
        BatchUtil.autoHeal(sku);
        assertThat(sku.getVersion()).isEqualTo(0);
        assertThat(sku.getDeleted()).isEqualTo(0L);
        skuMapper.insert(sku);

        sku.setStock(45);
        int updated = skuMapper.updateById(sku);
        assertThat(updated).isEqualTo(1);
        SkuFullEntity reloaded = skuMapper.selectById(sku.getId());
        assertThat(reloaded.getVersion()).isEqualTo(1);

        assertThat(skuMapper.deleteById(sku.getId())).isEqualTo(1);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT deleted FROM t_sku_full_sample WHERE id = ?")) {
            ps.setLong(1, sku.getId());
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong("deleted")).isEqualTo(sku.getId());
            }
        }
    }

    @Test
    @DisplayName("3. BaseGenericLogicEntity<String> 端到端全操作（deleteById/deleteByIds/delete(Wrapper)/穿透/恢复/唯一索引复用）")
    void shouldSupportStringGenericLogicEntityAcrossAllDeleteAndRestorePaths() throws Exception {
        UserContextHolder.set("voucher-admin");
        StringVoucherEntity v1 = new StringVoucherEntity();
        v1.setVoucherNo("VCH-100");
        BatchUtil.autoHeal(v1);
        assertThat(v1.getId()).hasSize(32);
        assertThat(v1.getDeleted()).isEqualTo("0");
        voucherMapper.insert(v1);

        // deleteById：物理列 deleted 必须写入 v1.getId() 而非字面量 "id"
        assertThat(voucherMapper.deleteById(v1.getId())).isEqualTo(1);
        assertThat(voucherMapper.selectById(v1.getId())).isNull();
        assertThat(queryRawStringColumn("SELECT deleted FROM t_voucher_str_logic WHERE id = ?", v1.getId()))
                .isEqualTo(v1.getId());

        // 同 voucherNo 重建再批量 deleteByIds，必须写入各自 id，不触发 uk_voucher_no_deleted 冲突
        StringVoucherEntity v2 = new StringVoucherEntity();
        v2.setVoucherNo("VCH-100");
        StringVoucherEntity v3 = new StringVoucherEntity();
        v3.setVoucherNo("VCH-200");
        voucherService.insertBatch(List.of(v2, v3));

        assertThat(voucherMapper.deleteByIds(List.of(v2.getId(), v3.getId()))).isEqualTo(2);
        assertThat(queryRawStringColumn("SELECT deleted FROM t_voucher_str_logic WHERE id = ?", v2.getId()))
                .isEqualTo(v2.getId());
        assertThat(queryRawStringColumn("SELECT deleted FROM t_voucher_str_logic WHERE id = ?", v3.getId()))
                .isEqualTo(v3.getId());

        // 穿透查询可见回收站中的 3 条记录，且可通过 restoreById 将 deleted 还原为 "0"
        assertThat(voucherService.countWithDeleted(null)).isEqualTo(3L);
        assertThat(voucherService.restoreById(v3.getId())).isTrue();
        assertThat(voucherMapper.selectById(v3.getId())).isNotNull();
        assertThat(queryRawStringColumn("SELECT deleted FROM t_voucher_str_logic WHERE id = ?", v3.getId()))
                .isEqualTo("0");

        // Wrapper delete 同样将 deleted 设为 v3.getId()
        assertThat(voucherMapper.delete(new LambdaQueryWrapper<StringVoucherEntity>()
                .eq(StringVoucherEntity::getVoucherNo, "VCH-200"))).isEqualTo(1);
        assertThat(queryRawStringColumn("SELECT deleted FROM t_voucher_str_logic WHERE id = ?", v3.getId()))
                .isEqualTo(v3.getId());
    }

    @Test
    @DisplayName("4. BaseGenericFullEntity<String> 自定义主键列名 custom_pk 软删占位与乐观锁/restoreById 联动")
    void shouldSupportCustomPkColumnWithBaseGenericFullEntity() throws Exception {
        CustomPkFullEntity entity = new CustomPkFullEntity();
        entity.setCustomPk("CT-2026-001");
        entity.setContractCode("CON-888");
        entity.setVersion(0);
        entity.setDeleted("0");
        customPkMapper.insert(entity);

        // 乐观锁更新 version 0 -> 1
        entity.setContractCode("CON-889");
        assertThat(customPkMapper.updateById(entity)).isEqualTo(1);
        CustomPkFullEntity reloaded = customPkMapper.selectById("CT-2026-001");
        assertThat(reloaded.getVersion()).isEqualTo(1);

        // deleteByMap 软删将 deleted 写入 custom_pk 值 "CT-2026-001"
        assertThat(customPkMapper.deleteByMap(Map.of("contract_code", "CON-889"))).isEqualTo(1);
        assertThat(queryRawStringColumn("SELECT deleted FROM t_contract_custom_pk_full WHERE custom_pk = ?", "CT-2026-001"))
                .isEqualTo("CT-2026-001");

        // restoreById 还原 deleted = '0'
        assertThat(customPkService.restoreById("CT-2026-001")).isTrue();
        assertThat(customPkMapper.selectById("CT-2026-001")).isNotNull();
    }

    private String queryRawStringColumn(String sql, String param) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
