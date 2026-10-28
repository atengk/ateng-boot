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

        @GetMapping("/test/business-exception-with-cause")
        public Result<Void> triggerBusinessExceptionWithCause() {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "网关服务通信异常", new java.io.IOException("Socket timeout"));
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

        @PostMapping("/test/upload-limit")
        public Result<Void> triggerUploadLimit() {
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(10 * 1024 * 1024L);
        }

        @GetMapping("/test/unsupported-media-type")
        public Result<Void> triggerMediaTypeNotSupported() throws Exception {
            throw new org.springframework.web.HttpMediaTypeNotSupportedException(
                    org.springframework.http.MediaType.APPLICATION_XML,
                    java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)
            );
        }

        @GetMapping("/test/media-not-acceptable")
        public Result<Void> triggerMediaNotAcceptable() throws Exception {
            throw new org.springframework.web.HttpMediaTypeNotAcceptableException(
                    java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)
            );
        }

        @GetMapping("/test/async-timeout")
        public Result<Void> triggerAsyncTimeout() {
            throw new org.springframework.web.context.request.async.AsyncRequestTimeoutException();
        }

        @PostMapping("/test/missing-part")
        public Result<Void> triggerMissingPart() throws Exception {
            throw new org.springframework.web.multipart.support.MissingServletRequestPartException("file");
        }

        @GetMapping("/test/custom-domain-exception")
        public Result<Void> triggerCustomDomainException() {
            throw new CustomDomainException("ORD_9999", "订单已被风控拦截取消");
        }
    }

    static class CustomDomainException extends RuntimeException {
        private final String orderId;

        public CustomDomainException(String orderId, String message) {
            super(message);
            this.orderId = orderId;
        }

        public String getOrderId() {
            return orderId;
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
    @DisplayName("带底层 cause 的 BusinessException 捕获并转译为业务 Result 且因果异常安全记录")
    void shouldHandleBusinessExceptionWithCause() throws Exception {
        mockMvc.perform(get("/test/business-exception-with-cause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value("网关服务通信异常"))
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

        Result<Void> result = (Result<Void>) handler.handleNoResourceFoundException(ex);
        assertEquals(404, result.getCode());
        assertEquals("请求资源不存在", result.getMsg());
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("上传文件大小超出限制时捕获 MaxUploadSizeExceededException 并转译为 413")
    void shouldHandleMaxUploadSizeExceededException() throws Exception {
        mockMvc.perform(post("/test/upload-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.msg").value("上传文件大小超出限制"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("不支持的媒体类型时捕获 HttpMediaTypeNotSupportedException 并转译为 415")
    void shouldHandleHttpMediaTypeNotSupportedException() throws Exception {
        mockMvc.perform(get("/test/unsupported-media-type"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.msg").value("不支持的媒体类型"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("不接受的媒体类型时捕获 HttpMediaTypeNotAcceptableException 并转译为 406")
    void shouldHandleHttpMediaTypeNotAcceptableException() throws Exception {
        mockMvc.perform(get("/test/media-not-acceptable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(406))
                .andExpect(jsonPath("$.msg").value("不接受的媒体类型"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("异步请求处理超时时捕获 AsyncRequestTimeoutException 并转译为 503")
    void shouldHandleAsyncRequestTimeoutException() throws Exception {
        mockMvc.perform(get("/test/async-timeout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.msg").value("异步请求处理超时"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("缺少必要文件或表单分片时捕获 MissingServletRequestPartException 并转译为 400")
    void shouldHandleMissingServletRequestPartException() throws Exception {
        mockMvc.perform(post("/test/missing-part"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("缺少必要的文件或表单分片: file"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("挂载 GlobalExceptionCustomizer SPI 前置精准转译自定义领域异常")
    void shouldSupportGlobalExceptionCustomizerSpi() throws Exception {
        GlobalExceptionCustomizer customizer = throwable -> {
            if (throwable instanceof CustomDomainException ex) {
                return Result.fail(40010, String.format("[%s] %s", ex.getOrderId(), ex.getMessage()));
            }
            return null;
        };

        GlobalExceptionHandler customHandler = new GlobalExceptionHandler(java.util.List.of(customizer));
        MockMvc customMockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(customHandler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        customMockMvc.perform(get("/test/custom-domain-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40010))
                .andExpect(jsonPath("$.msg").value("[ORD_9999] 订单已被风控拦截取消"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("GlobalExceptionCustomizer 链式有序执行：高优先级定制器拦截截断，返回 null 时平滑穿透至默认处理器")
    void shouldSupportCustomizerOrderingAndFallback() throws Exception {
        // 低优先级定制器 (order = 10)
        GlobalExceptionCustomizer lowPriorityCustomizer = new GlobalExceptionCustomizer() {
            @Override
            public Result<?> customize(Throwable throwable) {
                if (throwable instanceof CustomDomainException) {
                    return Result.fail(40099, "低优先级定制器处理");
                }
                return null;
            }

            @Override
            public int getOrder() {
                return 10;
            }
        };

        // 高优先级定制器 (order = 1)
        GlobalExceptionCustomizer highPriorityCustomizer = new GlobalExceptionCustomizer() {
            @Override
            public Result<?> customize(Throwable throwable) {
                if (throwable instanceof CustomDomainException) {
                    return Result.fail(40001, "高优先级定制器优先拦截");
                }
                return null;
            }

            @Override
            public int getOrder() {
                return 1;
            }
        };

        GlobalExceptionHandler customHandler = new GlobalExceptionHandler(java.util.List.of(lowPriorityCustomizer, highPriorityCustomizer));
        MockMvc customMockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(customHandler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        // 1. 高优先级截断
        customMockMvc.perform(get("/test/custom-domain-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40001))
                .andExpect(jsonPath("$.msg").value("高优先级定制器优先拦截"));

        // 2. 定制器返回 null 时回退至全局脱敏 (500)
        customMockMvc.perform(get("/test/system-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(CommonResultCode.INTERNAL_SERVER_ERROR.getMsg()));
    }
}
