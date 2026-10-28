package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 唯一索引防冲突受管实体与占位删除体系单元测试
 * 验证 BaseDeleteIdEntity 实体契约、JSON 序列化忽略、以及基于 H2 内存库的端到端软删除防唯一索引冲突
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("BaseDeleteIdEntity 唯一索引防冲突软删除测试")
class BaseDeleteIdEntityTest {

    @Getter
    @Setter
    @ToString(callSuper = true)
    @TableName("t_unique_sku")
    static class SkuEntity extends BaseDeleteIdEntity {
        private String code;
        private String name;
    }

    interface SkuMapper extends AtengBaseMapper<SkuEntity> {
    }

    private static DataSource dataSource;
    private static SqlSessionFactory sqlSessionFactory;
    private static final ObjectMapper objectMapper = io.github.atengk.boot.core.util.JsonUtil.getObjectMapper();

    @BeforeAll
    static void initDatabaseAndMyBatis() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:test_base_delete_id;DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        dataSource = ds;

        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS t_unique_sku (
                    id BIGINT PRIMARY KEY,
                    code VARCHAR(64) NOT NULL,
                    name VARCHAR(64),
                    create_time TIMESTAMP,
                    update_time TIMESTAMP,
                    create_by VARCHAR(64),
                    update_by VARCHAR(64),
                    version INT DEFAULT 1,
                    deleted BIGINT DEFAULT 0,
                    CONSTRAINT uk_code_deleted UNIQUE (code, deleted)
                )
            """);
        }

        MybatisConfiguration configuration = new MybatisConfiguration();
        Environment environment = new Environment("test", new JdbcTransactionFactory(), dataSource);
        configuration.setEnvironment(environment);

        // 注册乐观锁插件
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        configuration.addInterceptor(interceptor);

        // 初始化全局配置并绑定雪花发号器与 SQL 注入器
        GlobalConfig globalConfig = GlobalConfigUtils.defaults();
        globalConfig.setIdentifierGenerator(new DefaultIdentifierGenerator());
        globalConfig.setSqlInjector(new AtengSqlInjector());
        GlobalConfigUtils.setGlobalConfig(configuration, globalConfig);

        configuration.addMapper(SkuMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM t_unique_sku");
        }
    }

    @Test
    @DisplayName("1. 契约防卫：BaseDeleteIdEntity 正确继承 BaseEntity 并挂载乐观锁与 delval='id' 逻辑删除注解")
    void shouldHaveCorrectAnnotationsAndHierarchy() throws NoSuchFieldException {
        // 继承阶梯断言
        assertThat(BaseEntity.class).isAssignableFrom(BaseDeleteIdEntity.class);

        // version 字段断言
        Field versionField = BaseDeleteIdEntity.class.getDeclaredField("version");
        assertThat(versionField.getType()).isEqualTo(Integer.class);
        assertThat(versionField.getAnnotation(Version.class)).isNotNull();

        // deleted 字段断言
        Field deletedField = BaseDeleteIdEntity.class.getDeclaredField("deleted");
        assertThat(deletedField.getType()).isEqualTo(Long.class);
        TableLogic tableLogic = deletedField.getAnnotation(TableLogic.class);
        assertThat(tableLogic).isNotNull();
        assertThat(tableLogic.value()).isEqualTo("0");
        assertThat(tableLogic.delval()).isEqualTo("id");

        // @JsonIgnore 防泄露断言
        assertThat(deletedField.getAnnotation(JsonIgnore.class)).isNotNull();
    }

    @Test
    @DisplayName("2. 序列化防卫：BaseDeleteIdEntity 序列化为 JSON 时 100% 忽略 deleted 字段")
    void shouldIgnoreDeletedFieldInJsonSerialization() throws Exception {
        SkuEntity sku = new SkuEntity();
        sku.setId(10086L);
        sku.setCode("SKU-IPHONE-16");
        sku.setName("iPhone 16 Pro");
        sku.setVersion(1);
        sku.setDeleted(0L);
        sku.setCreateBy("admin");
        sku.setUpdateBy("admin");
        sku.setCreateTime(LocalDateTime.now());
        sku.setUpdateTime(LocalDateTime.now());

        String json = objectMapper.writeValueAsString(sku);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("id")).isTrue();
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("name")).isTrue();
        assertThat(root.has("version")).isTrue();
        // 核心验证：deleted 必须被 @JsonIgnore 彻底屏蔽
        assertThat(root.has("deleted")).isFalse();
    }

    @Test
    @DisplayName("3. 动态占位软删除：调用 deleteById 软删除时，底层数据库中的 deleted 字段被准确更新为自身 id 值")
    void shouldUpdateDeletedToSelfIdUponDeletion() throws Exception {
        Long skuId;
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SkuMapper mapper = session.getMapper(SkuMapper.class);

            SkuEntity sku = new SkuEntity();
            sku.setCode("SKU-MACBOOK-M4");
            sku.setName("MacBook Pro M4");
            sku.setVersion(1);
            sku.setDeleted(0L);

            int insertRows = mapper.insert(sku);
            assertThat(insertRows).isEqualTo(1);
            skuId = sku.getId();
            assertThat(skuId).isNotNull();

            // 执行软删除
            int deleteRows = mapper.deleteById(skuId);
            assertThat(deleteRows).isEqualTo(1);
        }

        // 穿透 MyBatis-Plus，直接查底层 JDBC 原始数据进行物理核验
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, code, deleted, version FROM t_unique_sku WHERE id = " + skuId)) {
            assertThat(rs.next()).isTrue();
            long rawId = rs.getLong("id");
            long rawDeleted = rs.getLong("deleted");
            int rawVersion = rs.getInt("version");

            assertThat(rawId).isEqualTo(skuId);
            // 核心断言：deleted 已更新为自身的 ID，而非简单的 1
            assertThat(rawDeleted).isEqualTo(skuId);
            assertThat(rawDeleted).isNotZero();
            // 逻辑删除未改动 version，version 保持原值 1
            assertThat(rawVersion).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("4. 唯一索引防冲突：同 code 记录在多次插入与多次软删除全生命周期中零冲突")
    void shouldNotViolateUniqueIndexWhenMultipleDeletedWithSameCode() throws Exception {
        String sharedCode = "SKU-COLLISION-GUARD-TEST";

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SkuMapper mapper = session.getMapper(SkuMapper.class);

            // 1. 插入第一条记录并软删除
            SkuEntity item1 = new SkuEntity();
            item1.setCode(sharedCode);
            item1.setName("Version 1");
            item1.setVersion(1);
            item1.setDeleted(0L);
            mapper.insert(item1);
            Long id1 = item1.getId();
            assertThat(mapper.deleteById(id1)).isEqualTo(1);

            // 2. 插入同 code 的第二条记录并软删除（在传统 deleted=1 模式下必然抛出 Duplicate Key 冲突）
            SkuEntity item2 = new SkuEntity();
            item2.setCode(sharedCode);
            item2.setName("Version 2");
            item2.setVersion(1);
            item2.setDeleted(0L);
            mapper.insert(item2);
            Long id2 = item2.getId();
            assertThat(id2).isNotEqualTo(id1);
            // 核心验收点：同 code 第二次软删除必须成功，不引发唯一键冲突异常
            assertThat(mapper.deleteById(id2)).isEqualTo(1);

            // 3. 再次插入同 code 的第三条活跃记录
            SkuEntity item3 = new SkuEntity();
            item3.setCode(sharedCode);
            item3.setName("Version 3 Active");
            item3.setVersion(1);
            item3.setDeleted(0L);
            mapper.insert(item3);
            Long id3 = item3.getId();

            // 4. 常规单条与列表查询验证
            // item1 与 item2 已被软删除，selectById 必须返回 null
            assertThat(mapper.selectById(id1)).isNull();
            assertThat(mapper.selectById(id2)).isNull();

            // item3 为活跃记录，正常查出
            SkuEntity found3 = mapper.selectById(id3);
            assertThat(found3).isNotNull();
            assertThat(found3.getName()).isEqualTo("Version 3 Active");

            // 列表查询仅返回活跃的 item3，自动过滤已被软删除的 item1 和 item2
            List<SkuEntity> activeList = mapper.selectList(new LambdaQueryWrapper<SkuEntity>().eq(SkuEntity::getCode, sharedCode));
            assertThat(activeList).hasSize(1);
            assertThat(activeList.get(0).getId()).isEqualTo(id3);
        }

        // 5. 底层原始数据库全量数据核验
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT id, code, deleted FROM t_unique_sku ORDER BY id ASC")) {

            int count = 0;
            while (rs.next()) {
                count++;
                long id = rs.getLong("id");
                long deleted = rs.getLong("deleted");
                if (count <= 2) {
                    // 前两条软删除记录的 deleted 值等于其各自的 id
                    assertThat(deleted).isEqualTo(id);
                } else {
                    // 第三条活跃记录的 deleted 值为 0
                    assertThat(deleted).isZero();
                }
            }
            assertThat(count).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("5. 对抗与防御测试：对已软删除的记录尝试二次软删除或更新时均影响 0 行")
    void shouldAffectZeroRowsWhenOperatingOnAlreadyDeletedEntity() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SkuMapper mapper = session.getMapper(SkuMapper.class);

            SkuEntity sku = new SkuEntity();
            sku.setCode("SKU-EDGE-TEST");
            sku.setName("Edge Item");
            sku.setVersion(1);
            sku.setDeleted(0L);
            mapper.insert(sku);
            Long id = sku.getId();

            // 首次软删除成功
            assertThat(mapper.deleteById(id)).isEqualTo(1);

            // 对已软删除的记录再次执行 deleteById，因 deleted=0 条件不匹配，返回 0
            assertThat(mapper.deleteById(id)).isZero();

            // 对已软删除记录执行 updateById，同样因 deleted=0 条件不匹配，返回 0
            sku.setName("Updated But Deleted");
            assertThat(mapper.updateById(sku)).isZero();
        }
    }

    @Test
    @DisplayName("6. 乐观锁协同验证：正常更新活跃记录时 version 递增，并发旧版本更新被阻断影响 0 行")
    void shouldIncrementVersionOnUpdateAndBlockStaleVersion() {
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            SkuMapper mapper = session.getMapper(SkuMapper.class);

            SkuEntity sku = new SkuEntity();
            sku.setCode("SKU-VERSION-TEST");
            sku.setName("Version Initial");
            sku.setVersion(1);
            sku.setDeleted(0L);
            mapper.insert(sku);
            Long id = sku.getId();

            // 第一次正常更新，乐观锁生效且 version 自增
            sku.setName("Version Updated");
            int updateRows = mapper.updateById(sku);
            assertThat(updateRows).isEqualTo(1);
            assertThat(sku.getVersion()).isEqualTo(2);

            // 模拟并发旧版本：使用 version=1 尝试更新，被乐观锁拦截
            SkuEntity staleSku = new SkuEntity();
            staleSku.setId(id);
            staleSku.setCode("SKU-VERSION-TEST");
            staleSku.setName("Stale Update");
            staleSku.setVersion(1);
            staleSku.setDeleted(0L);
            int staleRows = mapper.updateById(staleSku);
            assertThat(staleRows).isZero();
        }
    }
}
