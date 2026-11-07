package io.github.atengk.boot.sample.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 多版本路由与 Web 运行时工具集端到端集成测试
 * 验证 IpUtil 真实客户端 IP 提取、CIDR 子网位运算判定与 ServletUtil RFC 6266 文件流式下载
 *
 * @author Ateng
 * @since 2026-11-08
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Web 路由与运行时工具集端到端测试")
class WebRoutingAndUtilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("1. IpUtil 真实客户端 IP 提取：穿透多级反向代理 X-Forwarded-For 提取首个真实外网 IP")
    void shouldExtractRealClientIp() throws Exception {
        mockMvc.perform(get("/sample/web/routing/client-ip")
                        .header("X-Forwarded-For", "203.0.113.195, 192.168.1.1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("203.0.113.195"));
    }

    @Test
    @DisplayName("2. ServletUtil 安全流式下载：RFC 6266 双重文件名编码，杜绝浏览器乱码")
    void shouldDownloadFileWithRfc6266Header() throws Exception {
        MvcResult result = mockMvc.perform(get("/sample/web/routing/download"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("filename=");
        assertThat(disposition).contains("filename*=");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("ateng-boot 企业级运行时对账单文件下载内容");
    }

    @Test
    @DisplayName("3. IpUtil 原生 CIDR 判定：192.168.1.50 命中 192.168.1.0/24")
    void shouldValidateCidrInRange() throws Exception {
        mockMvc.perform(get("/sample/web/routing/ip-check")
                        .param("ip", "192.168.1.50")
                        .param("cidr", "192.168.1.0/24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.inRange").value(true));
    }
}
