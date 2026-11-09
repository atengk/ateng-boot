package io.github.atengk.boot.sample.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.http.dto.UserFormRequest;
import io.github.atengk.boot.sample.http.dto.UserOptionsRequest;
import io.github.atengk.boot.sample.http.dto.UserRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
public class HttpTemplateScenarioTest {

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
}
