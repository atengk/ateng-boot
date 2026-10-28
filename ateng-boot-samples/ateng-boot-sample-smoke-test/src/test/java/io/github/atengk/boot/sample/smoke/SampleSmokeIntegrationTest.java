package io.github.atengk.boot.sample.smoke;

import cn.dev33.satoken.SaManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import io.github.atengk.boot.sample.smoke.mapper.SmokeRecordMapper;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全家桶自动装配冒烟与跨模块端到端协同集成测试
 * 对应 ateng-boot-all 全套 Starter 装配验证：
 * 1. 验证在单体或全家桶场景下全套 Starter 零 Bean 冲突正常启动 (contextLoads)
 * 2. 验证跨模块全链路协同：上下文透传 -> MyBatis-Plus 审计自动填充 -> Apache Fesod 流式 Excel 导出 -> Result 契约封装
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ateng-boot-all 全家桶装配冒烟与跨模块集成门禁测试")
class SampleSmokeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Validator validator;

    @Autowired
    private SmokeRecordMapper smokeRecordMapper;

    @Autowired
    private StorageTemplate storageTemplate;

    @Autowired
    private HttpTemplate httpTemplate;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Autowired(required = false)
    private io.github.atengk.boot.statemachine.autoconfigure.StateMachineInitializer stateMachineInitializer;

    @BeforeEach
    @AfterEach
    void resetContexts() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("冒烟测试 1：验证 Spring 容器零冲突加载与核心基础设施 Bean 正确装配")
    void testContextLoadsAndStartersWiring() {
        assertThat(objectMapper).isNotNull();
        assertThat(validator).isNotNull();
        assertThat(smokeRecordMapper).isNotNull();
        assertThat(storageTemplate).isNotNull();
        assertThat(httpTemplate).isNotNull();
        assertThat(realtimeTemplate).isNotNull();
        assertThat(realtimeTemplate.getOnlineUserCount()).isEqualTo(0);
        assertThat(SaManager.getStpInterface()).isNotNull();
        assertThat(SaManager.getConfig()).isNotNull();
        assertThat(stateMachineInitializer).isNotNull();
    }

    @Test
    @DisplayName("冒烟测试 2：跨模块端到端主链路自验（四维上下文 -> MP 审计自动填充 -> Excel 流式导出 -> Result 封装）")
    void testCrossModuleE2EWorkflow() {
        // 1. 设置当前操作人与租户四维上下文
        UserContextHolder.set("9999");
        TenantContextHolder.setTenantId("TENANT_SMOKE");

        try {
            assertThat(UserContextHolder.getUserId()).isEqualTo("9999");
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("TENANT_SMOKE");

            // 2. 插入业务实体，触发 MyBatis-Plus 自动审计填充（由 CurrentUserProvider SPI 供给操作人）
            SmokeRecordEntity record = new SmokeRecordEntity("全家桶冒烟记录单", new BigDecimal("12345.67"));
            int rows = smokeRecordMapper.insert(record);
            assertThat(rows).isEqualTo(1);
            assertThat(record.getId()).isNotNull();
            assertThat(record.getCreateBy()).isEqualTo("9999");
            assertThat(record.getCreateTime()).isNotNull();

            // 3. 将持久化实体通过 Apache Fesod 流式导出为 Excel 字节流
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ExcelUtil.export(out, List.of(record), SmokeRecordEntity.class);
            byte[] excelBytes = out.toByteArray();
            assertThat(excelBytes).isNotEmpty();
            assertThat(excelBytes.length).isGreaterThan(100);

            // 4. 统一响应契约显式验证
            Result<SmokeRecordEntity> result = Result.success(record);
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getData().getTitle()).isEqualTo("全家桶冒烟记录单");
        } finally {
            // 清理上下文现场
            UserContextHolder.clear();
            TenantContextHolder.clear();
            assertThat(UserContextHolder.get()).isNull();
            assertThat(TenantContextHolder.getTenantId()).isNull();
        }
    }

    @Test
    @DisplayName("冒烟测试 3：跨模块 MockMvc HTTP 请求验证（Result 统一契约、TraceId 与 OpenAPI 路径放行）")
    void testMockMvcWorkflow() throws Exception {
        mockMvc.perform(get("/api/smoke/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("PONG"));

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
    }
}
