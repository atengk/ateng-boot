package io.github.atengk.boot.mybatisplus.dynamicsql;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mybatis.spring.SqlSessionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 通用编程式执行器 DynamicSqlExecutor 单元测试
 * 纯内存黑盒覆盖单条查询映射、列表批量查询、DML 分流执行及 ADR-0010 标准流式分页转换
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("通用编程式执行器 DynamicSqlExecutor 单元测试")
class DynamicSqlExecutorTest {

    private MybatisConfiguration configuration;
    private SqlSessionTemplate sqlSessionTemplate;
    private DynamicSqlRegistry registry;
    private DynamicSqlExecutor executor;

    @BeforeEach
    void setUp() {
        configuration = new MybatisConfiguration();
        sqlSessionTemplate = mock(SqlSessionTemplate.class);
        when(sqlSessionTemplate.getConfiguration()).thenReturn(configuration);

        registry = new DynamicSqlRegistry(configuration);
        executor = new DynamicSqlExecutor(sqlSessionTemplate, registry);
    }

    @Test
    @DisplayName("验证 selectOne 查询单条记录并映射为 Map 与自定义 VO")
    void testSelectOne() {
        String statementId = "user.querySingle";
        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE id = #{id}")
                .commandType(SqlCommandType.SELECT)
                .build());

        Map<String, Object> mockData = Map.of("id", 1L, "name", "Alice", "status", 1);
        when(sqlSessionTemplate.selectOne(eq(statementId), any())).thenReturn(mockData);

        // 1. 默认映射为 Map
        Map<String, Object> map = executor.selectOne(statementId, Map.of("id", 1));
        assertNotNull(map);
        assertEquals("Alice", map.get("name"));

        // 2. 映射为强类型 TestUserVO
        TestUserVO vo = executor.selectOne(statementId, Map.of("id", 1), TestUserVO.class);
        assertNotNull(vo);
        assertEquals(1L, vo.getId());
        assertEquals("Alice", vo.getName());
        assertEquals(1, vo.getStatus());

