package io.github.atengk.boot.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.satoken.model.LoginUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 安全鉴权与单机内存会话端到端集成测试
 * 验证无状态/单机登录、纯净 JSON 封包、401 细粒度异常映射、多租户主键前缀剥离、二级认证与注销下线
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Sa-Token 安全鉴权与单机会话端到端集成测试")
class SampleSaTokenIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("1. 未登录拦截防御：未提供凭据访问受保护接口，统一返回 HTTP 200 + 业务码 401 与细粒度提示")
    void shouldInterceptWhenNotLogin() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未提供访问凭据 (Token)"));
    }

    @Test
    @DisplayName("2. 完整登录、上下文读取、多租户主键剥离与注销全生命周期端到端验证")
    void shouldCompleteLoginLifecycleAndContextQuery() throws Exception {
        // 1. 构建登录请求数据
        LoginUser loginReq = LoginUser.builder()
                .userId("10086")
                .tenantId("tenant_sz")
                .userAccount("admin")
                .userName("系统管理员")
                .roles(Set.of("super_admin", "auditor"))
                .permissions(Set.of("user:query", "user:export"))
                .build()
                .setExtra("deptCode", "TECH_DEV");

        // 2. 执行登录，获取 Token
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = loginJson.get("data").asText();
        assertThat(token).isNotBlank();

        // 3. 携带 Token 查询当前登录主体详情
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("10086"))
                .andExpect(jsonPath("$.data.tenantId").value("tenant_sz"))
                .andExpect(jsonPath("$.data.userAccount").value("admin"))
                .andExpect(jsonPath("$.data.userName").value("系统管理员"))
                .andExpect(jsonPath("$.data.roles").isArray())
                .andExpect(jsonPath("$.data.permissions").isArray())
                .andExpect(jsonPath("$.data.extraData.deptCode").value("TECH_DEV"));

        // 4. 验证多租户复合 LoginId 前缀透明剥离
        mockMvc.perform(get("/api/auth/user-id")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("10086"));

        // 5. 校验登录状态
        mockMvc.perform(get("/api/auth/check-login")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 6. 执行会话注销
        mockMvc.perform(post("/api/auth/logout")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 7. 注销后再次携带原 Token 访问，验证已被无效拦截
        mockMvc.perform(get("/api/auth/me")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("访问凭据 (Token) 无效"));
    }

    @Test
    @DisplayName("3. 二级安全认证全生命周期：未开启 403 拦截 -> 开启通过 -> 手动关闭恢复拦截")
    void shouldHandleSafeActionLifecycle() throws Exception {
        // 1. 登录
        LoginUser loginReq = LoginUser.builder()
                .userId("20001")
                .tenantId("tenant_bj")
                .userAccount("finance_mgr")
                .userName("财务经理")
                .build();

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 未开启二级认证，访问敏感操作返回 403
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));

        // 3. 开启二级安全认证
        mockMvc.perform(post("/api/auth/open-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 4. 开启后再次执行敏感操作，成功通过
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("支付操作通过"));

        // 5. 主动关闭二级安全认证
        mockMvc.perform(post("/api/auth/close-safe")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 6. 关闭后再次访问，重新被 403 拦截
        mockMvc.perform(get("/api/auth/safe-action")
                        .header("satoken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权访问：当前操作需要二级安全认证"));
    }
}
