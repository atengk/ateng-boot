package io.github.atengk.boot.mybatisplus.dynamicsql;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 动态 SQL 注册中心与无锁热替换单元测试
 * 纯内存黑盒覆盖 MappedStatement 编译装载、StrictMap 解锁、声明式 Mapper 接口绑定、热更新无锁并发与语法异常防灾回滚
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("动态 SQL 注册中心与无锁热替换单元测试")
class DynamicSqlRegistryTest {

    private MybatisConfiguration configuration;
    private DynamicSqlRegistry registry;

    @BeforeEach
    void setUp() {
        configuration = new MybatisConfiguration();
        // 注册测试 Mapper 接口
        configuration.addMapper(TestDynamicUserMapper.class);
        registry = new DynamicSqlRegistry(configuration);
    }

    @Test
    @DisplayName("验证动态注册 MappedStatement 结构与 BoundSql 生成")
    void testRegisterStatementStructure() {
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id("raw.selectUserList")
                .sqlScript("<script>SELECT * FROM sys_user <where><if test=\"status != null\">AND status = #{status}</if></where></script>")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .description("按状态查询用户")
                .build();

        boolean registered = registry.register(def);
        assertTrue(registered, "动态注册应当返回成功");
        assertTrue(registry.hasStatement("raw.selectUserList"));

        MappedStatement ms = configuration.getMappedStatement("raw.selectUserList");
        assertNotNull(ms);
        assertEquals(SqlCommandType.SELECT, ms.getSqlCommandType());
        assertNotNull(ms.getResultMaps());
        assertEquals(1, ms.getResultMaps().size());
        assertEquals(Map.class, ms.getResultMaps().get(0).getType());

        // 验证 BoundSql 动态标签与参数占位符生成
        BoundSql boundSql = ms.getBoundSql(Map.of("status", 1));
        assertNotNull(boundSql);
        String sql = boundSql.getSql();
        assertTrue(sql.contains("status = ?"), "动态标签应当被 XMLLanguageDriver 正确解析为参数占位符: " + sql);
    }

    @Test
    @DisplayName("验证声明式 Java @Mapper 接口方法匹配绑定动态 MappedStatement")
    void testDeclarativeMapperBinding() {
        // statementId 严格对应接口全限定名 + 方法名
        String statementId = TestDynamicUserMapper.class.getName() + ".findUsers";
        DynamicSqlDefinition def = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user <where><if test=\"name != null and name != ''\">AND name = #{name}</if></where>")
                .autoScriptWrap(true)
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();

        boolean registered = registry.register(def);
        assertTrue(registered);
        assertTrue(registry.hasStatement(statementId));

