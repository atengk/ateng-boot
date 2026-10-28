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
 * IP 访问控制防火墙与 CIDR 子网判定场景端到端测试（方法级注解）
 * 验证白名单放行与拒绝、黑名单拦截、CIDR/IPv6 子网、自定义拒绝文案与 HTTP 403 物理切断
 *
 * @author Ateng
 * @since 2026-10-27
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("IP 访问控制防火墙方法级声明式场景端到端测试")
class WebIpAccessSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. 白名单放行：规则内 IP (127.0.0.1 或 192.168.1.0/24) 成功访问")
    void shouldAllowWhitelistedIp() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/whitelist")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.88");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("白名单放行成功"));

        mockMvc.perform(get("/api/sample/ip-access/whitelist")
                        .with(request -> {
                            request.setRemoteAddr("127.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("2. 白名单拒绝：未授权 IP 优雅拦截 (HTTP 200 + Result 403 + X-Denied-Reason 标头)")
    void shouldBlockNonWhitelistedIp() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/whitelist")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("IP 访问受限，拒绝访问"));
    }

    @Test
    @DisplayName("3. 黑名单拦截：命中黑名单规则 (10.0.0.0/8 或 203.0.113.100) 时优雅拒绝")
    void shouldBlockBlacklistedIp() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/blacklist")
                        .with(request -> {
                            request.setRemoteAddr("10.254.1.2");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/sample/ip-access/blacklist")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.100");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403));

        // 正常 IP 放行
        mockMvc.perform(get("/api/sample/ip-access/blacklist")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("黑名单未命中，放行成功"));
    }

    @Test
    @DisplayName("4. CIDR 子网与 IPv6 支持：172.16.0.0/12 与 2001:db8::/32 放行，其他拒绝")
    void shouldSupportCidrAndIpv6() throws Exception {
        // IPv4 CIDR
        mockMvc.perform(get("/api/sample/ip-access/cidr-ipv6")
                        .with(request -> {
                            request.setRemoteAddr("172.16.50.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // IPv6 CIDR
        mockMvc.perform(get("/api/sample/ip-access/cidr-ipv6")
                        .with(request -> {
                            request.setRemoteAddr("2001:db8:85a3::1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 越界 IP 拦截
        mockMvc.perform(get("/api/sample/ip-access/cidr-ipv6")
                        .with(request -> {
                            request.setRemoteAddr("192.168.1.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    @DisplayName("5. 自定义拒绝文案：拦截时输出注解声明的个性化提示信息")
    void shouldReturnCustomDeniedMessage() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/custom-message")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        }))
                .andExpect(status().isOk())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("内部核心机房特许通道，当前 IP 无权访问"));
    }

    @Test
    @DisplayName("6. 物理 403 切断模式：开启 httpStatus403 时直接响应 HTTP 403 状态码")
    void shouldReturnHttp403Status() throws Exception {
        mockMvc.perform(get("/api/sample/ip-access/http-403")
                        .with(request -> {
                            request.setRemoteAddr("10.0.0.1");
                            return request;
                        }))
                .andExpect(status().isForbidden())
                .andExpect(header().string(IpAccessControlFilter.DENIED_HEADER, IpAccessControlFilter.DENIED_REASON))
                .andExpect(jsonPath("$.code").value(403));
    }
}
