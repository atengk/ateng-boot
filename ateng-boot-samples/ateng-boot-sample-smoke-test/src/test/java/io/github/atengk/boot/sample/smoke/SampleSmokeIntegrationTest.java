package io.github.atengk.boot.sample.smoke;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.http.template.HttpTemplate;
import io.github.atengk.boot.openapi.properties.OpenApiProperties;
import io.github.atengk.boot.realtime.template.RealtimeTemplate;
import io.github.atengk.boot.redis.util.RedisUtil;
import io.github.atengk.boot.sample.smoke.entity.SmokeRecordEntity;
import io.github.atengk.boot.sample.smoke.mapper.SmokeRecordMapper;
import io.github.atengk.boot.sample.smoke.model.SmokeBatchItemRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeLoginRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordCreateRequest;
import io.github.atengk.boot.sample.smoke.model.SmokeRecordPageRequest;
import io.github.atengk.boot.statemachine.autoconfigure.StateMachineInitializer;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.Validator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全家桶自动装配冒烟与跨模块端到端协同集成门禁测试
 * 对应 ateng-boot-all 全套 Starter 与典型扩展 Starter 共存装配验证
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
    private Environment environment;

    @Autowired
    private SmokeRecordMapper smokeRecordMapper;

    @Autowired
    private StorageTemplate storageTemplate;

    @Autowired
    private HttpTemplate httpTemplate;

    @Autowired
    private RealtimeTemplate realtimeTemplate;

    @Autowired
    private EncryptTemplate encryptTemplate;

    @Autowired
    private OpenApiProperties openApiProperties;

    @Autowired(required = false)
    private StateMachineInitializer stateMachineInitializer;

    @Autowired
    private ObjectProvider<RedisUtil> redisUtilProvider;

    @BeforeEach
    @AfterEach
    void cleanupState() {
        UserContextHolder.clear();
        TenantContextHolder.clear();
        MDC.clear();
        smokeRecordMapper.delete(new LambdaQueryWrapper<SmokeRecordEntity>().isNotNull(SmokeRecordEntity::getId));
        StpUtil.logout("smoke-user-9999");
        if (redisUtilProvider.getIfAvailable() != null) {
            RedisUtil.delete("smoke:coordination:probe");
        }
    }

    @Test
    @DisplayName("冒烟门禁 1：验证 Spring 容器零冲突加载、全家桶与按需 Starter 共存装配")
    void testContextLoadsAndMultiStartersCoexistence() throws Exception {
        assertThat(objectMapper).isNotNull();
        assertThat(validator).isNotNull();
        assertThat(smokeRecordMapper).isNotNull();
        assertThat(storageTemplate).isNotNull();
        assertThat(httpTemplate).isNotNull();
        assertThat(realtimeTemplate).isNotNull();
        assertThat(realtimeTemplate.getOnlineUserCount()).isEqualTo(0);
        assertThat(encryptTemplate).isNotNull();
        assertThat(openApiProperties).isNotNull();
        assertThat(stateMachineInitializer).isNotNull();
        assertThat(SaManager.getStpInterface()).isNotNull();
        assertThat(SaManager.getConfig()).isNotNull();

        mockMvc.perform(get("/sample/smoke/security/coexistence"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.coreBundleReady").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-storage']").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-http']").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-realtime']").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-encrypt']").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-statemachine']").value(true))
                .andExpect(jsonPath("$.data.assembledComponents['ateng-boot-openapi']").value(true));
    }

    @Test
    @DisplayName("冒烟门禁 2：基础契约控制器（Result、TraceId、long-to-string、ValidList 校验与视图脱敏）")
    void testSmokeContractController() throws Exception {
        // 1. 验证 ping 契约、X-Trace-Id 响应头与 Long 大整数序列化防精度丢失
        mockMvc.perform(get("/sample/smoke/contract/ping"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.serviceName").value("ateng-boot-sample-smoke-test"))
                .andExpect(jsonPath("$.data.snowflakeProbeId").value("9007199254740993"));

        // 2. 正向批量校验 + @FieldSensitive JSON 动态脱敏
        List<SmokeBatchItemRequest> validItems = List.of(
                new SmokeBatchItemRequest("核心网关", "PUBLISHED", "13800138000")
        );
        mockMvc.perform(post("/sample/smoke/contract/batch-validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validItems)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("核心网关"))
                .andExpect(jsonPath("$.data[0].mobile").value("138****8000"));

        // 3. 逆向非法枚举值与空标题触发 ValidList JSR-303 Fail-Fast 拦截
        List<SmokeBatchItemRequest> invalidItems = List.of(
                new SmokeBatchItemRequest("", "INVALID_STATUS", "13800138000")
        );
        mockMvc.perform(post("/sample/smoke/contract/batch-validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidItems)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("冒烟门禁 3：持久层审计与表格导出闭环（Sa-Token 会话透传 -> MP 审计 -> PageResult -> @ExcelSensitive 导出）")
    void testPersistenceAuditAndExcelExportWorkflow() throws Exception {
        // 1. 先未登录访问受 @SaCheckLogin 保护端点，验证 SaTokenExceptionHandler 返回 401 契约
        mockMvc.perform(get("/sample/smoke/security/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.success").value(false));

        // 2. 通过 Sa-Token 登录并提取 Token
        SmokeLoginRequest loginRequest = new SmokeLoginRequest("smoke-user-9999", "ateng", "阿腾", "TENANT_SMOKE");
        MvcResult loginResult = mockMvc.perform(post("/sample/smoke/security/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resolvedContextUserId").value("smoke-user-9999"))
                .andExpect(jsonPath("$.data.resolvedContextTenantId").value("TENANT_SMOKE"))
                .andReturn();

        JsonNode loginJson = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String tokenName = loginJson.path("data").path("tokenName").asText();
        String tokenValue = loginJson.path("data").path("tokenValue").asText();
        assertThat(tokenName).isNotBlank();
        assertThat(tokenValue).isNotBlank();

        // 3. 携带 Token 访问受保护端点 /me
        mockMvc.perform(get("/sample/smoke/security/me").header(tokenName, tokenValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("smoke-user-9999"))
                .andExpect(jsonPath("$.data.tenantId").value("TENANT_SMOKE"));

        // 4. 携带 Token 新增业务记录，验证 MP 自动从 SaTokenCurrentUserProvider 填充 createBy，且内存中明文落库、响应 JSON 脱敏
        SmokeRecordCreateRequest createRequest = new SmokeRecordCreateRequest(
                "全家桶冒烟记录单",
                "PUBLISHED",
                new BigDecimal("12345.67"),
                "13912345678"
        );
        mockMvc.perform(post("/sample/smoke/record/create")
                        .header(tokenName, tokenValue)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("全家桶冒烟记录单"))
                .andExpect(jsonPath("$.data.createBy").value("smoke-user-9999"))
                .andExpect(jsonPath("$.data.mobile").value("139****5678"));

        // 验证数据库底层仍维持真明文不变量 (ADR-0012)
        List<SmokeRecordEntity> dbRecords = smokeRecordMapper.selectList(null);
        assertThat(dbRecords).hasSize(1);
        assertThat(dbRecords.get(0).getMobile()).isEqualTo("13912345678");
        assertThat(dbRecords.get(0).getCreateBy()).isEqualTo("smoke-user-9999");

        // 5. 验证标准 PageResult 分页响应契约
        SmokeRecordPageRequest pageRequest = new SmokeRecordPageRequest();
        pageRequest.setCurrent(1L);
        pageRequest.setSize(10L);
        pageRequest.setStatus("PUBLISHED");
        mockMvc.perform(post("/sample/smoke/record/page")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pageRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].mobile").value("139****5678"));

        // 6. 验证 Apache Fesod 流式导出 Excel 响应头、工作表内容与 @ExcelSensitive 脱敏单元格
        MvcResult exportMvcResult = mockMvc.perform(get("/sample/smoke/record/export")
                        .param("status", "PUBLISHED"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(exportMvcResult))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));

        byte[] excelBytes = exportMvcResult.getResponse().getContentAsByteArray();
        assertThat(excelBytes).isNotEmpty();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(2);
            Row dataRow = sheet.getRow(1);
            assertThat(dataRow.getCell(0).getStringCellValue()).isEqualTo("全家桶冒烟记录单");
            assertThat(dataRow.getCell(3).getStringCellValue()).isEqualTo("139****5678");
            assertThat(dataRow.getCell(4).getStringCellValue()).isEqualTo("smoke-user-9999");
        }
    }

    @Test
    @DisplayName("冒烟门禁 4：ateng-boot-openapi 文档放行与 Redis 双模探针校验")
    void testOpenApiDocsAndRedisDualModeProbe() throws Exception {
        // 1. 验证 OpenAPI 3.0 Schema 输出正常且被 Sa-Token 白名单放行
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ateng-boot 全家桶装配冒烟与集成示例"))
                .andExpect(jsonPath("$.info.version").value("1.2.0"));

        // 2. 验证 Redis 双模探针（CI !local 下离线优雅降级；local Profile 下直连读写）
        boolean localActive = List.of(environment.getActiveProfiles()).contains("local");
        if (localActive) {
            mockMvc.perform(get("/sample/smoke/security/redis-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.redisAvailable").value(true))
                    .andExpect(jsonPath("$.data.mode").value("LOCAL_LIVE"));
        } else {
            mockMvc.perform(get("/sample/smoke/security/redis-status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.redisAvailable").value(false))
                    .andExpect(jsonPath("$.data.mode").value("OFFLINE_DEGRADED"));
        }

        // 3. 验证显式 Result 失败构建契约
        Result<Void> failResult = Result.fail("SMOKE_ERROR");
        assertThat(failResult.isSuccess()).isFalse();
    }
}
