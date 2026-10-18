package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.id.Snowflake;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.entity.UserRoleEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import io.github.atengk.boot.sample.mapper.UserRoleMapper;
import io.github.atengk.boot.mybatisplus.handler.EncryptTypeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import io.github.atengk.boot.sample.service.AsyncSampleService;
import io.github.atengk.boot.sample.service.AsyncSampleService.AsyncExecutionResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ateng-boot 全技术栈端到端自动化回归与准出集成门禁测试
 * 覆盖显式 Result 契约、全链路追踪、超大 Long 精度防御、时间序列化、异常脱敏、MyBatis-Plus 审计与方言插件链全场景
 *
 * @author Ateng
 * @since 2026-10-15
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ateng-boot 全栈自动化回归集成门禁测试")
class SampleApplicationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private UserRoleMapper userRoleMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AsyncSampleService asyncSampleService;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. 显式契约与全链路追踪：成功响应 HTTP 200、携带 X-Trace-Id 并在 MDC/JSON 闭环")
    void shouldReturnExplicitResultWithTracingOnSuccess() throws Exception {
        String json = """
                {
                    "name": "张三",
                    "age": 28,
                    "email": "zhangsan@example.com"
                }
                """;

        MvcResult mvcResult = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString();
        String headerTraceId = mvcResult.getResponse().getHeader("X-Trace-Id");
        JsonNode rootNode = objectMapper.readTree(responseBody);
        assertThat(rootNode.get("traceId").asText()).isEqualTo(headerTraceId);

        // 验证 TraceFilter 在请求结束后严格清理 MDC traceId 避免线程池污染
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. 全链路追踪：客户端自定义 X-Trace-Id 优先提取并在响应头与载荷中准确回写")
    void shouldRespectCustomTraceIdFromRequestHeader() throws Exception {
        String customTraceId = "trace-client-88888888";

        mockMvc.perform(get("/api/users/date-time-demo")
                        .header("X-Trace-Id", customTraceId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", customTraceId))
                .andExpect(jsonPath("$.traceId").value(customTraceId));

        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("3. 精度安全：超大 Long（1846182948192841920L）由 Jackson 自动序列化为 String 防前端精度丢失")
    void shouldSerializeLargeLongAsString() throws Exception {
        Long specificLargeId = 1846182948192841920L;
        UserEntity user = new UserEntity();
        user.setId(specificLargeId);
        user.setName("李四");
        user.setAge(30);
        user.setEmail("lisi@example.com");
        userMapper.insert(user);

        MvcResult mvcResult = mockMvc.perform(get("/api/users/" + specificLargeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String content = mvcResult.getResponse().getContentAsString();
        // 关键断言：JSON 字符串中必须为严格的 "id":"1846182948192841920" 字符串形式，不得为裸数字
        assertThat(content).contains("\"id\":\"1846182948192841920\"");
    }

    @Test
    @DisplayName("4. 时间规范：LocalDateTime、LocalDate 与 LocalTime 统一按标准模式与 Asia/Shanghai 时区序列化")
    void shouldSerializeLocalDateTimeInStandardFormat() throws Exception {
        mockMvc.perform(get("/api/users/date-time-demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dateTime").value("2026-10-15 14:30:00"))
                .andExpect(jsonPath("$.data.date").value("2026-10-15"))
                .andExpect(jsonPath("$.data.time").value("14:30:00"));
    }

    @Test
    @DisplayName("5. 参数校验：@Valid 校验失败捕获并提取友好错误提示，HTTP 状态保持 200 业务码 400")
    void shouldHandleValidationExceptionWithFriendlyMessage() throws Exception {
        String invalidJson = """
                {
                    "name": "",
                    "age": -5,
                    "email": "not-an-email"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("6. 业务异常：受控 BusinessException 精准映射为语义化 Result")
    void shouldHandleBusinessExceptionCorrectly() throws Exception {
        mockMvc.perform(get("/api/users/error/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("用户积分余额不足"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("6.1 因果异常：转译带有底层 cause 的 BusinessException，严格保留堆栈因果链，映射语义化 Result 并关联 traceId")
    void shouldHandleCausalBusinessExceptionCorrectly() throws Exception {
        mockMvc.perform(get("/api/users/error/causal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("网关代理服务通信异常"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("7. 生产脱敏：未知根异常生产脱敏为统一文案，禁止泄露内部堆栈，traceId 贯穿可追溯")
    void shouldMaskUnexpectedExceptionAndLogWithTraceId() throws Exception {
        mockMvc.perform(get("/api/users/error/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("系统繁忙，请稍后再试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("8. 数据审计与雪花发号：插入时自动填充雪花 ID、时间与操作人，逻辑删除标记 @JsonIgnore 脱敏")
    void shouldAutoFillAuditFieldsAndSnowflakeIdOnInsert() throws Exception {
        UserEntity user = new UserEntity();
        user.setName("王五");
        user.setAge(25);
        user.setEmail("wangwu@example.com");

        userMapper.insert(user);

        UserEntity stored = userMapper.selectById(user.getId());
        assertThat(stored).isNotNull();
        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getCreateTime()).isNotNull();
        assertThat(stored.getUpdateTime()).isNotNull();
        assertThat(stored.getCreateBy()).isEqualTo("SYSTEM");
        assertThat(stored.getUpdateBy()).isEqualTo("SYSTEM");
        assertThat(stored.getVersion()).isEqualTo(0);
        assertThat(stored.getDeleted()).isEqualTo(0);

        // 验证 @JsonIgnore 确保 deleted 字段不对外暴露
        String json = objectMapper.writeValueAsString(stored);
        assertThat(json).doesNotContain("\"deleted\"");
    }

    @Test
    @DisplayName("9. 防御性审计策略：开发者显式赋值在插入时完整保留，不被自动填充无故冲刷覆盖")
    void shouldPreserveExplicitFieldValuesOnInsert() {
        UserEntity user = new UserEntity();
        user.setName("赵六");
        LocalDateTime explicitTime = LocalDateTime.of(2021, 5, 20, 13, 14, 0);
        user.setCreateTime(explicitTime);
        user.setCreateBy("manual-creator");

        userMapper.insert(user);

        UserEntity stored = userMapper.selectById(user.getId());
        assertThat(stored).isNotNull();
        assertThat(stored.getCreateTime()).isEqualTo(explicitTime);
        assertThat(stored.getCreateBy()).isEqualTo("manual-creator");
        // 未显式赋值的 updateTime 仍被自动填充
        assertThat(stored.getUpdateTime()).isNotNull();
        assertThat(stored.getUpdateBy()).isEqualTo("SYSTEM");
    }

    @Test
    @DisplayName("10. 乐观锁与 CAS 防并发更新：版本号自动比对流转，过期版本更新静默受阻")
    void shouldSupportOptimisticLockerCasUpdate() {
        UserEntity user = new UserEntity();
        user.setName("孙七");
        userMapper.insert(user);

        UserEntity copy1 = userMapper.selectById(user.getId());
        UserEntity copy2 = userMapper.selectById(user.getId());

        // 副本 1 率先更新，版本号由 0 升级至 1
        copy1.setName("孙七-版本1");
        int updatedRows1 = userMapper.updateById(copy1);
        assertThat(updatedRows1).isEqualTo(1);

        // 验证数据库最新实体：版本号自增为 1，且 updateTime 与 updateBy 正确自动刷新
        UserEntity reloaded1 = userMapper.selectById(user.getId());
        assertThat(reloaded1.getVersion()).isEqualTo(1);
        assertThat(reloaded1.getUpdateTime()).isNotNull();
        assertThat(reloaded1.getUpdateBy()).isEqualTo("SYSTEM");

        // 副本 2 携带旧版本号 0 提交更新，触发 CAS 冲突，更新受阻（影响行数为 0）
        copy2.setName("孙七-版本2-冲突");
        int updatedRows2 = userMapper.updateById(copy2);
        assertThat(updatedRows2).isEqualTo(0);
    }

    @Test
    @DisplayName("11. 分页安全防护：PaginationInnerInterceptor 生效且单页上限锁定 500 条")
    void shouldEnforceMaxLimitOnPagination() {
        Page<UserEntity> page = new Page<>(1, 1000);
        Page<UserEntity> result = userMapper.selectPage(page, null);

        // maxLimit 配置 500，当用户请求 1000 时，插件动态将其压制为 500
        assertThat(result.getSize()).isEqualTo(500L);
    }

    @Test
    @DisplayName("12. 契约解包：Result.getOrThrow() 成功正常解包，失败抛出语义化 BusinessException")
    void shouldUnpackResultWithGetOrThrow() {
        Result<String> successResult = Result.success("测试数据");
        assertThat(successResult.getOrThrow()).isEqualTo("测试数据");

        Result<String> failResult = Result.fail(403, "无权限访问");
        assertThatThrownBy(failResult::getOrThrow)
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权限访问");
    }

    @Test
    @DisplayName("13. 异步上下文传递：AsyncContext 装饰任务安全流转 MDC traceId 与 UserContext，并在执行完毕后彻底清理")
    void shouldPropagateContextAcrossThreadsWithAsyncContext() throws ExecutionException, InterruptedException {
        String testUser = "async-user-999";
        String testTrace = "trace-async-777";
        UserContextHolder.set(testUser);
        MDC.put("traceId", testTrace);

        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedTrace = new AtomicReference<>();

        Runnable task = AsyncContext.wrap(() -> {
            capturedUser.set(UserContextHolder.get());
            capturedTrace.set(MDC.get("traceId"));
        });

        // 异步提交执行
        CompletableFuture.runAsync(task).get();

        assertThat(capturedUser.get()).isEqualTo(testUser);
        assertThat(capturedTrace.get()).isEqualTo(testTrace);

        // 主线程清理
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("14. 轻量实体兼容：仅继承 BaseIdEntity 的实体执行插入、查询、更新与物理删除，无 SQL 列缺失异常")
    void shouldSupportCrudOnBaseIdEntityWithoutExtraColumns() {
        UserRoleEntity mapping = new UserRoleEntity();
        mapping.setUserId(10086L);
        mapping.setRoleId(2001L);

        // 1. 验证插入：自动发号雪花 ID，且 DefaultMetaObjectHandler 安全跳过不存在的时间与操作人字段
        int inserted = userRoleMapper.insert(mapping);
        assertThat(inserted).isEqualTo(1);
        assertThat(mapping.getId()).isNotNull();

        // 2. 验证单记录查询：底层不附加额外 deleted 条件
        UserRoleEntity stored = userRoleMapper.selectById(mapping.getId());
        assertThat(stored).isNotNull();
        assertThat(stored.getUserId()).isEqualTo(10086L);
        assertThat(stored.getRoleId()).isEqualTo(2001L);

        // 3. 验证更新：底层不附加 version 乐观锁与 update_time 字段
        stored.setRoleId(2002L);
        int updated = userRoleMapper.updateById(stored);
        assertThat(updated).isEqualTo(1);

        UserRoleEntity reloaded = userRoleMapper.selectById(mapping.getId());
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getRoleId()).isEqualTo(2002L);

        // 4. 验证物理删除：执行真正的 DELETE FROM 物理移除，而非 UPDATE 软删除
        int deleted = userRoleMapper.deleteById(mapping.getId());
        assertThat(deleted).isEqualTo(1);
        assertThat(userRoleMapper.selectById(mapping.getId())).isNull();
    }

    @Test
    @DisplayName("15. 软删除生命周期：继承 BaseFullEntity 的实体执行 deleteById 变为逻辑删除，普通查询自动过滤且数据物理保留")
    void shouldSupportSoftDeleteFilterOnBaseFullEntity() {
        UserEntity user = new UserEntity();
        user.setName("软删除测试");
        user.setAge(18);
        user.setEmail("softdelete@example.com");
        userMapper.insert(user);

        Long userId = user.getId();
        assertThat(userMapper.selectById(userId)).isNotNull();

        // 执行 deleteById，由 @TableLogic 拦截执行 UPDATE sys_user SET deleted=1 WHERE id=? AND deleted=0
        int deleteRows = userMapper.deleteById(userId);
        assertThat(deleteRows).isEqualTo(1);

        // 普通 selectById 自动附加 WHERE deleted=0，查询结果为 null
        assertThat(userMapper.selectById(userId)).isNull();
    }

    @Test
    @DisplayName("16. 分页契约解耦与流式转换：/api/users/page 接收 PageQuery 并返回标准 PageResult<UserVO>，彻底消除 ORM 噪音与内部属性泄露")
    void shouldReturnDecoupledPageResultWithoutOrmNoise() throws Exception {
        UserEntity u1 = new UserEntity();
        u1.setName("测试分页A");
        u1.setAge(18);
        u1.setEmail("page_a@example.com");
        userMapper.insert(u1);

        UserEntity u2 = new UserEntity();
        u2.setName("测试分页B");
        u2.setAge(19);
        u2.setEmail("page_b@example.com");
        userMapper.insert(u2);

        MvcResult mvcResult = mockMvc.perform(get("/api/users/page")
                        .param("current", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records").isArray())
                .andReturn();

        String json = mvcResult.getResponse().getContentAsString();
        com.fasterxml.jackson.databind.JsonNode rootNode = objectMapper.readTree(json);
        com.fasterxml.jackson.databind.JsonNode dataNode = rootNode.get("data");

        // 核心契约断言：标准 5 要素存在且严格仅含 5 字段（Long 字段受全局 ToStringSerializer 守护防前端精度丢失）
        assertThat(dataNode.size()).isEqualTo(5);
        assertThat(dataNode.fieldNames()).toIterable().containsExactlyInAnyOrder("records", "total", "size", "current", "pages");
        assertThat(dataNode.get("current").asLong()).isEqualTo(1L);
        assertThat(dataNode.get("size").asLong()).isEqualTo(2L);
        assertThat(dataNode.get("total").asLong()).isGreaterThanOrEqualTo(2L);
        assertThat(dataNode.get("pages").asLong()).isGreaterThanOrEqualTo(1L);
        assertThat(dataNode.get("records").isArray()).isTrue();
        assertThat(dataNode.get("records").size()).isLessThanOrEqualTo(2);

        // 核心断言：绝对杜绝 MyBatis-Plus ORM 内部属性泄露
        assertThat(json).doesNotContain("optimizeCountSql");
        assertThat(json).doesNotContain("searchCount");
        assertThat(json).doesNotContain("orders");
        assertThat(json).doesNotContain("countId");
        assertThat(json).doesNotContain("maxLimit");

        // 核心断言：输出为 UserVO，实体内部的审计与逻辑删除字段不向外暴露
        assertThat(json).doesNotContain("createBy");
        assertThat(json).doesNotContain("updateBy");
        assertThat(json).doesNotContain("deleted");
        assertThat(json).doesNotContain("version");
    }

    @Test
    @DisplayName("17. @Async 受管线程池上下文传播：异步执行无缝继承 MDC traceId 与 UserContext，数据库插入自动填充登录人而非 SYSTEM")
    void shouldPropagateContextInAsyncMethodAndAuditCurrentUser() throws Exception {
        String testUser = "async-login-admin";
        String testTrace = "trace-async-99999";
        UserContextHolder.set(testUser);
        MDC.put("traceId", testTrace);

        UserEntity user = new UserEntity();
        user.setName("异步测试用户");
        user.setAge(30);
        user.setEmail("async_user@example.com");

        CompletableFuture<AsyncExecutionResult> future = asyncSampleService.saveUserAsync(user);
        AsyncExecutionResult executionResult = future.get(5, TimeUnit.SECONDS);

        // 验证异步线程池命名符合默认前缀 ateng-async-
        assertThat(executionResult.threadName()).startsWith("ateng-async-");

        // 验证异步线程内部成功获取到父线程的 traceId 与 UserContext
        assertThat(executionResult.traceId()).isEqualTo(testTrace);
        assertThat(executionResult.userId()).isEqualTo(testUser);

        // 验证持久层审计：在异步线程内插入的数据，createBy 与 updateBy 为当前登录人，而非 SYSTEM
        UserEntity stored = userMapper.selectById(executionResult.entityId());
        assertThat(stored).isNotNull();
        assertThat(stored.getCreateBy()).isEqualTo(testUser);
        assertThat(stored.getUpdateBy()).isEqualTo(testUser);
    }

    @Test
    @DisplayName("18. @WebLog 接口审计：正常请求执行，切面透明拦截并返回正确业务结果")
    void shouldAuditWebLogSuccessfullyOnNormalRequest() throws Exception {
        String jsonPayload = """
                {
                    "name": "审计测试用户",
                    "age": 28,
                    "email": "audit_user@example.com"
                }
                """;

        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isString());
    }

    @Test
    @DisplayName("19. @WebLog 敏感字段排除：excludeParams 排除 password 等凭据防泄露")
    void shouldAuditWebLogWithExcludedParams() throws Exception {
        String loginJson = """
                {
                    "username": "sec-admin",
                    "password": "rawSuperPassword123"
                }
                """;

        mockMvc.perform(post("/api/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("mock-token-sec-admin"));
    }

    @Test
    @DisplayName("20. @WebLog 大文件上传：MultipartFile 参数自动占位替换，严防内存溢出")
    void shouldAuditWebLogWithFileUploadSafely() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", MediaType.APPLICATION_PDF_VALUE, "PDF content".getBytes()
        );

        mockMvc.perform(multipart("/api/users/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("report.pdf"));
    }

    @Test
    @DisplayName("21. @WebLog 异常感知：发生业务异常时，切面捕获异常上下文并由全局异常处理器统一脱敏")
    void shouldAuditWebLogOnException() throws Exception {
        mockMvc.perform(get("/api/users/error/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户积分余额不足"));
    }

    @Test
    @DisplayName("22. 持久层透明加密 @FieldEncrypt 与视图动态脱敏 @FieldSensitive 全生命周期端到端集成测试")
    void shouldVerifyFieldEncryptAndSensitiveLifecycleEndToEnd() throws Exception {
        // 1. 通过 Controller 接口创建携带手机号与身份证号的用户记录
        String rawPhone = "13812345678";
        String rawIdCard = "110101199003072345";
        String createJson = """
                {
                    "name": "安全测试用户",
                    "age": 30,
                    "email": "security@example.com",
                    "phone": "%s",
                    "idCard": "%s"
                }
                """.formatted(rawPhone, rawIdCard);

        MvcResult createResult = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode createNode = objectMapper.readTree(createResult.getResponse().getContentAsString());
        String userIdStr = createNode.get("data").asText();
        Long userId = Long.valueOf(userIdStr);

        // 2. 原生 SQL 探查 H2 数据库底层存储：直接断言物理字段值为 AES 密文，绝对不包含原始明文！
        java.util.Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT phone, id_card FROM sys_user WHERE id = ?", userId
        );
        String dbPhone = (String) rawDbRow.get("phone");
        String dbIdCard = (String) rawDbRow.get("id_card");

        assertThat(dbPhone)
                .isNotNull()
                .isNotEqualTo(rawPhone)
                .doesNotContain("1381234");
        assertThat(EncryptTypeHandler.decrypt(dbPhone)).isEqualTo(rawPhone);

        assertThat(dbIdCard)
                .isNotNull()
                .isNotEqualTo(rawIdCard)
                .doesNotContain("110101");
        assertThat(EncryptTypeHandler.decrypt(dbIdCard)).isEqualTo(rawIdCard);

        // 3. Mapper 查询实体：断言 Java 内存实体对象中的属性值为解密后的 100% 原始真实明文！
        UserEntity inMemoryEntity = userMapper.selectById(userId);
        assertThat(inMemoryEntity).isNotNull();
        assertThat(inMemoryEntity.getPhone()).isEqualTo(rawPhone);
        assertThat(inMemoryEntity.getIdCard()).isEqualTo(rawIdCard);

        // 4. 请求 Controller 接口：断言响应 JSON 中敏感数据呈现为星号掩码（视图层出库脱敏）
        mockMvc.perform(get("/api/users/" + userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("138****5678"))
                .andExpect(jsonPath("$.data.idCard").value("110101********2345"))
                .andExpect(jsonPath("$.data.name").value("安全测试用户"));

        // 5. 再次验证内存真明文不变量：经过 Controller/Jackson 序列化后，实体对象内的字段值依然保持真实明文，未被任何星号污染
        assertThat(inMemoryEntity.getPhone()).isEqualTo(rawPhone);
        assertThat(inMemoryEntity.getIdCard()).isEqualTo(rawIdCard);

        // 6. 调用 updateById 修改实体其他属性（如姓名），验证更新后加密字段依然保持合法密文，绝不会把掩码星号写入数据库
        inMemoryEntity.setName("安全测试用户_已更名");
        userMapper.updateById(inMemoryEntity);

        java.util.Map<String, Object> updatedDbRow = jdbcTemplate.queryForMap(
                "SELECT name, phone, id_card FROM sys_user WHERE id = ?", userId
        );
        assertThat(updatedDbRow.get("name")).isEqualTo("安全测试用户_已更名");

        String updatedDbPhone = (String) updatedDbRow.get("phone");
        String updatedDbIdCard = (String) updatedDbRow.get("id_card");

        assertThat(updatedDbPhone)
                .isNotNull()
                .doesNotContain("*")
                .doesNotContain("1381234");
        assertThat(EncryptTypeHandler.decrypt(updatedDbPhone)).isEqualTo(rawPhone);

        assertThat(updatedDbIdCard)
                .isNotNull()
                .doesNotContain("*")
                .doesNotContain("110101");
        assertThat(EncryptTypeHandler.decrypt(updatedDbIdCard)).isEqualTo(rawIdCard);
    }

    @Test
    @DisplayName("端到端测试：验证基于 SpEL 业务键精准防重、TTL过期恢复与自定义错误提示")
    void shouldPreventDuplicateOrderCreationWithSpelKey() throws Exception {
        String orderNo = "ORD_" + System.currentTimeMillis();
        String jsonPayload = """
                {
                    "orderNo": "%s",
                    "amount": 99.80
                }
                """.formatted(orderNo);

        // 1. 首个创建请求成功放行
        mockMvc.perform(post("/api/samples/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(orderNo));

        // 2. 窗口期内相同订单号重复提交被切面拦截
        mockMvc.perform(post("/api/samples/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("订单处理中，请勿重复提交"));

        // 3. 等待超时（2秒）后，锁自动失效，请求再次放行
        Thread.sleep(2200);
        mockMvc.perform(post("/api/samples/idempotent/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(orderNo));
    }

    @Test
    @DisplayName("端到端测试：验证无参 @Idempotent 通用按钮指纹哈希防抖与异参放行")
    void shouldPreventRapidButtonClickWithAutoFingerprint() throws Exception {
        String tokenA = "token_" + System.currentTimeMillis();
        String tokenB = tokenA + "_diff";

        // 1. 首个按钮提交成功放行
        mockMvc.perform(post("/api/samples/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("submitted: " + tokenA));

        // 2. 立即重复点击相同表单，指纹一致，被切面拦截
        mockMvc.perform(post("/api/samples/idempotent/button")
                        .param("formToken", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.msg").value("操作过于频繁，请稍后再试"));

        // 3. 提交不同参数，指纹不同，成功放行
        mockMvc.perform(post("/api/samples/idempotent/button")
                        .param("formToken", tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("submitted: " + tokenB));
    }

    @Test
    @DisplayName("端到端测试：验证业务执行发生异常时锁被立即释放，支持无等待重试")
    void shouldReleaseLockImmediatelyWhenBusinessThrowsException() throws Exception {
        String bizId = "BIZ_" + System.currentTimeMillis();

        // 1. 请求触发可恢复业务异常
        mockMvc.perform(post("/api/samples/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("可恢复业务校验失败"));

        // 2. 验证锁已被主动释放：无需等待 5 秒超时，立即修正参数重试即可成功
        mockMvc.perform(post("/api/samples/idempotent/fail-retry")
                        .param("bizId", bizId)
                        .param("fail", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("success: " + bizId));
    }

    @Test
    @DisplayName("23. 敏感数据脱敏策略扩展与持久层雪花发号器大一统端到端集成测试")
    void shouldVerifyExtendedDesensitizeAndSnowflakeIntegrationEndToEnd() throws Exception {
        // 1. 构造包含新增脱敏策略（地址、车牌号、IPv4、统一社会信用代码、自定义掩码）的入参
        String rawAddress = "北京市海淀区中关村南大街1号院";
        String rawCarLicense = "京A88888";
        String rawIpv4 = "192.168.1.100";
        String rawCreditCode = "91110000000000000X";
        String rawCustomMask = "ABCDEFG12345";

        String jsonPayload = """
                {
                    "name": "脱敏综合测试用户",
                    "age": 32,
                    "email": "desensitize@example.com",
                    "phone": "13912345678",
                    "idCard": "110101199003072345",
                    "address": "%s",
                    "carLicense": "%s",
                    "ipv4": "%s",
                    "socialCreditCode": "%s",
                    "customMask": "%s"
                }
                """.formatted(rawAddress, rawCarLicense, rawIpv4, rawCreditCode, rawCustomMask);

        // 2. 调用 POST /api/users 创建用户，验证持久层通过 Snowflake 自动发号入库
        MvcResult postResult = mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode postNode = objectMapper.readTree(postResult.getResponse().getContentAsString());
        Long generatedId = postNode.get("data").asLong();
        assertThat(generatedId).isPositive();

        // 验证生成的 ID 来源于 ateng-boot-core 的 Snowflake 引擎（合法的 workerId 与 datacenterId 范围 0~31）
        long workerId = Snowflake.getWorkerId(generatedId);
        long datacenterId = Snowflake.getDatacenterId(generatedId);
        assertThat(workerId).isBetween(0L, 31L);
        assertThat(datacenterId).isBetween(0L, 31L);

        // 3. 验证数据库物理存储与内存真明文不变量：
        // 探查 H2 数据库物理行：未加密字段必须 100% 存储真实明文，绝对不能在入库时被打码为星号！
        java.util.Map<String, Object> rawDbRow = jdbcTemplate.queryForMap(
                "SELECT address, car_license, ipv4, social_credit_code, custom_mask FROM sys_user WHERE id = ?", generatedId
        );
        assertThat(rawDbRow.get("address")).isEqualTo(rawAddress);
        assertThat(rawDbRow.get("car_license")).isEqualTo(rawCarLicense);
        assertThat(rawDbRow.get("ipv4")).isEqualTo(rawIpv4);
        assertThat(rawDbRow.get("social_credit_code")).isEqualTo(rawCreditCode);
        assertThat(rawDbRow.get("custom_mask")).isEqualTo(rawCustomMask);

        // 查询实体：Java 内存中对象属性必须为 100% 原始真实明文
        UserEntity entity = userMapper.selectById(generatedId);
        assertThat(entity).isNotNull();
        assertThat(entity.getAddress()).isEqualTo(rawAddress);
        assertThat(entity.getCarLicense()).isEqualTo(rawCarLicense);
        assertThat(entity.getIpv4()).isEqualTo(rawIpv4);
        assertThat(entity.getSocialCreditCode()).isEqualTo(rawCreditCode);
        assertThat(entity.getCustomMask()).isEqualTo(rawCustomMask);

        // 4. 调用 GET /api/users/{id}：断言 Controller 响应 JSON 中所有新增敏感字段动态脱敏掩码呈现
        mockMvc.perform(get("/api/users/" + generatedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.address").value("北京市海淀区*********"))
                .andExpect(jsonPath("$.data.carLicense").value("京A***88"))
                .andExpect(jsonPath("$.data.ipv4").value("192.168.1.*"))
                .andExpect(jsonPath("$.data.socialCreditCode").value("91************000X"))
                .andExpect(jsonPath("$.data.customMask").value("AB********45"));

        // 5. 调用 GET /api/users/page：断言解耦后的 PageResult<UserVO> 视图层同样正确动态脱敏
        MvcResult pageResult = mockMvc.perform(get("/api/users/page")
                        .param("current", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String pageJson = pageResult.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(pageJson)
                .contains("\"address\":\"北京市海淀区*********\"")
                .contains("\"carLicense\":\"京A***88\"")
                .contains("\"ipv4\":\"192.168.1.*\"")
                .contains("\"socialCreditCode\":\"91************000X\"")
                .contains("\"customMask\":\"AB********45\"");

        // 6. 验证防脏写回铁律：修改实体其他属性（如姓名）后执行 updateById，再次断言数据库物理字段依然保持真实原始值
        entity.setName("脱敏综合测试用户_已更名");
        userMapper.updateById(entity);

        java.util.Map<String, Object> updatedDbRow = jdbcTemplate.queryForMap(
                "SELECT address, car_license, ipv4, social_credit_code, custom_mask FROM sys_user WHERE id = ?", generatedId
        );
        assertThat(updatedDbRow.get("address")).isEqualTo(rawAddress);
        assertThat(updatedDbRow.get("car_license")).isEqualTo(rawCarLicense);
        assertThat(updatedDbRow.get("ipv4")).isEqualTo(rawIpv4);
        assertThat(updatedDbRow.get("social_credit_code")).isEqualTo(rawCreditCode);
        assertThat(updatedDbRow.get("custom_mask")).isEqualTo(rawCustomMask);
    }
}
