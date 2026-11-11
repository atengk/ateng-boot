package io.github.atengk.boot.admin.security;

import de.codecentric.boot.admin.server.config.AdminServerProperties;
import io.github.atengk.boot.admin.properties.AtengAdminServerProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * AdminWriteGuardFilter 单元测试
 * 验证只读模式开启与关闭状态下的微服务 Actuator 代理请求拦截与放行逻辑
 *
 * @author Ateng
 * @since 2026-11-02
 */
@DisplayName("AdminWriteGuardFilter 控制台只读安全守卫单元测试")
class AdminWriteGuardFilterTest {

    private AtengAdminServerProperties properties;
    private AdminServerProperties adminServerProperties;
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        properties = new AtengAdminServerProperties();
        adminServerProperties = new AdminServerProperties();
        filterChain = mock(FilterChain.class);
    }

    @Test
    @DisplayName("默认配置开启写操作时，所有针对微服务 Actuator 的变更操作均正常放行向下游流转")
    void shouldAllowAllMutatingOperationsWhenWriteOperationsEnabled() throws ServletException, IOException {
        properties.setEnableWriteOperations(true);
        AdminWriteGuardFilter filter = new AdminWriteGuardFilter(properties, adminServerProperties);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/instances/inst-001/actuator/loggers/root");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("只读模式开启后，针对微服务 Actuator 的 POST/PUT/DELETE/PATCH 写请求均被阻断并返回 403")
    void shouldBlockMutatingActuatorOperationsWhenWriteOperationsDisabled() throws ServletException, IOException {
        properties.setEnableWriteOperations(false);
        AdminWriteGuardFilter filter = new AdminWriteGuardFilter(properties, adminServerProperties);

        // 1. POST 测试 (如在线改日志级别)
        MockHttpServletRequest postReq = new MockHttpServletRequest("POST", "/instances/inst-001/actuator/loggers/root");
        MockHttpServletResponse postResp = new MockHttpServletResponse();
        filter.doFilter(postReq, postResp, filterChain);
        assertThat(postResp.getStatus()).isEqualTo(403);
        assertThat(postResp.getContentAsString()).contains("Console write operations are disabled by policy");

        // 2. PUT 测试 (如更新环境参数)
        MockHttpServletRequest putReq = new MockHttpServletRequest("PUT", "/instances/inst-001/actuator/env");
        MockHttpServletResponse putResp = new MockHttpServletResponse();
        filter.doFilter(putReq, putResp, filterChain);
        assertThat(putResp.getStatus()).isEqualTo(403);

        // 3. DELETE 测试 (如清空缓存)
        MockHttpServletRequest delReq = new MockHttpServletRequest("DELETE", "/instances/inst-001/actuator/caches/my-cache");
        MockHttpServletResponse delResp = new MockHttpServletResponse();
        filter.doFilter(delReq, delResp, filterChain);
        assertThat(delResp.getStatus()).isEqualTo(403);

        // 4. PATCH 测试
        MockHttpServletRequest patchReq = new MockHttpServletRequest("PATCH", "/instances/inst-001/actuator/env");
        MockHttpServletResponse patchResp = new MockHttpServletResponse();
        filter.doFilter(patchReq, patchResp, filterChain);
        assertThat(patchResp.getStatus()).isEqualTo(403);

        verify(filterChain, never()).doFilter(postReq, postResp);
        verify(filterChain, never()).doFilter(putReq, putResp);
        verify(filterChain, never()).doFilter(delReq, delResp);
        verify(filterChain, never()).doFilter(patchReq, patchResp);
    }

    @Test
    @DisplayName("只读模式开启时，针对 Actuator 的 GET 读操作正常放行不受影响")
    void shouldAllowReadOperationsWhenWriteOperationsDisabled() throws ServletException, IOException {
        properties.setEnableWriteOperations(false);
        AdminWriteGuardFilter filter = new AdminWriteGuardFilter(properties, adminServerProperties);

        MockHttpServletRequest getHealthReq = new MockHttpServletRequest("GET", "/instances/inst-001/actuator/health");
        MockHttpServletResponse getHealthResp = new MockHttpServletResponse();
        filter.doFilter(getHealthReq, getHealthResp, filterChain);
        verify(filterChain).doFilter(getHealthReq, getHealthResp);

        MockHttpServletRequest getLoggersReq = new MockHttpServletRequest("GET", "/instances/inst-001/actuator/loggers");
        MockHttpServletResponse getLoggersResp = new MockHttpServletResponse();
        filter.doFilter(getLoggersReq, getLoggersResp, filterChain);
        verify(filterChain).doFilter(getLoggersReq, getLoggersResp);
    }

    @Test
    @DisplayName("只读模式开启时，微服务自身的正常生命周期操作（POST 注册与 DELETE 注销）严格放行")
    void shouldAllowInstanceLifecycleOperationsWhenWriteOperationsDisabled() throws ServletException, IOException {
        properties.setEnableWriteOperations(false);
        AdminWriteGuardFilter filter = new AdminWriteGuardFilter(properties, adminServerProperties);

        // 1. 微服务合法上报注册
        MockHttpServletRequest registerReq = new MockHttpServletRequest("POST", "/instances");
        MockHttpServletResponse registerResp = new MockHttpServletResponse();
        filter.doFilter(registerReq, registerResp, filterChain);
        verify(filterChain).doFilter(registerReq, registerResp);

        // 2. 微服务合法受控下线注销
        MockHttpServletRequest deregisterReq = new MockHttpServletRequest("DELETE", "/instances/inst-001");
        MockHttpServletResponse deregisterResp = new MockHttpServletResponse();
        filter.doFilter(deregisterReq, deregisterResp, filterChain);
        verify(filterChain).doFilter(deregisterReq, deregisterResp);
    }

    @Test
    @DisplayName("配置非空 context-path 时，只读过滤器能够正确规范化前缀并精准执行拦截与放行")
    void shouldRespectCustomContextPath() throws ServletException, IOException {
        properties.setEnableWriteOperations(false);
        adminServerProperties.setContextPath("/admin");
        AdminWriteGuardFilter filter = new AdminWriteGuardFilter(properties, adminServerProperties);

        // 1. 命中自定义 context-path 下的高危写请求 -> 403 阻断
        MockHttpServletRequest blockReq = new MockHttpServletRequest("POST", "/admin/instances/inst-001/actuator/shutdown");
        MockHttpServletResponse blockResp = new MockHttpServletResponse();
        filter.doFilter(blockReq, blockResp, filterChain);
        assertThat(blockResp.getStatus()).isEqualTo(403);
        verify(filterChain, never()).doFilter(blockReq, blockResp);

        // 2. 自定义 context-path 下的注册请求 -> 正常放行
        MockHttpServletRequest regReq = new MockHttpServletRequest("POST", "/admin/instances");
        MockHttpServletResponse regResp = new MockHttpServletResponse();
        filter.doFilter(regReq, regResp, filterChain);
        verify(filterChain).doFilter(regReq, regResp);

        // 3. shouldBlock 直接判定契约校验
        assertThat(filter.shouldBlock("post", "/admin/instances/inst-001/actuator/loggers/root")).isTrue();
        assertThat(filter.shouldBlock("GET", "/admin/instances/inst-001/actuator/health")).isFalse();
        assertThat(filter.shouldBlock("POST", "/admin/instances")).isFalse();
    }
}