        // 3. 查无数据时安全返回 null
        when(sqlSessionTemplate.selectOne(eq(statementId), eq(Map.of("id", 999)))).thenReturn(null);
        TestUserVO nonExistent = executor.selectOne(statementId, Map.of("id", 999), TestUserVO.class);
        assertNull(nonExistent);
    }

    @Test
    @DisplayName("验证 selectList 批量查询多条记录及空数据集合保底")
    void testSelectList() {
        String statementId = "user.queryByStatus";
        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE status = #{status}")
                .commandType(SqlCommandType.SELECT)
                .build());

        List<Map<String, Object>> mockList = List.of(
                Map.of("id", 1L, "name", "Alice", "status", 1),
                Map.of("id", 2L, "name", "Bob", "status", 1)
        );
        when(sqlSessionTemplate.selectList(eq(statementId), any())).thenReturn((List) mockList);

        // 查有数据情况
        List<TestUserVO> list = executor.selectList(statementId, Map.of("status", 1), TestUserVO.class);
        assertNotNull(list);
        assertEquals(2, list.size());
        assertEquals("Alice", list.get(0).getName());
        assertEquals("Bob", list.get(1).getName());

        // 查无数据情况保底返回空 List，严禁返回 null
        when(sqlSessionTemplate.selectList(eq(statementId), eq(Map.of("status", 99)))).thenReturn(null);
        List<TestUserVO> emptyList = executor.selectList(statementId, Map.of("status", 99), TestUserVO.class);
        assertNotNull(emptyList);
        assertTrue(emptyList.isEmpty(), "查询无数据应当保底返回空集合");
    }

    @Test
    @DisplayName("验证 executeDml 自动分流 INSERT / UPDATE / DELETE 操作并准确返回受影响行数")
    void testExecuteDml() {
        // 1. 测试 INSERT
        String insertId = "user.insertSingle";
        registry.register(DynamicSqlDefinition.builder()
                .id(insertId)
                .sqlScript("INSERT INTO sys_user (id, name, status) VALUES (#{id}, #{name}, #{status})")
                .commandType(SqlCommandType.INSERT)
                .build());
        when(sqlSessionTemplate.insert(eq(insertId), any())).thenReturn(1);
        int insertRows = executor.executeDml(insertId, Map.of("id", 4L, "name", "David", "status", 1));
        assertEquals(1, insertRows);
        verify(sqlSessionTemplate, times(1)).insert(eq(insertId), any());

        // 2. 测试 UPDATE
        String updateId = "user.updateStatus";
        registry.register(DynamicSqlDefinition.builder()
                .id(updateId)
                .sqlScript("UPDATE sys_user SET status = #{status} WHERE id = #{id}")
                .commandType(SqlCommandType.UPDATE)
                .build());
        when(sqlSessionTemplate.update(eq(updateId), any())).thenReturn(2);
        int updateRows = executor.executeDml(updateId, Map.of("id", 4L, "status", 0));
        assertEquals(2, updateRows);
        verify(sqlSessionTemplate, times(1)).update(eq(updateId), any());

        // 3. 测试 DELETE
        String deleteId = "user.deleteSingle";
        registry.register(DynamicSqlDefinition.builder()
                .id(deleteId)
                .sqlScript("DELETE FROM sys_user WHERE id = #{id}")
                .commandType(SqlCommandType.DELETE)
                .build());
        when(sqlSessionTemplate.delete(eq(deleteId), any())).thenReturn(1);
        int deleteRows = executor.executeDml(deleteId, Map.of("id", 4L));
        assertEquals(1, deleteRows);
        verify(sqlSessionTemplate, times(1)).delete(eq(deleteId), any());
    }

    @Test
    @DisplayName("验证深度融合 ADR-0010 分页契约：自动触发物理分页并返回 PageResult<T>")
    void testSelectPage() {
        String statementId = "user.queryPage";
        registry.register(DynamicSqlDefinition.builder()
                .id(statementId)
                .sqlScript("SELECT * FROM sys_user WHERE status = #{status}")
                .commandType(SqlCommandType.SELECT)
                .build());

        // 模拟 MyBatis-Plus 拦截器执行后的分页数据回填
        doAnswer(invocation -> {
            Map<String, Object> paramMap = invocation.getArgument(1);
            Page<?> page = (Page<?>) paramMap.get("page");
            page.setTotal(25L);
            return List.of(
                    Map.of("id", 1L, "name", "Alice", "status", 1),
                    Map.of("id", 2L, "name", "Bob", "status", 1)
            );
        }).when(sqlSessionTemplate).selectList(eq(statementId), any());

        // 构造分页入参：第 2 页，每页 10 条
        PageQuery pageQuery = new PageQuery();
        pageQuery.setCurrent(2L);
        pageQuery.setSize(10L);

        PageResult<TestUserVO> pageResult = executor.selectPage(
                statementId,
                Map.of("status", 1),
                pageQuery,
                TestUserVO.class
        );

        assertNotNull(pageResult);
        assertEquals(25L, pageResult.getTotal(), "总记录数应当为 25");
        assertEquals(2L, pageResult.getCurrent(), "当前页码应当为 2");
        assertEquals(10L, pageResult.getSize(), "单页容量应当为 10");
        assertEquals(3L, pageResult.getPages(), "总页数应当向上取整为 3");
        assertNotNull(pageResult.getRecords());
        assertEquals(2, pageResult.getRecords().size());
        assertEquals("Alice", pageResult.getRecords().get(0).getName());
        assertEquals("Bob", pageResult.getRecords().get(1).getName());

        // 验证入参中挂载了 page 对象
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(sqlSessionTemplate).selectList(eq(statementId), captor.capture());
        Map capturedMap = captor.getValue();
        assertTrue(capturedMap.containsKey("page"), "分页参数中必须挂载 page 对象供 MyBatis-Plus 拦截器嗅探");
    }

    @Test
    @DisplayName("验证调用不存在的 statementId 时防御性抛出明确异常")
    void testNonExistentStatement() {
        assertThrows(IllegalArgumentException.class, () ->
                executor.selectOne("non.existent.statement", null, Map.class));

        assertThrows(IllegalArgumentException.class, () ->
                executor.selectList("non.existent.statement", null, Map.class));

        assertThrows(IllegalArgumentException.class, () ->
                executor.executeDml("non.existent.statement", null));

        assertThrows(IllegalArgumentException.class, () ->
                executor.selectPage("non.existent.statement", null, new PageQuery(), Map.class));
    }
}