        // 验证 MapperProxy 对应的 MappedStatement 可以从 Configuration 中正确检索并生成 BoundSql
        MappedStatement ms = configuration.getMappedStatement(statementId);
        assertNotNull(ms);
        BoundSql boundSql = ms.getBoundSql(Map.of("name", "Alice"));
        assertTrue(boundSql.getSql().contains("name = ?"));
    }

    @Test
    @DisplayName("验证解除 StrictMap 限制：重复注册与热替换同 ID 语句无报错且即时更新 BoundSql")
    void testHotReloadWithoutStrictMapConflict() {
        String statementId = "test.hotReload";

        // 第 1 版 SQL: 查全部
        DynamicSqlDefinition defV1 = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();

        assertTrue(registry.register(defV1));
        MappedStatement ms1 = configuration.getMappedStatement(statementId);
        assertEquals("SELECT * FROM sys_user", ms1.getBoundSql(null).getSql().trim());

        // 第 2 版 SQL: 热替换为仅查 id = 1
        DynamicSqlDefinition defV2 = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE id = 1")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();

        // 连续热替换，绝不抛出 IllegalArgumentException (StrictMap already contains value)
        assertDoesNotThrow(() -> {
            boolean updated = registry.register(defV2);
            assertTrue(updated, "同 ID 热替换注册应当成功");
        });

        // 验证热更新立即生效，获取到的最新 MappedStatement 生成新 SQL
        MappedStatement ms2 = configuration.getMappedStatement(statementId);
        assertNotSame(ms1, ms2, "热更新后应当生成并注入新的 MappedStatement 实例");
        assertEquals("SELECT * FROM sys_user WHERE id = 1", ms2.getBoundSql(null).getSql().trim());
    }

    @Test
    @DisplayName("验证语法错误防灾回滚：更新非法 SQL 时记录告警并安全保留旧版本继续可用")
    void testResilienceRollbackOnSyntaxError() {
        String statementId = "test.resilient";

        // 1. 先注入一条正常的 SQL
        DynamicSqlDefinition validDef = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE id = #{id}")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();
        assertTrue(registry.register(validDef));

        MappedStatement oldMs = configuration.getMappedStatement(statementId);
        assertNotNull(oldMs);
        assertEquals("SELECT * FROM sys_user WHERE id = ?", oldMs.getBoundSql(Map.of("id", 1)).getSql().trim());

        // 2. 尝试热更新一条语法严重错误的 XML 脚本（未闭合的非法标签）
        DynamicSqlDefinition brokenDef = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("<script>SELECT * FROM sys_user <where><if test='unclosed'>AND id = 1</where></script>")
                .autoScriptWrap(false)
                .commandType(SqlCommandType.SELECT)
                .build();

        // 语法错误应被优雅捕获并返回 false，坚决不抛出未受控崩溃异常
        boolean updateSuccess = registry.register(brokenDef);
        assertFalse(updateSuccess, "语法错误的 SQL 更新应当被拒绝并返回 false");

        // 3. 验证旧版本 MappedStatement 依然安全存活且完全可用
        MappedStatement preservedMs = configuration.getMappedStatement(statementId);
        assertSame(oldMs, preservedMs, "语法错误时应当完整保留上一版本 MappedStatement");
        assertEquals("SELECT * FROM sys_user WHERE id = ?", preservedMs.getBoundSql(Map.of("id", 2)).getSql().trim());
    }

    @Test
    @DisplayName("验证多线程高并发读与热更新并发安全（无 NPE 与并发修改异常）")
    void testConcurrentReadAndHotReload() throws Exception {
        String statementId = "test.concurrent";
        DynamicSqlDefinition defA = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE id = 1")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();
        registry.register(defA);

        DynamicSqlDefinition defB = DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE id = 2")
                .commandType(SqlCommandType.SELECT)
                .resultType(Map.class)
                .build();

        int readerThreads = 8;
        int iterations = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(readerThreads + 1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(readerThreads + 1);

        // 8 个并发读线程，持续获取 MappedStatement 与 BoundSql
        for (int i = 0; i < readerThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < iterations; j++) {
                        MappedStatement ms = configuration.getMappedStatement(statementId);
                        if (ms != null && ms.getBoundSql(null) != null) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 1 个写线程在执行期间高频来回热替换 A 和 B
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int j = 0; j < iterations; j++) {
                    registry.register((j % 2 == 0) ? defB : defA);
                    Thread.sleep(1);
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get(), "多线程高并发读与热更新不应发生任何异常");
        assertEquals(readerThreads * iterations, successCount.get(), "并发读取全部成功且未发生瞬态 NPE");
    }

    @Test
    @DisplayName("验证与 ExternalSqlLoader 联动：loadAndRegisterAll 与 refresh")
    void testLoaderIntegration() {
        ExternalSqlLoader loader = new ExternalSqlLoader() {
            @Override
            public List<DynamicSqlDefinition> loadAll() {
                return List.of(
                        DynamicSqlDefinition.builder().id("loader.sql1").sqlScript("SELECT 1").build(),
                        DynamicSqlDefinition.builder().id("loader.sql2").sqlScript("SELECT 2").build()
                );
            }

            @Override
            public Optional<DynamicSqlDefinition> loadById(String id) {
                if ("loader.sql1".equals(id)) {
                    return Optional.of(DynamicSqlDefinition.builder().id("loader.sql1").sqlScript("SELECT 100").build());
                }
                return Optional.empty();
            }
        };

        DynamicSqlRegistry registryWithLoader = new DynamicSqlRegistry(configuration, loader);
        int loaded = registryWithLoader.loadAndRegisterAll();
        assertEquals(2, loaded);
        assertTrue(registryWithLoader.hasStatement("loader.sql1"));
        assertTrue(registryWithLoader.hasStatement("loader.sql2"));

        // 验证单条热刷新
        assertTrue(registryWithLoader.refresh("loader.sql1"));
        MappedStatement ms = configuration.getMappedStatement("loader.sql1");
        assertEquals("SELECT 100", ms.getBoundSql(null).getSql().trim());

        assertFalse(registryWithLoader.refresh("not.exist"));
    }

    @Test
    @DisplayName("验证 remove 动态注销已注册的 Statement")
    void testRemoveStatement() {
        String id = "test.toRemove";
        registry.register(DynamicSqlDefinition.builder().id(id).sqlScript("SELECT 1").build());
        assertTrue(registry.hasStatement(id));

        assertTrue(registry.remove(id));
        assertFalse(registry.hasStatement(id));
        assertFalse(registry.remove("not.exist.id"));
    }
}
