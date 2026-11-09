package io.github.atengk.boot.sample.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.sample.core.dto.AuditAndDiagnoseRequest;
import io.github.atengk.boot.sample.core.dto.SlowAlertAndFileRequest;
import io.github.atengk.boot.sample.core.listener.SampleAuditEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Core 系统基线防卫、网络探测、慢方法告警与审计诊断端到端 HTTP 集成测试
 * 覆盖 CoreSystemAndAuditController 全部 3 个对外接口契约、CIDR 匹配、@SlowAlert 耗时拦截、临时文件安全治理与审计事件流转
 *
 * @author Ateng
 * @since 2026-11-07
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Core 系统基线与运维诊断端到端集成测试")
class CoreSystemAndAuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SampleAuditEventListener auditEventListener;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        auditEventListener.reset();
    }

    @AfterEach
    void tearDown() {
        auditEventListener.reset();
        Path tempDir = FileUtil.getAppTempDirectory("sample-core");
        FileUtil.cleanDirectorySafely(tempDir);
    }

    @Test
    @DisplayName("测试系统环境基线防卫与网络探测 (时区、编码、Headless、超时、物理IPv4、CIDR与IPv6清洗)")
    void testGetBaselineAndNet_Success() throws Exception {
        mockMvc.perform(get("/sample/core/system/baseline-and-net")
                        .param("clientIp", "192.168.1.188:8443")
                        .param("cidrPattern", "192.168.1.0/24"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.timezone", is("Asia/Shanghai")))
                .andExpect(jsonPath("$.data.headless", is(true)))
                .andExpect(jsonPath("$.data.connectTimeout", is("10000")))
                .andExpect(jsonPath("$.data.readTimeout", is("10000")))
                .andExpect(jsonPath("$.data.physicalIpv4", notNullValue()))
                .andExpect(jsonPath("$.data.usablePort", notNullValue()))
                .andExpect(jsonPath("$.data.rawClientIp", is("192.168.1.188:8443")))
                .andExpect(jsonPath("$.data.cleanedClientIp", is("192.168.1.188")))
                .andExpect(jsonPath("$.data.cidrMatched", is(true)))
                .andExpect(jsonPath("$.data.ipv6Cleaned", is("fe80::1")))
                .andExpect(jsonPath("$.data.ipv6CidrMatched", is(true)));
    }

    @Test
    @DisplayName("测试慢方法告警、安全文件名清洗与受管临时目录治理 (验证零磁盘残留)")
    void testExecuteSlowAlertAndFile_Success() throws Exception {
        SlowAlertAndFileRequest request = SlowAlertAndFileRequest.builder()
                .taskId("bench-task-999")
                .secretToken("secretToken-ABC-123456")
                .filename("../../malicious/path/export-audit.txt")
                .content("Ateng-Boot 2026 Core Baseline Audit File Content")
                .maxReadBytes(512)
                .build();

        mockMvc.perform(post("/sample/core/system/slow-alert-and-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.taskId", is("bench-task-999")))
                .andExpect(jsonPath("$.data.slowMethodCostMillis", notNullValue()))
                .andExpect(jsonPath("$.data.originalFilename", is("../../malicious/path/export-audit.txt")))
                .andExpect(jsonPath("$.data.cleanedFilename", is("export-audit.txt")))
                .andExpect(jsonPath("$.data.readContent", is("Ateng-Boot 2026 Core Baseline Audit File Content")))
                .andExpect(jsonPath("$.data.readByteCount", is(48)))
                .andExpect(jsonPath("$.data.cleanedFileCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.fileExistedBeforeClean", is(true)))
                .andExpect(jsonPath("$.data.fileDestroyedAfterClean", is(true)))
                .andExpect(jsonPath("$.data.safeResolvedPath", notNullValue()))
                .andExpect(jsonPath("$.data.junkFileDetected", is(false)))
                .andExpect(jsonPath("$.data.junkFilteredCount", is(3)))
                .andExpect(jsonPath("$.data.stopWatchReport", containsString("1.慢调用方法执行与脱敏告警")))
                .andExpect(jsonPath("$.data.stopWatchReport", containsString("4.受管临时目录确定性清理")));

        // 强断言：物理磁盘临时文件已彻底销毁，零残留
        Path appTempDir = FileUtil.getAppTempDirectory("sample-core");
        Path cleanedFilePath = appTempDir.resolve("export-audit.txt");
        assertThat(Files.exists(cleanedFilePath)).isFalse();
    }

    @Test
    @DisplayName("测试宿主系统垃圾与隐性文件治理 (.DS_Store精准拦截与过滤)")
    void testExecuteSlowAlertAndFile_WithJunkFile() throws Exception {
        SlowAlertAndFileRequest request = SlowAlertAndFileRequest.builder()
                .taskId("junk-check-001")
                .secretToken("secret-xyz")
                .filename(".DS_Store")
                .content("Mock DS Store Content")
                .maxReadBytes(128)
                .build();

        mockMvc.perform(post("/sample/core/system/slow-alert-and-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.junkFileDetected", is(true)))
                .andExpect(jsonPath("$.data.junkFilteredCount", is(3)));
    }

    @Test
    @DisplayName("测试慢方法与文件治理入参 JSR-303 格式校验快速失败")
    void testExecuteSlowAlertAndFile_ValidationFailure() throws Exception {
        SlowAlertAndFileRequest invalidRequest = SlowAlertAndFileRequest.builder()
                .taskId("")
                .secretToken("")
                .filename("")
                .content("")
                .maxReadBytes(0)
                .build();

        mockMvc.perform(post("/sample/core/system/slow-alert-and-file")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(400)))
                .andExpect(jsonPath("$.success", is(false)));
    }

    @Test
    @DisplayName("测试结构化操作与 DML SQL 审计、事件消费与异常深度因果追溯")
    void testExecuteAuditAndDiagnose_DmlSqlAndException() throws Exception {
        AuditAndDiagnoseRequest request = AuditAndDiagnoseRequest.builder()
                .module("系统基线管理")
                .operateType(OperateTypeEnum.INSERT)
                .description("新增系统参数配置")
                .sql("INSERT INTO sys_config (config_key, config_value) VALUES ('sys.timeout', '30s')")
                .errorMessage("底层数据库连接池耗尽拒绝连接")
                .build();

        mockMvc.perform(post("/sample/core/system/audit-and-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.traceId", notNullValue()))
                .andExpect(jsonPath("$.data.operateLog.module", is("系统基线管理")))
                .andExpect(jsonPath("$.data.operateLog.operateType", is("INSERT")))
                .andExpect(jsonPath("$.data.eventConsumedCount", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.consumedEventModel.module", is("系统基线管理")))
                .andExpect(jsonPath("$.data.sqlLog.sqlType", is("INSERT")))
                .andExpect(jsonPath("$.data.dml", is(true)))
                .andExpect(jsonPath("$.data.query", is(false)))
                .andExpect(jsonPath("$.data.rootCauseClass", is("java.lang.IllegalStateException")))
                .andExpect(jsonPath("$.data.rootCauseMessage", is("底层数据库连接池耗尽拒绝连接")))
                .andExpect(jsonPath("$.data.truncatedStackTrace", containsString("底层数据库连接池耗尽拒绝连接")))
                .andExpect(jsonPath("$.data.stackTruncated", is(true)));

        // 强断言：验证事件监听器确实消费到了相同的审计事件
        assertThat(auditEventListener.getEventCount()).isGreaterThanOrEqualTo(1);
        assertThat(auditEventListener.getLastReceivedModel()).isNotNull();
        assertThat(auditEventListener.getLastReceivedModel().getModule()).isEqualTo("系统基线管理");
    }

    @Test
    @DisplayName("测试只读 SELECT SQL 审计识别与状态判定")
    void testExecuteAuditAndDiagnose_SelectSql() throws Exception {
        AuditAndDiagnoseRequest request = AuditAndDiagnoseRequest.builder()
                .module("用户审计中心")
                .operateType(OperateTypeEnum.QUERY)
                .description("查询系统用户详情")
                .sql("SELECT * FROM t_user WHERE id = 1001 AND status = 1")
                .errorMessage("模拟只读查询超时")
                .build();

        mockMvc.perform(post("/sample/core/system/audit-and-diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is(200)))
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.sqlLog.sqlType", is("SELECT")))
                .andExpect(jsonPath("$.data.dml", is(false)))
                .andExpect(jsonPath("$.data.query", is(true)));
    }
}
