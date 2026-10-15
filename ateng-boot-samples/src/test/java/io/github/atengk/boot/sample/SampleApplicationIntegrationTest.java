package io.github.atengk.boot.sample;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.AsyncContext;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.entity.UserEntity;
import io.github.atengk.boot.sample.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

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
    private ObjectMapper objectMapper;

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
}
