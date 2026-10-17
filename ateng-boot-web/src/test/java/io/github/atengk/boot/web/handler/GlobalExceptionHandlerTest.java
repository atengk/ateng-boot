package io.github.atengk.boot.web.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-15
 */
@DisplayName("全局异常处理器测试")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {

        @GetMapping("/test/business-exception")
        public Result<Void> triggerBusinessException() {
            throw new BusinessException(403, "账户已被封禁");
        }

        @PostMapping("/test/validation")
        public Result<Void> triggerValidation(@Valid @RequestBody TestParam param) {
            return Result.success();
        }

        @GetMapping("/test/system-error")
        public Result<Void> triggerSystemError() {
            throw new NullPointerException("内部敏感空指针引用");
        }

        @GetMapping("/test/type-mismatch")
        public Result<Void> triggerTypeMismatch(@org.springframework.web.bind.annotation.RequestParam("id") Long id) {
            return Result.success();
        }
    }

    static class TestParam {
        @NotBlank(message = "用户名不能为空")
        private String username;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }
    }

    @BeforeEach
    void setUp() {
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(exceptionHandler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @Test
    @DisplayName("业务异常 BusinessException 正常转译为业务 Result 且 HTTP 状态码为 200")
    void shouldHandleBusinessException() throws Exception {
        mockMvc.perform(get("/test/business-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("账户已被封禁"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("参数校验异常提取第一条友好提示且 HTTP 状态码为 200")
    void shouldHandleValidationException() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("用户名不能为空"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("JSON 格式错误时捕获 HttpMessageNotReadableException 并友好提示")
    void shouldHandleHttpMessageNotReadableException() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("invalid json content {"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求参数反序列化失败，请检查 JSON 格式"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("未知根异常 Throwable/Exception 输出生产脱敏提示且不暴露堆栈")
    void shouldHandleUnknownExceptionWithMasking() throws Exception {
        mockMvc.perform(get("/test/system-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(CommonResultCode.INTERNAL_SERVER_ERROR.getMsg()))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("请求不支持的 HTTP 方法时转译为 405 提示且 HTTP 状态码为 200")
    void shouldHandleMethodNotSupportedException() throws Exception {
        mockMvc.perform(post("/test/business-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(405))
                .andExpect(jsonPath("$.msg").value("请求方法不支持: POST"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("参数类型不匹配时捕获 MethodArgumentTypeMismatchException 并返回 400")
    void shouldHandleMethodArgumentTypeMismatchException() throws Exception {
        mockMvc.perform(get("/test/type-mismatch").param("id", "not-a-number"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("请求参数非法或缺失"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("静态资源或路由不存在时捕获 NoResourceFoundException 并返回 404 Result")
    void shouldHandleNoResourceFoundException() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        org.springframework.web.servlet.resource.NoResourceFoundException ex =
                new org.springframework.web.servlet.resource.NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/static/missing.js");

        Result<Void> result = handler.handleNoResourceFoundException(ex);
        assertEquals(404, result.getCode());
        assertEquals("请求资源不存在", result.getMsg());
        assertFalse(result.isSuccess());
    }
}
