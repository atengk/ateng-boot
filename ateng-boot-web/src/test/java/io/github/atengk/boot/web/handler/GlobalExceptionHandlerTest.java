package io.github.atengk.boot.web.handler;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;

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
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("全局异常处理器测试")
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class TestController {

        @GetMapping("/test/business-exception")
        public Result<Void> triggerBusinessException() {
            throw new BusinessException(403, "账户已被封禁");
        }

        @GetMapping("/test/business-exception-remote-trace")
        public Result<Void> triggerRemoteTraceBusinessException() {
            throw new BusinessException(502, "下游订单服务异常").withTraceId("remote-trace-777");
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

        @PostMapping("/test/multipart-invalid")
        public Result<Void> triggerMultipartInvalid() {
            throw new MultipartException("Current request is not a multipart request");
        }

        @PostMapping("/test/multipart-client-abort")
        public Result<Void> triggerMultipartClientAbort() {
            throw new MultipartException(
                    "Failed to parse multipart servlet request",
                    new ClientAbortException(new IOException("Broken pipe"))
            );
        }

        @PostMapping("/test/multipart-file-count-limit")
        public Result<Void> triggerMultipartFileCountLimit() {
            throw new MultipartException(
                    "Failed to parse multipart servlet request",
                    new FileCountLimitExceededException("attachment")
            );
        }

        @PostMapping("/test/multipart-server-error")
        public Result<Void> triggerMultipartServerError() {
            throw new MultipartException(
                    "Failed to parse multipart servlet request",
                    new AccessDeniedException("/tmp/tomcat-upload-dir")
            );
        }

        @GetMapping("/test/custom-domain-exception")
        public Result<Void> triggerCustomDomainException() {
            throw new CustomDomainException("ORD_9999", "订单已被风控拦截取消");
        }
    }

    static class ClientAbortException extends IOException {
        public ClientAbortException(Throwable cause) {
            super(cause);
        }
    }

    static class FileCountLimitExceededException extends IOException {
        public FileCountLimitExceededException(String message) {
            super(message);
        }
    }

    static class MalformedStreamException extends IOException {
        public MalformedStreamException(String message) {
            super(message);
        }
    }

    static class InvalidContentTypeException extends IOException {
        public InvalidContentTypeException(String message) {
            super(message);
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
        MDC.clear();
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(exceptionHandler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("业务异常 BusinessException 正常转译为业务 Result 且 HTTP 状态码为 200")
    void shouldHandleBusinessException() throws Exception {
        mockMvc.perform(get("/test/business-exception"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("账户已被封禁"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.traceId").doesNotExist());
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

    @Test
    @DisplayName("本地 MDC traceId 为空时由 BusinessException.traceId 兜底回填 Result.traceId")
    void shouldFallbackResultTraceIdFromBusinessExceptionWhenMdcEmpty() throws Exception {
        MDC.clear();
        mockMvc.perform(get("/test/business-exception-remote-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.msg").value("下游订单服务异常"))
                .andExpect(jsonPath("$.traceId").value("remote-trace-777"));
    }

    @Test
    @DisplayName("本地 MDC traceId 非空时优先保留本地 MDC，不被 BusinessException.traceId 覆盖")
    void shouldPreferLocalMdcTraceIdOverBusinessExceptionTraceId() throws Exception {
        MDC.put("traceId", "local-trace-888");
        mockMvc.perform(get("/test/business-exception-remote-trace"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.msg").value("下游订单服务异常"))
                .andExpect(jsonPath("$.traceId").value("local-trace-888"));
    }

    @Test
    @DisplayName("带 cause 的远程 BusinessException 在 MDC 为空时同样回填 Result.traceId 与错误日志且支持 Customizer 回填")
    void shouldFallbackTraceIdForCausalAndCustomizedBusinessException(CapturedOutput output) {
        MDC.clear();
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        BusinessException causalEx = new BusinessException(
                CommonResultCode.INTERNAL_SERVER_ERROR,
                "远程驱动调用失败",
                new java.io.IOException("EOF")
        ).withTraceId("remote-causal-trace-999");

        Result<?> result = handler.handleBusinessException(causalEx);
        assertEquals("remote-causal-trace-999", result.getTraceId());
        assertTrue(output.getOut().contains("[TraceId: remote-causal-trace-999]"));

        GlobalExceptionHandler customizedHandler = new GlobalExceptionHandler(java.util.List.of(
                t -> t instanceof BusinessException be ? Result.fail(40088, "定制:" + be.getMsg()) : null
        ));
        Result<?> customizedResult = customizedHandler.handleBusinessException(causalEx);
        assertEquals(40088, customizedResult.getCode());
        assertEquals("remote-causal-trace-999", customizedResult.getTraceId());
    }

    @Test
    @DisplayName("普通 MultipartException 精细拦截并返回 400 且不落入 500 错误告警")
    void shouldHandleMultipartExceptionAsBadRequest(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/multipart-invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文件上传请求格式不合法或内容解析失败"))
                .andExpect(jsonPath("$.success").value(false));

        assertTrue(output.getOut().contains("文件上传请求解析失败"));
        assertFalse(output.getOut().contains("系统发生未处理异常"));
    }

    @Test
    @DisplayName("MaxUploadSizeExceededException 继续保持最高匹配优先级返回 413 不退化")
    void shouldPreserveMaxUploadSizeExceededPriorityOverMultipartException() throws Exception {
        GlobalExceptionCustomizer multipartOnlyCustomizer = throwable -> {
            if (throwable instanceof MultipartException && !(throwable instanceof MaxUploadSizeExceededException)) {
                return Result.fail(40089, "定制 Multipart 拦截");
            }
            return null;
        };

        GlobalExceptionHandler customHandler = new GlobalExceptionHandler(java.util.List.of(multipartOnlyCustomizer));
        MockMvc customMockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(customHandler)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        // 子类 MaxUploadSizeExceededException 命中专属 413 处理器，不被父类通用 MultipartException 覆盖
        customMockMvc.perform(post("/test/upload-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.msg").value("上传文件大小超出限制"))
                .andExpect(jsonPath("$.success").value(false));

        // 普通 MultipartException 优先命中 GlobalExceptionCustomizer 定制转译
        customMockMvc.perform(post("/test/multipart-invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40089))
                .andExpect(jsonPath("$.msg").value("定制 Multipart 拦截"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("客户端上传中途断连识别为传输中断 400 并降噪输出精简 WARN")
    void shouldHandleMultipartClientDisconnectWithoutErrorStorm(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/test/multipart-client-abort"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("文件上传传输中断，请检查网络后重试"))
                .andExpect(jsonPath("$.success").value(false));

        assertTrue(output.getOut().contains("文件上传连接中断"));
        assertFalse(output.getOut().contains("系统发生未处理异常"));

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<?> eofResult = handler.handleMultipartException(
                new MultipartException("Failed to parse multipart servlet request", new EOFException("Unexpected EOF"))
        );
        assertEquals(400, eofResult.getCode());
        assertEquals("文件上传传输中断，请检查网络后重试", eofResult.getMsg());
    }

    @Test
    @DisplayName("文件或表单分片数量超限识别并返回 400 明确提示")
    void shouldHandleMultipartFileCountLimitExceeded() throws Exception {
        mockMvc.perform(post("/test/multipart-file-count-limit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("上传文件或表单分片数量超出限制"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("服务端容器配置缺失或磁盘存储故障保留 500 脱敏与 ERROR 日志")
    void shouldPreserveServerErrorForServerMultipartFailures(CapturedOutput output) throws Exception {
        MDC.put("traceId", "multipart-srv-trace-001");
        mockMvc.perform(post("/test/multipart-server-error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(CommonResultCode.INTERNAL_SERVER_ERROR.getMsg()))
                .andExpect(jsonPath("$.success").value(false));
        assertTrue(output.getOut().contains("[TraceId: multipart-srv-trace-001] 文件上传服务端异常"));

        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Result<?> noConfigResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new IllegalStateException("Unable to process parts as no multi-part configuration has been provided")
        ));
        assertEquals(500, noConfigResult.getCode());

        Result<?> invalidTempDirResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new IOException("The temporary upload location [/data/tmp] is not valid")
        ));
        assertEquals(500, invalidTempDirResult.getCode());

        Result<?> diskFullResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new IOException("No space left on device")
        ));
        assertEquals(500, diskFullResult.getCode());
    }

    @Test
    @DisplayName("对抗性边界：MalformedStreamException 不误判为断连，畸形头关键词注入不误判为 500，循环 cause 不死循环")
    void shouldResistMalformedStreamMisclassificationAndCyclicCauses(CapturedOutput output) {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        // 1. MalformedStreamException("Stream ended unexpectedly") 保持畸形格式 400
        Result<?> malformedResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new MalformedStreamException("Stream ended unexpectedly")
        ));
        assertEquals(400, malformedResult.getCode());
        assertEquals("文件上传请求格式不合法或内容解析失败", malformedResult.getMsg());

        // 2. 畸形 Content-Type 中故意携带服务端或分片数量关键词时仍归为普通畸形格式 400
        Result<?> injectedHeaderResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new InvalidContentTypeException("invalid header: no space left on device / no multipart config / too many parts\r\ninjected")
        ));
        assertEquals(400, injectedHeaderResult.getCode());
        assertEquals("文件上传请求格式不合法或内容解析失败", injectedHeaderResult.getMsg());

        // 2.1 超长控制字符异常摘要在日志中被清洗截断且不泄露至 Result.msg
        String longNoisyCause = "bad\tcontrol\u0007chars " + "x".repeat(400);
        Result<?> longCauseResult = handler.handleMultipartException(new MultipartException(
                "Failed to parse multipart servlet request",
                new InvalidContentTypeException(longNoisyCause)
        ));
        assertEquals(400, longCauseResult.getCode());
        assertEquals("文件上传请求格式不合法或内容解析失败", longCauseResult.getMsg());
        assertTrue(output.getOut().contains("... [TRUNCATED]"));
        assertFalse(output.getOut().contains("\u0007"));

        // 3. 2 节点循环 cause 链安全终止且仍准确识别断连或格式错误
        IOException cycleA = new IOException("Connection reset");
        IOException cycleB = new IOException("Wrapper", cycleA);
        cycleA.initCause(cycleB);
        Result<?> cyclicAbortResult = handler.handleMultipartException(
                new MultipartException("Failed to parse multipart servlet request", cycleA)
        );
        assertEquals(400, cyclicAbortResult.getCode());
        assertEquals("文件上传传输中断，请检查网络后重试", cyclicAbortResult.getMsg());

        RuntimeException plainA = new RuntimeException("bad-a");
        RuntimeException plainB = new RuntimeException("bad-b", plainA);
        plainA.initCause(plainB);
        Result<?> cyclicPlainResult = handler.handleMultipartException(
                new MultipartException("Failed to parse multipart servlet request", plainA)
        );
        assertEquals(400, cyclicPlainResult.getCode());
        assertEquals("文件上传请求格式不合法或内容解析失败", cyclicPlainResult.getMsg());
    }
}
