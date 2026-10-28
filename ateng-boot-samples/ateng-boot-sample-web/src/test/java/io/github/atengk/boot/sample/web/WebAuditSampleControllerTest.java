package io.github.atengk.boot.sample.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.sample.web.audit.MemoryOperateLogStorageProvider;
import io.github.atengk.boot.sample.web.dto.AuditUserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结构化操作审计与接口耗时切面端到端集成测试
 * 验证 @OperateLog、MemoryOperateLogStorageProvider SPI 与 @WebLog
 *
 * @author Ateng
 * @since 2026-10-19
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("结构化操作审计与接口耗时切面端到端测试")
class WebAuditSampleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemoryOperateLogStorageProvider storageProvider;

    @BeforeEach
    @AfterEach
    void cleanContext() {
        storageProvider.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("1. @OperateLog 正常新增：完整对齐四维上下文，敏感入参 password 被自动排除为 [EXCLUDED]，出参手机号动态脱敏")
    void shouldCaptureOperateLogOnSuccess() throws Exception {
        UserContextHolder.set("admin-operator");
        TenantContextHolder.setTenantId("tenant-888");

        AuditUserDTO dto = new AuditUserDTO();
        dto.setUsername("audit-user");
        dto.setPassword("SuperSecretPass123");
        dto.setEmail("audit@example.com");
        dto.setPhone("13812345678");
        String json = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/api/sample/audit/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("X-Trace-Id", "trace-audit-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("138****5678")); // 出库动态脱敏

        // 验证存储 SPI 接收到的结构化审计模型
        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getTraceId()).isEqualTo("trace-audit-001");
        assertThat(lastLog.getUserName()).isEqualTo("admin-operator");
        assertThat(lastLog.getTenantId()).isEqualTo("tenant-888");
        assertThat(lastLog.getModule()).isEqualTo("用户管理");
        assertThat(lastLog.getOperateType()).isEqualTo(OperateTypeEnum.INSERT);
        assertThat(lastLog.getStatus()).isEqualTo(0); // 成功
        assertThat(lastLog.getClientIp()).isNotBlank();
        assertThat(lastLog.getCostTime()).isGreaterThanOrEqualTo(0L);

        // 验证入参中敏感字段 password 被排除替换为 [EXCLUDED]
        assertThat(lastLog.getRequestParams()).contains("\"username\":\"audit-user\"");
        assertThat(lastLog.getRequestParams()).contains("\"password\":\"[EXCLUDED]\"");
        assertThat(lastLog.getRequestParams()).doesNotContain("SuperSecretPass123");
    }

    @Test
    @DisplayName("2. @OperateLog 查询操作审计：记录 QUERY 类型审计记录")
    void shouldCaptureOperateLogOnQuery() throws Exception {
        mockMvc.perform(get("/api/sample/audit/user/10086"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.phone").value("139****5678"));

        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getModule()).isEqualTo("用户中心");
        assertThat(lastLog.getOperateType()).isEqualTo(OperateTypeEnum.QUERY);
        assertThat(lastLog.getDescription()).isEqualTo("查询用户详情");
        assertThat(lastLog.getStatus()).isEqualTo(0);
    }

    @Test
    @DisplayName("3. @OperateLog 业务异常审计：切面捕获失败状态 (status=1) 并记录错误信息，异常透明重抛")
    void shouldCaptureOperateLogOnException() throws Exception {
        mockMvc.perform(get("/api/sample/audit/error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("模拟业务操作失败"));

        OperateLogModel lastLog = awaitLastLog();
        assertThat(lastLog).isNotNull();
        assertThat(lastLog.getStatus()).isEqualTo(1); // 失败
        assertThat(lastLog.getErrorMsg()).isEqualTo("模拟业务操作失败");
    }

    @Test
    @DisplayName("4. @WebLog 接口日志切面正常拦截执行")
    void shouldAuditWebLogSuccessfully() throws Exception {
        AuditUserDTO dto = new AuditUserDTO();
        dto.setUsername("weblog-tester");
        dto.setPassword("Secret123");
        String json = objectMapper.writeValueAsString(dto);

        mockMvc.perform(post("/api/sample/audit/weblog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("weblog-ok:weblog-tester"));
    }

    private OperateLogModel awaitLastLog() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            OperateLogModel model = storageProvider.getLastLog();
            if (model != null) {
                return model;
            }
            Thread.sleep(20);
        }
        return storageProvider.getLastLog();
    }
}
