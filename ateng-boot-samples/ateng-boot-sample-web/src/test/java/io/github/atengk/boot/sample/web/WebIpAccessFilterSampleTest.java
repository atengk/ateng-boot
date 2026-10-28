package io.github.atengk.boot.sample.web;

import io.github.atengk.boot.web.ip.filter.IpAccessControlFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * URL 路径级 IP 访问控制过滤器场景端到端测试
 * 验证基于 Spring 属性配置的全局路径拦截、白名单放行、未授权阻断与白名单路径排除
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest(properties = {
        "ateng.boot.web.ip-access-control.enabled=true",
        "ateng.boot.web.ip-access-control.filter.enabled=true",
        "ateng.boot.web.ip-access-control.filter.mode=WHITE_LIST",
        "ateng.boot.web.ip-access-control.filter.rules[0]=192.168.1.0/24",
        "ateng.boot.web.ip-access-control.filter.url-patterns[0]=/api/sample/ip-access/filter-admin/**",
        "ateng.boot.web.ip-access-control.filter.exclude-urls[0]=/api/sample/ip-access/filter-admin/public-ping"
})
@AutoConfigureMockMvc
@DisplayName("URL 路径级 IP 访问控制过滤器场景端到端测试")
class WebIpAccessFilterSampleTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. 路径级过滤器白名单放行：符合 rules 的客户端 IP 访问受限路径")
    void shouldAllowWhitelistedIpOnFilterPath() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/filter-admin/dashboard")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.66");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("管理员仪表盘数据"));
    }

    @Test
    @DisplayName("2. 路径级过滤器拦截：非白名单客户端 IP 访问受限路径被优雅拦截")
    void shouldBlockNonWhitelistedIpOnFilterPath() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/filter-admin/dashboard")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.99");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("IP 访问受限，拒绝访问"));
    }

    @Test
    @DisplayName("3. 路径级过滤器排除白名单：exclude-urls 路径跳过 IP 校验放行")
    void shouldBypassFilterOnExcludedUrl() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/filter-admin/public-ping")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.99"); // 非白名单 IP
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("public-pong"));
    }
}
