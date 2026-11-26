package io.github.atengk.boot.sample.satoken;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.spring.SaBeanInject;
import cn.dev33.satoken.spring.SaBeanRegister;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.satoken.dto.LoginRequestDTO;
import io.github.atengk.boot.satoken.properties.SaTokenProperties;
import io.github.atengk.boot.satoken.util.LoginHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 自定义 TokenProperties 与 sa-token.* 协同覆盖端到端集成测试
 * 验证存在 SaBeanRegister + SaBeanInject 时自定义 timeout/active-timeout 生效且未覆盖 sa-token.is-share
 *
 * @author Ateng
 * @since 2026-11-26
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "ateng.boot.satoken.token.timeout=12h",
        "ateng.boot.satoken.token.active-timeout=15m",
        "ateng.boot.satoken.token.is-concurrent=false",
        "sa-token.is-share=false"
})
@DisplayName("Sa-Token 自定义 TokenProperties 协同覆盖端到端集成测试")
class SampleSaTokenTokenPropertiesIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApplicationContext applicationContext;

    private String issuedDefaultToken;
    private String issuedAdminToken;

    @AfterEach
    void tearDown() {
        try {
            if (issuedDefaultToken != null && !issuedDefaultToken.isBlank()) {
                LoginHelper.logoutByTokenValue(issuedDefaultToken);
            }
            if (issuedAdminToken != null && !issuedAdminToken.isBlank()) {
                LoginHelper.logoutByTokenValue("admin", issuedAdminToken);
            }
        } finally {
            issuedDefaultToken = null;
            issuedAdminToken = null;
        }
    }

    @AfterAll
    static void restoreDefaultGlobalSaTokenConfig() {
        // 按 SaTokenProperties 默认基线恢复 SaManager 全局配置，避免影响同 JVM 后续复用的其他集成测试上下文
        SaTokenProperties.TokenProperties defaults = new SaTokenProperties().getToken();
        SaTokenConfig restored = new SaTokenConfig()
                .setTimeout(defaults.getTimeout().toSeconds())
                .setActiveTimeout(defaults.getActiveTimeout().toSeconds())
                .setIsConcurrent(defaults.isConcurrent())
                .setIsShare(defaults.isShare())
                .setMaxLoginCount(defaults.getMaxLoginCount())
                .setTokenStyle(defaults.getTokenStyle());
        SaManager.setConfig(restored);
    }

    @Test
    @DisplayName("Issue #1176: 自定义 TokenProperties(12h/15m/is-concurrent=false) 与显式 sa-token.is-share=false 协同生效")
    void shouldApplyCustomTokenPropertiesAndPreserveExplicitSaTokenShareInFullContext() throws Exception {
        assertThat(applicationContext.getBean(SaBeanRegister.class)).isNotNull();
        assertThat(applicationContext.getBean(SaBeanInject.class)).isNotNull();

        SaTokenConfig globalConfig = SaManager.getConfig();
        assertThat(globalConfig.getTimeout()).isEqualTo(43200L);
        assertThat(globalConfig.getActiveTimeout()).isEqualTo(900L);
        assertThat(globalConfig.getIsConcurrent()).isFalse();
        assertThat(globalConfig.getIsShare()).isFalse();
        assertThat(StpUtil.stpLogic.getConfigOrGlobal()).isSameAs(globalConfig);
        assertThat(LoginHelper.getStpLogic("admin").getConfigOrGlobal()).isSameAs(globalConfig);

        LoginRequestDTO defaultReq = LoginRequestDTO.builder()
                .userId("117611")
                .username("custom_policy_user")
                .tenantId("tenant-custom")
                .build();
        MvcResult defaultLogin = mockMvc.perform(post("/sample/satoken/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(defaultReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        issuedDefaultToken = objectMapper.readTree(defaultLogin.getResponse().getContentAsString()).get("data").asText();

        LoginRequestDTO adminReq = LoginRequestDTO.builder()
                .userId("117612")
                .username("custom_policy_admin")
                .tenantId("tenant-custom")
                .build();
        MvcResult adminLogin = mockMvc.perform(post("/sample/satoken/multi-account/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adminReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        issuedAdminToken = objectMapper.readTree(adminLogin.getResponse().getContentAsString()).get("data").asText();

        MvcResult defaultPolicy = mockMvc.perform(get("/sample/satoken/auth/token-policy")
                        .header("satoken", issuedDefaultToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.globalTimeout").value(43200))
                .andExpect(jsonPath("$.data.globalActiveTimeout").value(900))
                .andExpect(jsonPath("$.data.isConcurrent").value(false))
                .andExpect(jsonPath("$.data.isShare").value(false))
                .andReturn();
        JsonNode defaultData = objectMapper.readTree(defaultPolicy.getResponse().getContentAsString()).get("data");
        assertThat(defaultData.get("tokenTimeout").asLong()).isBetween(43180L, 43200L);
        assertThat(defaultData.get("tokenActiveTimeout").asLong()).isBetween(880L, 900L);

        MvcResult adminPolicy = mockMvc.perform(get("/sample/satoken/auth/token-policy")
                        .param("loginType", "admin")
                        .header("satoken-admin", issuedAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.loginType").value("admin"))
                .andExpect(jsonPath("$.data.globalTimeout").value(43200))
                .andExpect(jsonPath("$.data.globalActiveTimeout").value(900))
                .andReturn();
        JsonNode adminData = objectMapper.readTree(adminPolicy.getResponse().getContentAsString()).get("data");
        assertThat(adminData.get("tokenTimeout").asLong()).isBetween(43180L, 43200L);
        assertThat(adminData.get("tokenActiveTimeout").asLong()).isBetween(880L, 900L);
    }
}
