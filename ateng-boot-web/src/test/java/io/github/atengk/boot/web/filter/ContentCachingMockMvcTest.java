package io.github.atengk.boot.web.filter;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ContentCachingFilter 与 MockMvc 端到端全真流式读取与防爆测试
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("ContentCachingFilter MockMvc 端到端全真测试")
class ContentCachingMockMvcTest {

    private MockMvc mockMvc;
    private final AtomicReference<String> controllerReadBody = new AtomicReference<>();
    private final AtomicReference<String> downstreamReadBody = new AtomicReference<>();

    @RestController
    static class MockTestController {

        private final AtomicReference<String> controllerReadBody;
        private final AtomicReference<String> downstreamReadBody;

        public MockTestController(AtomicReference<String> controllerReadBody,
                                  AtomicReference<String> downstreamReadBody) {
            this.controllerReadBody = controllerReadBody;
            this.downstreamReadBody = downstreamReadBody;
        }

        @PostMapping("/api/echo")
        public Result<String> echo(HttpServletRequest request) throws IOException {
            // 第一次在业务 Controller 读取输入流
            byte[] firstRead = request.getInputStream().readAllBytes();
            this.controllerReadBody.set(new String(firstRead, StandardCharsets.UTF_8));

            // 第二次在同一次请求中再次读取输入流（验证流重复消费能力）
            byte[] secondRead = request.getInputStream().readAllBytes();
            this.downstreamReadBody.set(new String(secondRead, StandardCharsets.UTF_8));

            return Result.success("ok");
        }

        @PostMapping(value = "/api/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
        public Result<String> formLogin(@RequestParam("username") String username,
                                       @RequestParam("role") String role) {
            return Result.success(username + ":" + role);
        }
    }

    @BeforeEach
    void setUp() {
        controllerReadBody.set(null);
        downstreamReadBody.set(null);

        // 装配 ContentCachingFilter（限制 1KB），并挂载 GlobalExceptionHandler 统一拦截超限异常
        ContentCachingFilter cachingFilter = new ContentCachingFilter(1024);
        mockMvc = MockMvcBuilders.standaloneSetup(new MockTestController(controllerReadBody, downstreamReadBody))
                .addFilter(cachingFilter)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("MockMvc 请求中支持 Controller 多次重复读取请求体输入流")
    void shouldAllowRepeatedStreamReadingInMockMvc() throws Exception {
        String payload = "{\"user\":\"ateng\",\"action\":\"login\",\"timestamp\":1760000000}";

        mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ok"));

        assertThat(controllerReadBody.get()).isEqualTo(payload);
        assertThat(downstreamReadBody.get()).isEqualTo(payload);
    }

    @Test
    @DisplayName("MockMvc 请求体超过配置上限时在 Filter 层快速失败拦截防 OOM")
    void shouldReturn400WhenBodyExceedsLimit() {
        // 构建 2048 字节超大请求体，超过 1024 字节限制
        byte[] largeBody = new byte[2048];
        java.util.Arrays.fill(largeBody, (byte) 'A');

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(largeBody)))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessageContaining("请求体大小超过允许的最大限制: 1024 字节")
                .extracting("code")
                .isEqualTo(io.github.atengk.boot.core.code.CommonResultCode.BAD_REQUEST.getCode());
    }

    @Test
    @DisplayName("MockMvc 表单请求正常解析参数且不被 ContentCachingFilter 破坏")
    void shouldHandleFormUrlEncodedRequestNormally() throws Exception {
        mockMvc.perform(post("/api/form")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("username", "ateng")
                        .param("role", "admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("ateng:admin"));
    }
}
