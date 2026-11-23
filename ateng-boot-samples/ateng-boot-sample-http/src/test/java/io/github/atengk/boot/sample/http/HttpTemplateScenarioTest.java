package io.github.atengk.boot.sample.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.http.dto.UserFormRequest;
import io.github.atengk.boot.sample.http.dto.UserOptionsRequest;
import io.github.atengk.boot.sample.http.dto.UserPatchRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.TestSocketUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * HttpTemplateController 端到端回环集成场景测试
 * 验证 MockMvc 调用业务 Controller -> HttpTemplate -> 真实回环网络 -> MockServerProviderController 链路
 *
 * @author Ateng
 * @since 2026-11-09
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
public class HttpTemplateScenarioTest {

    static int port = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> port);
        registry.add("sample.user-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.order-service.url", () -> "http://localhost:" + port + "/mock/provider");
        registry.add("sample.wechat-pay.url", () -> "http://localhost:" + port + "/mock/provider/wechat");
        registry.add("sample.flaky-service.url", () -> "http://localhost:" + port);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("场景 1: 命令式 GET 查询用户详情并自动反序列化")
    void testGetUserById() throws Exception {
        mockMvc.perform(get("/sample/http/template/user/{id}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.username").value("user_1001"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("场景 2: 命令式 POST 创建用户资源")
    void testCreateUser() throws Exception {
        UserRequest request = UserRequest.builder()
                .username("NewDeveloper")
                .email("dev@example.com")
                .role("ENGINEER")
                .build();

        mockMvc.perform(post("/sample/http/template/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(2001))
                .andExpect(jsonPath("$.data.username").value("NewDeveloper"))
                .andExpect(jsonPath("$.data.role").value("ENGINEER"));
    }

    @Test
    @DisplayName("场景 3: 命令式 PUT 更新用户资源")
    void testUpdateUser() throws Exception {
        UserRequest request = UserRequest.builder()
                .username("UpdatedDeveloper")
                .role("ARCHITECT")
                .build();

        mockMvc.perform(put("/sample/http/template/user/{id}", 1001L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.username").value("UpdatedDeveloper"))
                .andExpect(jsonPath("$.data.role").value("ARCHITECT"));
    }

    @Test
    @DisplayName("场景 4: 命令式 DELETE 删除用户资源")
    void testDeleteUser() throws Exception {
        mockMvc.perform(delete("/sample/http/template/user/{id}", 1001L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.id").value(1001));
    }

    @Test
    @DisplayName("场景 5: 单次动态 RequestOptions 覆盖超时与请求头")
    void testGetUserWithOptions() throws Exception {
        UserOptionsRequest request = UserOptionsRequest.builder()
                .id(1001L)
                .readTimeoutMillis(3000L)
                .customHeader("trace-opt-12345")
                .customQueryParam("testParam")
                .build();

        mockMvc.perform(post("/sample/http/template/user/options")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1001))
                .andExpect(jsonPath("$.data.username").value("user_1001"));
    }

    @Test
    @DisplayName("场景 6: 表单 application/x-www-form-urlencoded 提交")
    void testSubmitUserForm() throws Exception {
        UserFormRequest request = UserFormRequest.builder()
                .username("FormUser01")
                .email("form@example.com")
                .role("FORM_TESTER")
                .build();

        mockMvc.perform(post("/sample/http/template/user/form")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(3001))
                .andExpect(jsonPath("$.data.username").value("FormUser01"))
                .andExpect(jsonPath("$.data.role").value("FORM_TESTER"));
    }

    @Test
    @DisplayName("场景 7: SSE 文本事件流流式消费")
    void testConsumeSseStream() throws Exception {
        mockMvc.perform(get("/sample/http/template/stream/sse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.eventCount").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.events", hasItem(containsString("data: sse-event"))));
    }

    @Test
    @DisplayName("场景 8: 大文件零缓冲下载")
    void testDownloadFile() throws Exception {
        mockMvc.perform(get("/sample/http/template/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.size").value("57"))
                .andExpect(jsonPath("$.data.filename").value("sample-file.txt"))
                .andExpect(jsonPath("$.data.previewContent").value(containsString("ateng-boot-http")));
    }

    @Test
    @DisplayName("场景 9: 命令式 PATCH 局部更新用户属性")
    void testPatchUser() throws Exception {
        UserPatchRequest request = UserPatchRequest.builder()
                .username("PatchUser09")
                .status("LOCKED")
                .build();

        mockMvc.perform(patch("/sample/http/template/user/{id}", 1009L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1009))
                .andExpect(jsonPath("$.data.username").value("PatchUser09"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.status").value("LOCKED"));
    }

    @Test
    @DisplayName("场景 10: HEAD / OPTIONS 元数据头与 Allow 允许动词探测")
    void testProbeUserMetadata() throws Exception {
        mockMvc.perform(get("/sample/http/template/metadata/{id}", 1010L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resourceId").value(1010))
                .andExpect(jsonPath("$.data.resourceVersion").value("v2"))
                .andExpect(jsonPath("$.data.endpointCapability").value("USER_CRUD_V2"))
                .andExpect(jsonPath("$.data.allowedMethods", hasItems("GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")))
                .andExpect(jsonPath("$.data.optionsHeaderCount", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("场景 11: 空安全 getOptional 查询（200 命中与 404 自动转 Optional.empty()）")
    void testGetUserOptional() throws Exception {
        mockMvc.perform(get("/sample/http/template/get-optional/{id}", 1011L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.found").value(true))
                .andExpect(jsonPath("$.data.statusSummary").value("FOUND"))
                .andExpect(jsonPath("$.data.user.id").value(1011));

        mockMvc.perform(get("/sample/http/template/get-optional/{id}", 404L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.found").value(false))
                .andExpect(jsonPath("$.data.statusSummary").value("NOT_FOUND_EMPTY"))
                .andExpect(jsonPath("$.data.user").doesNotExist());
    }

    @Test
    @DisplayName("场景 12: getEntity 提取完整 HTTP 状态码与响应头")
    void testGetUserEntity() throws Exception {
        mockMvc.perform(get("/sample/http/template/get-entity/{id}", 1012L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.httpStatus").value(200))
                .andExpect(jsonPath("$.data.resourceVersion").value("v2"))
                .andExpect(jsonPath("$.data.contentType", containsString("application/json")))
                .andExpect(jsonPath("$.data.body.id").value(1012))
                .andExpect(jsonPath("$.data.body.username").value("user_1012"));
    }

    @Test
    @DisplayName("场景 13: 零缓冲流式 Multipart 分片上传并校验无内部控制头泄漏")
    void testUploadFile() throws Exception {
        byte[] content = "ateng-boot-http zero-buffer streaming multipart payload".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo-chunk.txt",
                MediaType.TEXT_PLAIN_VALUE,
                content
        );

        mockMvc.perform(multipart("/sample/http/template/upload")
                        .file(file)
                        .param("bizType", "contract-pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.filename").value("demo-chunk.txt"))
                .andExpect(jsonPath("$.data.size").value(content.length))
                .andExpect(jsonPath("$.data.bizType").value("contract-pdf"))
                .andExpect(jsonPath("$.data.internalHeaderClean").value(true));
    }

    @Test
    @DisplayName("场景 14: 异步受管线程池调用与四维上下文全保真透传")
    void testExecuteAsyncContext() throws Exception {
        mockMvc.perform(get("/sample/http/template/async-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.propagatedAllFour").value(true))
                .andExpect(jsonPath("$.data.asyncUserId").value("USER_ASYNC_777"))
                .andExpect(jsonPath("$.data.asyncTenantId").value("TENANT_ASYNC_999"))
                .andExpect(jsonPath("$.data.asyncIgnoreTenant").value(true))
                .andExpect(jsonPath("$.data.asyncTraceId", notNullValue()))
                .andExpect(jsonPath("$.data.remoteUser.id").value(1001));
    }
}
