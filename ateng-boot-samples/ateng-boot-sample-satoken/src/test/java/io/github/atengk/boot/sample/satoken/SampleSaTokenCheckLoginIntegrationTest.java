package io.github.atengk.boot.sample.satoken;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sa-Token 全域强制登录模式 (checkLogin=true) 下的 @Anonymous 声明式白名单集成测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ateng.boot.satoken.interceptor.check-login=true"
})
@DisplayName("Sa-Token 全域强制登录模式下 @Anonymous 与动态白名单集成测试")
class SampleSaTokenCheckLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. 严格模式拦截：普通未标注 @Anonymous 接口在未携带 Token 时被拦截返回 401")
    void shouldInterceptProtectedEndpointWhenCheckLoginEnabled() throws Exception {
        mockMvc.perform(get("/api/auth/current-tenant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.msg").value("未提供访问凭据 (Token)"));
    }

    @Test
    @DisplayName("2. 严格模式放行：方法级 @Anonymous 普通接口与路径参数通配接口无需 Token 成功放行")
    void shouldAllowAnonymousMethodEndpointsWhenCheckLoginEnabled() throws Exception {
        mockMvc.perform(get("/api/auth/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("pong"));

        mockMvc.perform(get("/api/auth/public/article/777888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("公开文章详情: 777888"));
    }

    @Test
    @DisplayName("3. 严格模式放行：类级 @Anonymous 控制器所有接口无需 Token 成功放行")
    void shouldAllowAnonymousClassEndpointsWhenCheckLoginEnabled() throws Exception {
        mockMvc.perform(get("/api/anonymous/hello"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("类级匿名访问成功"));

        mockMvc.perform(get("/api/anonymous/item/SEC-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("类级匿名条目详情: SEC-001"));
    }

    @Test
    @DisplayName("4. 严格模式放行：SaTokenWhitelistProvider SPI 动态注册白名单无需 Token 成功放行")
    void shouldAllowSpiWhitelistWhenCheckLoginEnabled() throws Exception {
        mockMvc.perform(get("/api/custom-spi/data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("SPI白名单访问成功"));
    }
}
