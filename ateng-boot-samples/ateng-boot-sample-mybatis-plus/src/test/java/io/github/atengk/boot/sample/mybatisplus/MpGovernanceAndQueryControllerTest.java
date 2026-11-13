package io.github.atengk.boot.sample.mybatisplus;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.sample.mybatisplus.model.DynamicSqlHotSwapDTO;
import io.github.atengk.boot.sample.mybatisplus.model.UserSearchDTO;
import io.github.atengk.boot.sample.mybatisplus.permission.SampleSecurityContext;
import io.github.atengk.boot.sample.mybatisplus.service.DynamicSqlScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 切片 2：SaaS 行级多租户、AST 数据权限、多源守卫与动态查询/外部动态 SQL Controller 集成测试
 * 覆盖 MpTenantAndPermissionController 与 MpDynamicQueryAndSqlController 全部正向与对抗性边界
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("切片2：多租户/数据权限/多源守卫与动态查询/动态SQL Controller 集成测试")
class MpGovernanceAndQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DynamicSqlScenarioService dynamicSqlScenarioService;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        DataPermissionContextHolder.clear();
        SampleSecurityContext.clear();
        DynamicDatasourceGuard.clearTaintedContext();
        dynamicSqlScenarioService.removeCustomSql("custom.webHotSwapDemo");
        jdbcTemplate.execute("DELETE FROM tenant_order");
        jdbcTemplate.execute("DELETE FROM sys_user");
        jdbcTemplate.execute("DELETE FROM sys_dept");
    }

    @Test
    @DisplayName("1. 验证行级多租户自动隔离查询与 @IgnoreTenant / runWithoutTenant 跨租户平台汇总")
    void shouldIsolateTenantOrdersAndBypassViaIgnoreTenantAndClosure() throws Exception {
        // 1.1 租户 A 视图下仅可见租户 A 的 2 笔订单
        mockMvc.perform(get("/sample/mybatis-plus/governance/tenant/orders")
                        .param("tenantId", "tenant_A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].tenantId", equalTo("tenant_A")));

        // 1.2 租户 B 视图下仅可见租户 B 的 1 笔订单
        mockMvc.perform(get("/sample/mybatis-plus/governance/tenant/orders")
                        .param("tenantId", "tenant_B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].tenantId", equalTo("tenant_B")));

        // 1.3 跨租户平台汇总对比：当前绑定 tenant_B (1 笔)，但 @IgnoreTenant 与 runWithoutTenant 均查出全平台 3 笔
        mockMvc.perform(get("/sample/mybatis-plus/governance/tenant/platform-summary")
                        .param("currentTenantId", "tenant_B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.currentTenantId", equalTo("tenant_B")))
                .andExpect(jsonPath("$.data.tenantScopedCount", equalTo(1)))
                .andExpect(jsonPath("$.data.ignoreTenantAnnotationCount", equalTo(3)))
                .andExpect(jsonPath("$.data.runWithoutTenantClosureCount", equalTo(3)));
    }

    @Test
    @DisplayName("2. 验证 @DataScope 部门与本人数据权限过滤及 runAsSuperAdmin / runWithoutPermission 短路放行")
    void shouldFilterByDataScopeAndShortCircuitOnSuperAdminOrClosure() throws Exception {
        // 2.1 仅授权部门 10：仅可见部门 10 的张三 (1 人)
        mockMvc.perform(get("/sample/mybatis-plus/governance/permission/users")
                        .param("deptIds", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name", equalTo("张三")));

        // 2.2 授权部门 10 + 本人操作人 creator-bob：可见部门 10 的张三 + creator-bob 创建的部门 30 王五 (共 2 人)
        mockMvc.perform(get("/sample/mybatis-plus/governance/permission/users")
                        .param("deptIds", "10")
                        .param("selfOperator", "creator-bob"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(2)));

        // 2.3 对比超管放行与闭包豁免：部门 10 下受限可见 1 人，超管与豁免闭包均短路放行可见全部 3 人
        mockMvc.perform(get("/sample/mybatis-plus/governance/permission/bypass-compare")
                        .param("deptIds", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.scopedCount", equalTo(1)))
                .andExpect(jsonPath("$.data.superAdminCount", equalTo(3)))
                .andExpect(jsonPath("$.data.withoutPermissionCount", equalTo(3)));
    }

    @Test
    @DisplayName("3. 验证 DynamicDatasourceGuard 干净上下文放行与活跃事务假切源 / 权限残留 / 租户不匹配阻断")
    void shouldPassCleanContextAndBlockActiveTransactionOrDirtyContext() throws Exception {
        // 3.1 干净上下文放行
        mockMvc.perform(post("/sample/mybatis-plus/governance/datasource-guard/check")
                        .param("mode", "clean"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.contextIsolated", equalTo(true)));

        // 3.2 对抗性边界：活跃事务内部尝试切源，触发 IllegalStateException 阻断并转译为 400
        mockMvc.perform(post("/sample/mybatis-plus/governance/datasource-guard/check")
                        .param("mode", "active-tx"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("TransactionActive=true")));

        // 3.3 对抗性边界：超管权限上下文未清理残留，触发阻断
        mockMvc.perform(post("/sample/mybatis-plus/governance/datasource-guard/check")
                        .param("mode", "super-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("isSuperAdmin=true")));

        // 3.4 对抗性边界：租户上下文不一致，触发阻断
        mockMvc.perform(post("/sample/mybatis-plus/governance/datasource-guard/check")
                        .param("mode", "tenant-check")
                        .param("currentTenantId", "TENANT_A")
                        .param("expectedTenantId", "TENANT_B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("租户上下文不一致")));
    }

    @Test
    @DisplayName("4. 验证 PageUtil 安全排序分页、URL 冒号高阶语法、@QueryField DTO 检索及 @DynamicQueryIgnore / @FieldEncrypt 六重防卫")
    void shouldEnforceSixLayerSecurityGuardInPageUtilUrlQueryAndSearchDto() throws Exception {
        // 4.1 PageUtil 安全排序：合法字段 age:desc 生效，黑名单字段 internalRemark:asc 与加密字段 phone:desc 被平滑忽略
        mockMvc.perform(get("/sample/mybatis-plus/query/page-safe")
                        .with(req -> {
                            req.setQueryString("current=1&size=10&sort=age:desc,internalRemark:asc,phone:desc");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(3)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("王五")))
                .andExpect(jsonPath("$.data.records[0].age", equalTo(35)))
                .andExpect(jsonPath("$.data.records[2].name", equalTo("张三")))
                .andExpect(jsonPath("$.data.records[2].age", equalTo(25)));

        // 4.2 URL 冒号高阶语法（含 ~ 波浪线范围、or_1: 作用域分组）与对抗性探针（非法操作符、@DynamicQueryIgnore、@FieldEncrypt 拦截）
        // 正常条件：age 在 20~32 之间 且 (name 包含 '张' 或 deptId=20) -> 命中张三(25, dept=10)与李四(30, dept=20)
        // 对抗探针：internalRemark:eq=不存在的机密（@DynamicQueryIgnore 被拦截忽略，不会导致查不到数据！）
        //          phone:eq=000000（@FieldEncrypt 被拦截忽略）
        //          name:invalidOp=xxx（非法操作符被拦截忽略）
        String adversarialQuery = "current=1&size=10"
                + "&age:between=20~32"
                + "&or_1:name:like=张&or_1:deptId:eq=20"
                + "&internalRemark:eq=不存在的机密"
                + "&phone:eq=000000"
                + "&name:invalidOp=xxx"
                + "&sort=age:asc,internalRemark:desc";

        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic/url")
                        .with(req -> {
                            req.setQueryString(adversarialQuery);
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("张三")))
                .andExpect(jsonPath("$.data.records[1].name", equalTo("李四")));

        // 4.3 @QueryField 业务检索 DTO 驱动分页（同时验证 internalRemark 被 @DynamicQueryIgnore 防越权忽略）
        UserSearchDTO searchDTO = new UserSearchDTO();
        searchDTO.setCurrent(1L);
        searchDTO.setSize(10L);
        searchDTO.setMinAge(28);
        searchDTO.setMaxAge(36);
        searchDTO.setDeptIds(List.of(20L, 30L));
        searchDTO.setInternalRemark("试图通过DTO探测受限备注字段");
        searchDTO.addSort("age", false);

        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic/dto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(searchDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("王五")))
                .andExpect(jsonPath("$.data.records[1].name", equalTo("李四")));

        // 4.4 确定性透明加密字段（email）URL 动态精确等值与 IN 检索实证（及落库密文一致性验证）
        String dbEmailZhangsan = jdbcTemplate.queryForObject("SELECT email FROM sys_user WHERE name = '张三'", String.class);
        assertThat(dbEmailZhangsan).startsWith("v1:");
        assertThat(dbEmailZhangsan).isEqualTo(io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler.encryptDeterministic("zhangsan@example.com"));

        // 4.4.1 email:eq 精确检索：前端传入明文，底层自动转译为确定性密文匹配
        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic/url")
                        .with(req -> {
                            req.setQueryString("email:eq=zhangsan@example.com");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("张三")))
                .andExpect(jsonPath("$.data.records[0].email", containsString("*"))); // 出参 @Sensitive 脱敏输出

        // 4.4.2 email:in 批量集合检索：前端传入多个明文，底层逐一转密文匹配
        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic/url")
                        .with(req -> {
                            req.setQueryString("email:in=zhangsan@example.com,lisi@example.com&sort=age:asc");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("张三")))
                .andExpect(jsonPath("$.data.records[1].name", equalTo("李四")));

        // 4.4.3 email DTO 声明式等值精确检索
        UserSearchDTO emailSearchDTO = new UserSearchDTO();
        emailSearchDTO.setEmail("zhangsan@example.com");
        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic/dto")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(emailSearchDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(1)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("张三")));

        // 4.5 对抗性阻断：确定性加密字段使用模糊查询或排序时触发快速失败拦截 (400)
        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic/url")
                        .with(req -> {
                            req.setQueryString("email:like=zhangsan");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("确定性加密字段")));

        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic/url")
                        .with(req -> {
                            req.setQueryString("sort=email:asc");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("不支持 ORDER BY 排序")));
    }

    @Test
    @DisplayName("5. 验证多表 JOIN 方法引用直出（pageDynamic / listDynamic AST 别名自愈）与 existsDynamic 单页短路判定")
    void shouldAutoHealMultiTableAliasesAndShortCircuitExists() throws Exception {
        // 5.1 多表联查分页：前端传入 VO 属性 deptName:like=研发&age:gte=26&sort=userId:asc，自动自愈为 d.dept_name 与 u.id
        mockMvc.perform(get("/sample/mybatis-plus/query/multi-table/page")
                        .with(req -> {
                            req.setQueryString("current=1&size=10&deptName:like=研发&age:gte=26&sort=userId:asc");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].userName", equalTo("李四")))
                .andExpect(jsonPath("$.data.records[0].deptName", equalTo("研发一部")))
                .andExpect(jsonPath("$.data.records[1].userName", equalTo("王五")))
                .andExpect(jsonPath("$.data.records[1].deptName", equalTo("研发二部")));

        // 5.2 多表联查列表：listDynamic 方法引用直出
        mockMvc.perform(get("/sample/mybatis-plus/query/multi-table/list")
                        .with(req -> {
                            req.setQueryString("deptName:eq=运营中心");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].userName", equalTo("孙七")));

        // 5.3 多表 existsDynamic 单页 Page(1, 1, false) 物理短路判定
        mockMvc.perform(get("/sample/mybatis-plus/query/multi-table/exists")
                        .with(req -> {
                            req.setQueryString("deptName:eq=研发二部&age:gte=30");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(get("/sample/mybatis-plus/query/multi-table/exists")
                        .with(req -> {
                            req.setQueryString("deptName:eq=不存在的部门");
                            return req;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(false)));
    }

    @Test
    @DisplayName("6. 验证外部动态 SQL 免 Mapper 分页、运行时零停机热替换及语法错误沙箱拦截回滚")
    void shouldExecuteExternalDynamicSqlAndHotSwapWithSandboxRollbackOnBadSyntax() throws Exception {
        // 6.1 免 Mapper 调用外部 XML 动态 SQL 分页查询
        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic-sql/page")
                        .param("name", "张")
                        .param("minAge", "20")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data.records", hasSize(2)))
                .andExpect(jsonPath("$.data.records[0].name", equalTo("张三")))
                .andExpect(jsonPath("$.data.records[1].name", equalTo("张伟")));

        // 6.2 运行时注册 V1 动态 SQL（仅返回 id 与 name）
        String statementId = "custom.webHotSwapDemo";
        DynamicSqlHotSwapDTO v1Dto = new DynamicSqlHotSwapDTO();
        v1Dto.setStatementId(statementId);
        v1Dto.setSqlScript("SELECT id, name FROM sys_user WHERE id = #{id}");
        v1Dto.setDescription("V1: 仅查询ID与姓名");
        v1Dto.setSampleParams(Map.of("id", 1001L));

        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic-sql/hot-swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(v1Dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)));

        // 6.3 零停机原子热替换为 V2 动态 SQL（扩展查询 age 与 dept_id）
        DynamicSqlHotSwapDTO v2Dto = new DynamicSqlHotSwapDTO();
        v2Dto.setStatementId(statementId);
        v2Dto.setSqlScript("SELECT id, name, age, dept_id FROM sys_user <where><if test=\"id != null\">AND id = #{id}</if></where>");
        v2Dto.setDescription("V2: 扩展查询年龄与部门ID");
        v2Dto.setSampleParams(Map.of("id", 1001L));

        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic-sql/hot-swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(v2Dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", hasSize(1)));

        // 6.4 对抗性边界：提交包含未闭合 XML 标签的损坏脚本，验证沙箱拦截返回 400 且保留 V2 正常可用
        DynamicSqlHotSwapDTO brokenDto = new DynamicSqlHotSwapDTO();
        brokenDto.setStatementId(statementId);
        brokenDto.setSqlScript("SELECT id, name FROM sys_user <where><if test=\"id != null\">AND id = #{id}</where>");
        brokenDto.setDescription("损坏的动态SQL脚本");
        brokenDto.setSampleParams(Map.of("id", 1001L));

        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic-sql/hot-swap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(brokenDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(400)))
                .andExpect(jsonPath("$.msg", containsString("内存沙箱已安全拦截并保留上一版本")));

        // 6.5 验证 DynamicSqlExecutor.exists 与 existsDynamic（含 extraParams 混合条件）存在性探测
        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic-sql/exists")
                        .param("name", "张")
                        .param("minAge", "20")
                        .param("deptId", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(get("/sample/mybatis-plus/query/dynamic-sql/exists")
                        .param("name", "张")
                        .param("deptId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(false)));

        UserSearchDTO existsDto = new UserSearchDTO();
        existsDto.setName("张");
        existsDto.setMinAge(20);
        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic-sql/exists-dynamic")
                        .param("deptId", "101")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(existsDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(true)));

        mockMvc.perform(post("/sample/mybatis-plus/query/dynamic-sql/exists-dynamic")
                        .param("deptId", "999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JsonUtil.toJsonString(existsDto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", equalTo(200)))
                .andExpect(jsonPath("$.data", equalTo(false)));
    }
}
