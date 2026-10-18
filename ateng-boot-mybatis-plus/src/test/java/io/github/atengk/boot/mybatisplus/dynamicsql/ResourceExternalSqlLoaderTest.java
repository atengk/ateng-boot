package io.github.atengk.boot.mybatisplus.dynamicsql;

import io.github.atengk.boot.mybatisplus.properties.DynamicSqlProperties;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 类路径/文件系统外部动态 SQL 资源加载器单元测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("类路径/文件系统外部动态 SQL 资源加载器单元测试")
class ResourceExternalSqlLoaderTest {

    @Test
    @DisplayName("验证扫描并解析标准 dynamic-sql XML 片段")
    void testLoadAllFromClassPath() {
        DynamicSqlProperties properties = new DynamicSqlProperties();
        properties.setLocations(List.of("classpath*:dynamic-sql/**/*.xml"));
        properties.setAutoScriptWrap(true);

        ExternalSqlLoader loader = new ResourceExternalSqlLoader(properties);
        List<DynamicSqlDefinition> definitions = loader.loadAll();

        assertNotNull(definitions);
        assertEquals(2, definitions.size(), "test-dynamic-sql.xml 应当解析出 2 条 SQL 定义");

        // 验证第一条 statement: user.findByName
        Optional<DynamicSqlDefinition> findByNameOpt = loader.loadById("user.findByName");
        assertTrue(findByNameOpt.isPresent(), "应当能根据 ID 查出 user.findByName");
        DynamicSqlDefinition findByName = findByNameOpt.get();
        assertEquals("user.findByName", findByName.getId());
        assertEquals(SqlCommandType.SELECT, findByName.getCommandType());
        assertEquals("根据名称查询用户", findByName.getDescription());
        assertTrue(findByName.getSqlScript().startsWith("<script>"), "含 <where>/<if> 应当自动容错包裹 <script>");
        assertTrue(findByName.getSqlScript().endsWith("</script>"));

        // 验证第二条 statement: user.updateStatus
        Optional<DynamicSqlDefinition> updateStatusOpt = loader.loadById("user.updateStatus");
        assertTrue(updateStatusOpt.isPresent());
        DynamicSqlDefinition updateStatus = updateStatusOpt.get();
        assertEquals(SqlCommandType.UPDATE, updateStatus.getCommandType());
        assertEquals("更新用户状态", updateStatus.getDescription());

        // 验证不存在的 statementId
        Optional<DynamicSqlDefinition> nonExistOpt = loader.loadById("non.exist.statement");
        assertTrue(nonExistOpt.isEmpty(), "查询不存在的 ID 应当返回 Optional.empty()");
    }

    @Test
    @DisplayName("验证扫描不存在的路径时防御性返回空列表，不抛出异常")
    void testLoadNonExistentLocation() {
        DynamicSqlProperties properties = new DynamicSqlProperties();
        properties.setLocations(List.of("classpath*:non-existent-sql-dir/**/*.xml"));

        ExternalSqlLoader loader = new ResourceExternalSqlLoader(properties);
        List<DynamicSqlDefinition> definitions = loader.loadAll();

        assertNotNull(definitions);
        assertTrue(definitions.isEmpty(), "路径不存在时应当防御性返回空集合，杜绝阻断应用启动");
    }
}
