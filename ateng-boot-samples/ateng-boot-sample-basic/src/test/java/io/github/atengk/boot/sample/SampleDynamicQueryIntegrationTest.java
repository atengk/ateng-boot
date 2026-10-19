package io.github.atengk.boot.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.SortItem;
import io.github.atengk.boot.mybatisplus.dynamicquery.DynamicQueryRequest;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryCondition;
import io.github.atengk.boot.mybatisplus.dynamicquery.QueryOperator;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlDefinition;
import io.github.atengk.boot.mybatisplus.dynamicsql.DynamicSqlRegistry;
import io.github.atengk.boot.mybatisplus.permission.DataPermissionHandler;
import io.github.atengk.boot.sample.entity.SysDocumentEntity;
import io.github.atengk.boot.sample.entity.TenantOrderEntity;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.entity.UserRoleEntity;
import io.github.atengk.boot.sample.mapper.SysDocumentMapper;
import io.github.atengk.boot.sample.mapper.TenantOrderMapper;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.mapper.UserRoleMapper;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 持久层动态查询转译、多表 AST 自愈与拦截器协同全链路端到端集成测试
 * 覆盖 Controller -> Service -> Mapper / XML / External SQL -> H2 -> VO 的完整调用，
 * 并全真验证与 @DataScope 数据权限和 TenantLineInnerInterceptor 多租户行级隔离的正交无冲突协同。
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest(properties = {
        "ateng.boot.mybatis-plus.tenant.enabled=true",
        "ateng.boot.mybatis-plus.tenant.column=tenant_id",
        "ateng.boot.mybatis-plus.tenant.ignore-tables=sys_dict,sys_user,sys_user_role,sys_document",
        "ateng.boot.mybatis-plus.data-permission.enabled=true",
        "ateng.boot.mybatis-plus.dynamic-sql.enabled=true"
})
@AutoConfigureMockMvc
@Import(SampleDynamicQueryIntegrationTest.TestDataPermissionConfig.class)
@DisplayName("动态查询全链路与拦截器协同端到端集成测试")
class SampleDynamicQueryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private SysDocumentMapper sysDocumentMapper;

    @Autowired
    private TenantOrderMapper tenantOrderMapper;

    @Autowired
    private DynamicSqlRegistry dynamicSqlRegistry;

    /**
     * 模拟测试数据权限上下文
     */
    static class SecurityContext {
        static final ThreadLocal<String> SCOPE_MODE = new ThreadLocal<>(); // "SELF", "DEPT", "ALL"
        static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();
        static final ThreadLocal<List<Long>> CURRENT_DEPT_IDS = new ThreadLocal<>();

        static void clear() {
            SCOPE_MODE.remove();
            CURRENT_USER_ID.remove();
            CURRENT_DEPT_IDS.remove();
        }
    }

    @TestConfiguration
    static class TestDataPermissionConfig {

        @Bean
        @Primary
        public DataPermissionHandler dynamicTestDataPermissionHandler() {
            return (table, where, mappedStatementId, dataScope) -> {
                String mode = SecurityContext.SCOPE_MODE.get();
                if (mode == null || "ALL".equals(mode)) {
                    return null;
                }

                String prefix = dataScope.alias().isBlank() ? "" : dataScope.alias() + ".";

                if ("SELF".equals(mode)) {
                    Long userId = SecurityContext.CURRENT_USER_ID.get();
                    if (userId == null) {
                        return null;
                    }
                    EqualsTo equalsTo = new EqualsTo();
                    equalsTo.setLeftExpression(new Column(prefix + dataScope.userColumn()));
                    equalsTo.setRightExpression(new LongValue(userId));
                    return equalsTo;
                }

                if ("DEPT".equals(mode)) {
                    List<Long> deptIds = SecurityContext.CURRENT_DEPT_IDS.get();
                    if (deptIds == null || deptIds.isEmpty()) {
                        return null;
                    }
                    ParenthesedExpressionList<LongValue> list = new ParenthesedExpressionList<>();
                    for (Long id : deptIds) {
                        list.add(new LongValue(id));
                    }
                    InExpression inExpression = new InExpression();
                    inExpression.setLeftExpression(new Column(prefix + dataScope.deptColumn()));
                    inExpression.setRightExpression(list);
                    return inExpression;
                }
                return null;
            };
        }
    }

    @BeforeEach
    @AfterEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("DELETE FROM sys_user_role");
        jdbcTemplate.execute("DELETE FROM sys_document");
        jdbcTemplate.execute("DELETE FROM sys_tenant_order");
        SecurityContext.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. Web Controller 单表动态查询直出：条件过滤、排序、脱敏与 Result 契约闭环")
    void shouldExecuteSingleTableDynamicQueryFromController() throws Exception {
        // 准备初始用户数据
        UserEntity u1 = new UserEntity();
        u1.setName("张三");
        u1.setAge(25);
        u1.setEmail("zhangsan@example.com");
        u1.setPhone("13800138000");
        u1.setIdCard("110101199003072345");
        userMapper.insert(u1);

        UserEntity u2 = new UserEntity();
        u2.setName("李四");
        u2.setAge(30);
        u2.setEmail("lisi@example.com");
        u2.setPhone("13900139000");
        userMapper.insert(u2);

        UserEntity u3 = new UserEntity();
        u3.setName("张伟");
        u3.setAge(18);
        u3.setEmail("zhangwei@example.com");
        u3.setPhone("13700137000");
        userMapper.insert(u3);

        // 构造动态请求：name LIKE '张' AND age >= 20，按 age DESC 排序
        DynamicQueryRequest request = new DynamicQueryRequest(1, 10);
        request.addCondition(QueryCondition.of("name", QueryOperator.LIKE, "张"));
        request.addCondition(QueryCondition.of("age", QueryOperator.GE, 20));
        request.addSort(SortItem.desc("age"));

        MvcResult mvcResult = mockMvc.perform(post("/api/dynamic-query/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("张三"))
                .andExpect(jsonPath("$.data.records[0].age").value(25))
                // 验证出库动态脱敏生效（手机号与身份证号被打掩码）
                .andExpect(jsonPath("$.data.records[0].phone").value("138****8000"))
                .andExpect(jsonPath("$.data.records[0].idCard").value("110101********2345"))
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString();
        JsonNode rootNode = objectMapper.readTree(responseBody);
        assertThat(rootNode.get("data").get("records").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("2. Web Controller Mapper XML 联表方法引用直出：AST 投影推导与别名自愈")
    void shouldExecuteXmlJoinDynamicQueryFromController() throws Exception {
        // 准备用户与角色关联数据
        UserEntity u1 = new UserEntity();
        u1.setName("王小明");
        u1.setAge(28);
        u1.setEmail("wang@example.com");
        userMapper.insert(u1);

        UserRoleEntity r1 = new UserRoleEntity();
        r1.setUserId(u1.getId());
        r1.setRoleId(888L);
        userRoleMapper.insert(r1);

        UserEntity u2 = new UserEntity();
        u2.setName("王大力");
        u2.setAge(35);
        u2.setEmail("dali@example.com");
        userMapper.insert(u2);

        UserRoleEntity r2 = new UserRoleEntity();
        r2.setUserId(u2.getId());
        r2.setRoleId(999L);
        userRoleMapper.insert(r2);

        // 前端仅传递 VO 的平铺字段 name 和 roleId，不带表前缀
        DynamicQueryRequest request = new DynamicQueryRequest(1, 10);
        request.addCondition(QueryCondition.of("name", QueryOperator.LIKE, "王"));
        request.addCondition(QueryCondition.of("roleId", QueryOperator.EQ, 888L));
        request.addSort(SortItem.desc("roleId"));

        mockMvc.perform(post("/api/dynamic-query/user-roles/xml")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("王小明"))
                .andExpect(jsonPath("$.data.records[0].roleId").value(888))
                .andExpect(jsonPath("$.data.records[0].age").value(28));
    }

    @Test
    @DisplayName("3. Web Controller 外部动态 SQL 动态查询与热替换即时生效")
    void shouldExecuteExternalSqlDynamicQueryAndHotReload() throws Exception {
        // 1. 注册初始外部动态 SQL
        dynamicSqlRegistry.register(DynamicSqlDefinition.builder()
                .id("queryExternalUserRole")
                .sqlScript("SELECT u.id AS id, u.name AS name, u.age AS age, u.email AS email, r.role_id AS role_id " +
                        "FROM sys_user u LEFT JOIN sys_user_role r ON u.id = r.user_id")
                .description("外部动态用户角色联表 SQL")
                .build());

        // 准备数据
        UserEntity u1 = new UserEntity();
        u1.setName("赵六");
        u1.setAge(22);
        u1.setEmail("zhao@example.com");
        userMapper.insert(u1);

        UserRoleEntity r1 = new UserRoleEntity();
        r1.setUserId(u1.getId());
        r1.setRoleId(601L);
        userRoleMapper.insert(r1);

        UserEntity u2 = new UserEntity();
        u2.setName("赵钱孙");
        u2.setAge(40);
        u2.setEmail("zhaoqs@example.com");
        userMapper.insert(u2);

        UserRoleEntity r2 = new UserRoleEntity();
        r2.setUserId(u2.getId());
        r2.setRoleId(602L);
        userRoleMapper.insert(r2);

        // 2. 初始调用：age > 20
        DynamicQueryRequest request = new DynamicQueryRequest(1, 10);
        request.addCondition(QueryCondition.of("name", QueryOperator.LIKE, "赵"));
        request.addCondition(QueryCondition.of("age", QueryOperator.GT, 20));

        mockMvc.perform(post("/api/dynamic-query/user-roles/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        // 3. 执行外部 SQL 热替换：修改底层 SQL 增加前置过滤条件 (age >= 30)
        dynamicSqlRegistry.register(DynamicSqlDefinition.builder()
                .id("queryExternalUserRole")
                .sqlScript("SELECT u.id AS id, u.name AS name, u.age AS age, u.email AS email, r.role_id AS role_id " +
                        "FROM sys_user u LEFT JOIN sys_user_role r ON u.id = r.user_id WHERE u.age >= 30")
                .description("热替换后的外部动态 SQL")
                .build());

        // 4. 再次调用：自适应改写语句与投影缓存即时刷新，命中热替换后的新 SQL
        mockMvc.perform(post("/api/dynamic-query/user-roles/sql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("赵钱孙"))
                .andExpect(jsonPath("$.data.records[0].age").value(40));
    }

    @Test
    @DisplayName("4. @DataScope 细粒度数据权限与动态查询条件正交协同")
    void shouldCooperateWithDataScopePermissionInterceptor() throws Exception {
        // 准备文档数据：不同部门与标题
        SysDocumentEntity d1 = new SysDocumentEntity();
        d1.setTitle("重要战略规划");
        d1.setDeptId(10L);
        d1.setUserId(1L);
        d1.setCreateTime(LocalDateTime.now());
        sysDocumentMapper.insert(d1);

        SysDocumentEntity d2 = new SysDocumentEntity();
        d2.setTitle("重要财务预算");
        d2.setDeptId(20L);
        d2.setUserId(2L);
        d2.setCreateTime(LocalDateTime.now());
        sysDocumentMapper.insert(d2);

        SysDocumentEntity d3 = new SysDocumentEntity();
        d3.setTitle("重要竞品分析");
        d3.setDeptId(30L); // 未授权部门
        d3.setUserId(3L);
        d3.setCreateTime(LocalDateTime.now());
        sysDocumentMapper.insert(d3);

        SysDocumentEntity d4 = new SysDocumentEntity();
        d4.setTitle("日常会议纪要");
        d4.setDeptId(10L); // 虽有部门权限，但标题不含"重要"
        d4.setUserId(1L);
        d4.setCreateTime(LocalDateTime.now());
        sysDocumentMapper.insert(d4);

        // 设置当前用户部门权限为 [10, 20]
        SecurityContext.SCOPE_MODE.set("DEPT");
        SecurityContext.CURRENT_DEPT_IDS.set(List.of(10L, 20L));

        // 动态查询条件：title LIKE '重要'
        DynamicQueryRequest request = new DynamicQueryRequest(1, 10);
        request.addCondition(QueryCondition.of("title", QueryOperator.LIKE, "重要"));

        // 预期结果：仅 d1(10,重要) 与 d2(20,重要) 符合条件，d3(30)与 d4(日常)被双向拦截过滤
        mockMvc.perform(post("/api/dynamic-query/documents/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    @DisplayName("5. TenantLineInnerInterceptor 多租户行级隔离与动态查询条件正交协同")
    void shouldCooperateWithTenantLineInterceptor() throws Exception {
        // 准备不同租户订单数据
        TenantOrderEntity o1 = new TenantOrderEntity();
        o1.setOrderNo("ORD-T1001-001");
        o1.setAmount(new BigDecimal("188.00"));
        o1.setTenantId("1001");
        tenantOrderMapper.insert(o1);

        TenantOrderEntity o2 = new TenantOrderEntity();
        o2.setOrderNo("ORD-T1001-002");
        o2.setAmount(new BigDecimal("30.00")); // 金额不足 100
        o2.setTenantId("1001");
        tenantOrderMapper.insert(o2);

        TenantOrderEntity o3 = new TenantOrderEntity();
        o3.setOrderNo("ORD-T2002-001");
        o3.setAmount(new BigDecimal("299.00"));
        o3.setTenantId("2002");
        tenantOrderMapper.insert(o3);

        // 设置租户上下文为 1001
        TenantContextHolder.setTenantId(1001L);

        // 动态查询：orderNo LIKE 'ORD' AND amount >= 100
        DynamicQueryRequest request = new DynamicQueryRequest(1, 10);
        request.addCondition(QueryCondition.of("orderNo", QueryOperator.LIKE, "ORD"));
        request.addCondition(QueryCondition.of("amount", QueryOperator.GE, new BigDecimal("100.00")));

        // 验证租户 1001 下仅查出 o1
        mockMvc.perform(post("/api/dynamic-query/tenant-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].orderNo").value("ORD-T1001-001"))
                .andExpect(jsonPath("$.data.records[0].tenantId").value("1001"));

        // 切换租户上下文为 2002，调用相同端点，仅查出 o3
        TenantContextHolder.setTenantId(2002L);
        mockMvc.perform(post("/api/dynamic-query/tenant-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].orderNo").value("ORD-T2002-001"))
                .andExpect(jsonPath("$.data.records[0].tenantId").value("2002"));
    }

    @Test
    @DisplayName("6. 对抗性极限边界防卫：空条件、黑名单敏感列拦截与空集合保底")
    void shouldHandleAdversarialEdgeCasesDefensively() throws Exception {
        UserEntity u = new UserEntity();
        u.setName("孙悟空");
        u.setAge(500);
        u.setEmail("wukong@example.com");
        userMapper.insert(u);

        // 1. 空条件：返回全量分页，保底非空列表
        DynamicQueryRequest emptyRequest = new DynamicQueryRequest(1, 10);
        mockMvc.perform(post("/api/dynamic-query/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(emptyRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        // 2. 注入黑名单字段 (password) 与未声明字段 (non_exist_col)：安全忽略
        DynamicQueryRequest attackRequest = new DynamicQueryRequest(1, 10);
        attackRequest.addCondition(QueryCondition.of("password", QueryOperator.EQ, "123456"));
        attackRequest.addCondition(QueryCondition.of("unknownCol", QueryOperator.LIKE, "hacker"));
        attackRequest.addCondition(QueryCondition.of("name", QueryOperator.EQ, "孙悟空"));

        mockMvc.perform(post("/api/dynamic-query/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attackRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].name").value("孙悟空"));

        // 3. 无匹配数据：保底返回空列表，绝不返回 null
        DynamicQueryRequest noMatchRequest = new DynamicQueryRequest(1, 10);
        noMatchRequest.addCondition(QueryCondition.of("name", QueryOperator.EQ, "不存在的用户"));

        mockMvc.perform(post("/api/dynamic-query/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noMatchRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.records").isEmpty());
    }
}
