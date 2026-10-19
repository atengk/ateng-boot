package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.UserRegisterDTO;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 契约层、参数校验与全局统一异常拦截端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 统一契约、第一道防线校验与异常防御端到端测试")
class WebContractSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. 显式契约与链路追踪：成功响应返回 HTTP 200，强类型 Result 包含 traceId 与 timestamp，响应头回写 X-Trace-Id")
    void shouldReturnExplicitResultWithTraceIdOnSuccess() throws Exception {
        String json = """
                {
                    "name": "张三",
                    "age": 28,
                    "email": "zhangsan@example.com",
                    "phone": "13812345678"
                }
                """;

        MvcResult mvcResult = mockMvc.perform(post("/api/sample/contract/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.msg").value("操作成功"))
                .andExpect(jsonPath("$.data.name").value("张三"))
                .andExpect(jsonPath("$.data.age").value(28))
                .andExpect(jsonPath("$.data.phone").value("138****5678")) // 视图动态脱敏
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString();
        String headerTraceId = mvcResult.getResponse().getHeader("X-Trace-Id");
        JsonNode rootNode = objectMapper.readTree(responseBody);
        assertThat(rootNode.get("traceId").asText()).isEqualTo(headerTraceId);

        // 验证请求完成后 MDC 严格清理
        assertThat(MDC.get("traceId")).isNull();
    }

    @Test
    @DisplayName("2. JSR-303 格式校验防卫：字段不合规时快速失败并提取友好提示（HTTP 200，业务码 400）")
    void shouldFailFastOnInvalidDto() throws Exception {
        String invalidJson = """
                {
                    "name": "",
                    "age": 0,
                    "email": "invalid-email",
                    "phone": "12345"
                }
                """;

        mockMvc.perform(post("/api/sample/contract/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("3. ValidList 批量校验防漏检：列表元素逐项校验，出现非法项时立即 Fail-Fast 拦截")
    void shouldValidateBatchListElementsWithValidList() throws Exception {
        // 非法批量数据（第二项手机号不合法）
        String batchJson = """
                [
                    {
                        "name": "用户A",
                        "age": 20,
                        "email": "a@example.com",
                        "phone": "13800001111"
                    },
                    {
                        "name": "用户B",
                        "age": 22,
                        "email": "b@example.com",
                        "phone": "invalid-phone"
                    }
                ]
                """;

        mockMvc.perform(post("/api/sample/contract/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("手机号格式不正确"));
    }

    @Test
    @DisplayName("4. ValidList 批量校验合规通过：所有元素合规放行并返回准确记录数")
    void shouldPassValidListWhenAllElementsAreValid() throws Exception {
        String batchJson = """
                [
                    {
                        "name": "用户A",
                        "age": 20,
                        "email": "a@example.com",
                        "phone": "13800001111"
                    },
                    {
                        "name": "用户B",
                        "age": 22,
                        "email": "b@example.com",
                        "phone": "13800002222"
                    }
                ]
                """;

        mockMvc.perform(post("/api/sample/contract/users/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(2));
    }

    @Test
    @DisplayName("5. 分组校验 ValidationGroups.Update：缺少主键 ID 时被严格拦截")
    void shouldEnforceUpdateGroupValidation() throws Exception {
        // 缺少 id 字段触发 Update 分组校验失败
        String updateJsonWithoutId = """
                {
                    "name": "李四",
                    "age": 30,
                    "email": "lisi@example.com",
                    "phone": "13900001111"
                }
                """;

        mockMvc.perform(put("/api/sample/contract/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJsonWithoutId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("更新操作用户ID不能为空"));

        // 携带合法 id 则正常通过
        String updateJsonWithId = """
                {
                    "id": 10086,
                    "name": "李四",
                    "age": 30,
                    "email": "lisi@example.com",
                    "phone": "13900001111"
                }
                """;

        mockMvc.perform(put("/api/sample/contract/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJsonWithId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value("10086"));
    }

    @Test
    @DisplayName("6. 精度与时间规范：超大 Long 转为 String 防失真，标准日期时间 yyyy-MM-dd HH:mm:ss 格式化")
    void shouldSerializeLargeLongAsStringAndFormatDateTime() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/sample/contract/datetime"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.dateTime").value("2026-10-19 14:30:00"))
                .andExpect(jsonPath("$.data.date").value("2026-10-19"))
                .andExpect(jsonPath("$.data.time").value("14:30:00"))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        // 验证 64 位雪花 ID 为严格 JSON 字符串形式，避免 JS IEEE 754 截断
        assertThat(content).contains("\"id\":\"1846182948192841920\"");
    }

    @Test
    @DisplayName("7. 业务异常转译：受控 BusinessException 映射为语义化 HTTP 200 业务错误")
    void shouldTranslateBusinessException() throws Exception {
        mockMvc.perform(get("/api/sample/contract/error/business"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("用户积分余额不足"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("8. 因果异常转译：带有 cause 根因的 BusinessException 保留因果调用栈并在前端安全呈现")
    void shouldTranslateCausalBusinessException() throws Exception {
        mockMvc.perform(get("/api/sample/contract/error/causal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("网关代理服务通信异常"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("9. 生产脱敏防线：未知根异常统一脱敏为友好文案，严禁泄露内部堆栈，traceId 闭环溯源")
    void shouldMaskUnexpectedExceptionInProduction() throws Exception {
        mockMvc.perform(get("/api/sample/contract/error/unexpected"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("系统繁忙，请稍后再试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("10. 契约解包测试：Result.getOrThrow() 正常解包与异常校验")
    void shouldSupportResultUnpack() {
        Result<String> successResult = Result.success("测试数据");
        assertThat(successResult.getOrThrow()).isEqualTo("测试数据");

        Result<String> failResult = Result.fail(403, "无权限访问");
        assertThatThrownBy(failResult::getOrThrow)
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权限访问");
    }
}
