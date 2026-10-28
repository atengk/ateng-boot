package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局异常体系精细化转译与扩展 SPI 端到端集成测试
 * 验证 GlobalExceptionCustomizer 自定义扩展、413、415、406、503、400 缺少分片在真实 Spring MVC 管道中的优雅转译
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 全局异常体系精细化转译与扩展 SPI 端到端测试")
class WebExceptionSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. SPI 自定义异常转译：第三方支付异常经由 PaymentExceptionCustomizer 转译为领域错误码 40020")
    void shouldHandlePaymentExceptionViaCustomizerSpi() throws Exception {
        mockMvc.perform(get("/api/sample/exception/payment"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(40020))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("支付通道异常 [ALIPAY]: 通道账户可用余额不足")))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("2. 415 媒体类型不支持：发送 text/plain 请求 Content-Type 转译为 HTTP 200 业务码 415")
    void shouldTranslateHttpMediaTypeNotSupportedException() throws Exception {
        mockMvc.perform(post("/api/sample/exception/media-type")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello world"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(415))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("不支持的媒体类型")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("3. 406 媒体类型不接受：客户端仅接受 application/xml 时转译为 HTTP 200 业务码 406")
    void shouldTranslateHttpMediaTypeNotAcceptableException() throws Exception {
        mockMvc.perform(post("/api/sample/exception/media-type")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_XML)
                        .content("{\"key\":\"value\"}"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(406))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("不接受的媒体类型"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("4. 413 大文件超限：MaxUploadSizeExceededException 统一优雅转译为业务码 413")
    void shouldTranslateMaxUploadSizeExceededException() throws Exception {
        mockMvc.perform(get("/api/sample/exception/payload-too-large"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(413))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("上传文件大小超出限制"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("5. 503 异步超时：AsyncRequestTimeoutException 转译为业务码 503")
    void shouldTranslateAsyncRequestTimeoutException() throws Exception {
        mockMvc.perform(get("/api/sample/exception/async-timeout"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("异步请求处理超时"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("6. 400 缺少分片：缺少必要文件分片时转译为业务码 400 并提示缺少的分片名")
    void shouldTranslateMissingServletRequestPartException() throws Exception {
        // multipart 请求携带 other 分片，但缺少必须的 file 分片
        MockMultipartFile otherPart = new MockMultipartFile("other", "other.txt", "text/plain", "content".getBytes());
        mockMvc.perform(multipart("/api/sample/exception/upload").file(otherPart))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value(containsString("缺少必要的文件或表单分片: file")))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    @DisplayName("7. 正常文件上传：携带合法 file 分片正常通过并返回文件名")
    void shouldUploadFileSuccessfully() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());
        mockMvc.perform(multipart("/api/sample/exception/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("test.txt"));
    }
}
