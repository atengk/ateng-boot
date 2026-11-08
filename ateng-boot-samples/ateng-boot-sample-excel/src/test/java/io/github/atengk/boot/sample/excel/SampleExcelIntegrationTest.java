package io.github.atengk.boot.sample.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.async.ExportTask;
import io.github.atengk.boot.excel.async.ExportTaskStatus;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.model.ExcelMultiSheetResult;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelSecurityUtil;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.enums.EmployeeStatusEnum;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.service.AsyncExportScenarioService;
import io.github.atengk.boot.sample.excel.service.DynamicColumnProjectionScenarioService;
import io.github.atengk.boot.sample.excel.service.MultiSheetScenarioService;
import io.github.atengk.boot.sample.excel.service.SecurityWorkbookScenarioService;
import io.github.atengk.boot.sample.excel.service.StreamingPageExportScenarioService;
import io.github.atengk.boot.sample.excel.service.ValidationAndFeedbackScenarioService;
import io.github.atengk.boot.sample.excel.vo.DepartmentAttendanceMergeVO;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.EmployeeRosterVO;
import io.github.atengk.boot.sample.excel.vo.I18nUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.OnboardingImportVO;
import io.github.atengk.boot.sample.excel.vo.SecurityUserExcelVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel Starter 全栈端到端自动化验收集成测试
 * <p>
 * 覆盖 6 大专职业务场景组件与 1 个最小 Web 契约控制器（≤ 3 端点），实现 100% 离线自洽全真断言。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ateng-boot-excel 端到端自动化集成门禁测试")
class SampleExcelIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StreamingPageExportScenarioService streamingPageExportService;

    @Autowired
    private MultiSheetScenarioService multiSheetService;

    @Autowired
    private ValidationAndFeedbackScenarioService validationService;

    @Autowired
    private AsyncExportScenarioService asyncExportService;

    @Autowired
    private SecurityWorkbookScenarioService securityWorkbookService;

    @Autowired
    private DynamicColumnProjectionScenarioService dynamicProjectionService;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initTemplates() throws Exception {
        Path templateDir = Path.of("src/main/resources/templates");
        Files.createDirectories(templateDir);
        Path templateFile = templateDir.resolve("expense_reimbursement_template.xlsx");
        byte[] bytes = io.github.atengk.boot.sample.excel.controller.WorkbookReportController.generateReimbursementTemplateBytes();
        if (!Files.exists(templateFile)) {
            Files.write(templateFile, bytes);
        }
        Path targetDir = Path.of("target/classes/templates");
        if (Files.exists(Path.of("target/classes"))) {
            Files.createDirectories(targetDir);
            Files.write(targetDir.resolve("expense_reimbursement_template.xlsx"), bytes);
        }
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws Exception {
        Path target = tempDir.resolve(fileName);
        Files.write(target, bytes);
    }

    // ==========================================
    // 领域控制器 1：EmployeeRosterController 测试 (/sample/excel/roster/*)
    // ==========================================

    @Test
    @DisplayName("花名册端点 1：ResponseEntity<StreamingResponseBody> 异步流式写出与动态列裁剪")
    void testRoster_ExportResponseEntity_WithProjection() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/roster/export-response-entity")
                        .param("includeColumns", "id", "name"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("员工花名册_异步流式.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5); // 1 表头 + 4 条数据

            Row headRow = sheet.getRow(0);
            assertThat(headRow.getPhysicalNumberOfCells()).isEqualTo(2);
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("员工工号");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("姓名");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("张三");
        }

        saveTempExcel("roster-response-entity.xlsx", content);
    }

    @Test
    @DisplayName("花名册端点 2：HttpServletResponse 同步流式写出与脱敏/枚举转义")
    void testRoster_ExportServletResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/roster/export-servlet-response"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("员工花名册_同步流式.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5); // 1 表头 + 4 条数据

            Row row1 = sheet.getRow(1);
            // 姓名
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("张三");
            // 性别枚举
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("男");
            // 手机号脱敏
            assertThat(row1.getCell(3).getStringCellValue()).isEqualTo("138****8000");
            // 邮箱脱敏
            assertThat(row1.getCell(4).getStringCellValue()).contains("@example.com").contains("*");
            // 在职状态枚举
            assertThat(row1.getCell(6).getStringCellValue()).isEqualTo("正式");
        }

        saveTempExcel("roster-servlet-response.xlsx", content);
    }

    @Test
    @DisplayName("花名册端点 3：应用企业预设主题渲染导出")
    void testRoster_ExportTheme() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/roster/export-theme")
                        .param("theme", "WARM_ORANGE"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5);
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertThat(headStyle.getFillPattern()).isEqualTo(FillPatternType.SOLID_FOREGROUND);
        }

        saveTempExcel("roster-theme-warm-orange.xlsx", content);
    }

    @Test
    @DisplayName("花名册端点 4：多语言国际化表头动态转译导出")
    void testRoster_ExportI18n() throws Exception {
        // 中文测试
        MvcResult zhResult = mockMvc.perform(get("/sample/excel/roster/export-i18n")
                        .header("Accept-Language", "zh-CN"))
                .andExpect(status().isOk())
                .andReturn();

        try (Workbook wbZh = WorkbookFactory.create(new ByteArrayInputStream(zhResult.getResponse().getContentAsByteArray()))) {
            Row headRow = wbZh.getSheetAt(0).getRow(0);
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("用户标识");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("用户名称");
        }

        // 英文测试
        MvcResult enResult = mockMvc.perform(get("/sample/excel/roster/export-i18n")
                        .header("Accept-Language", "en-US"))
                .andExpect(status().isOk())
                .andReturn();

        try (Workbook wbEn = WorkbookFactory.create(new ByteArrayInputStream(enResult.getResponse().getContentAsByteArray()))) {
            Row headRow = wbEn.getSheetAt(0).getRow(0);
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("User ID");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("User Name");
        }
    }

    @Test
    @DisplayName("花名册端点 5：部门考勤纵向单元格合并导出")
    void testRoster_ExportMerge() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/roster/export-merge"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(6); // 1 表头 + 5 条数据
            assertThat(sheet.getNumMergedRegions()).isGreaterThan(0);
        }

        saveTempExcel("roster-merge-attendance.xlsx", content);
    }

    // ==========================================
    // 领域控制器 2：OnboardingImportController 测试 (/sample/excel/onboarding/*)
    // ==========================================

    @Test
    @DisplayName("入职防呆端点 1：下载防呆导入模板（含下拉选单与批注）")
    void testOnboarding_DownloadTemplate() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/onboarding/template"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(1); // 仅有表头行
            List<? extends DataValidation> validations = sheet.getDataValidations();
            assertThat(validations).isNotEmpty();

            // 验证表头悬浮批注
            Comment comment = sheet.getRow(0).getCell(0).getCellComment();
            assertThat(comment).isNotNull();
            assertThat(comment.getString().getString()).contains("2-20 位员工真实姓名");
        }

        saveTempExcel("onboarding-template.xlsx", content);
    }

    @Test
    @DisplayName("入职防呆端点 2 & 3：下载合规模拟样本与含错测试样本")
    void testOnboarding_DownloadSamples() throws Exception {
        MvcResult validResult = mockMvc.perform(get("/sample/excel/onboarding/sample-valid"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] validBytes = validResult.getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(validBytes))) {
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 数据
        }

        MvcResult dirtyResult = mockMvc.perform(get("/sample/excel/onboarding/sample-dirty"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] dirtyBytes = dirtyResult.getResponse().getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(dirtyBytes))) {
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 数据
        }
    }

    @Test
    @DisplayName("入职防呆端点 4：前置表头契约校验（严格匹配与异常拦截）")
    void testOnboarding_ValidateHeader() throws Exception {
        // 1. 合规模板文件进行表头校验
        MvcResult templateResult = mockMvc.perform(get("/sample/excel/onboarding/template"))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile validFile = new MockMultipartFile(
                "file", "template.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                templateResult.getResponse().getContentAsByteArray()
        );

        MvcResult successResult = mockMvc.perform(multipart("/sample/excel/onboarding/validate-header")
                        .file(validFile)
                        .param("strategy", "STRICT"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode successNode = objectMapper.readTree(successResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(successNode.get("code").asInt()).isEqualTo(200);
        assertThat(successNode.get("data").asText()).contains("表头契约校验通过");

        // 2. 构造错误表头文件进行校验
        ByteArrayOutputStream wrongHeaderOut = new ByteArrayOutputStream();
        ExcelUtil.export(wrongHeaderOut, List.of(new I18nUserExcelVO(1L, "a", "b", "c")), I18nUserExcelVO.class);
        MockMultipartFile wrongFile = new MockMultipartFile(
                "file", "wrong_header.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                wrongHeaderOut.toByteArray()
        );

        MvcResult wrongResult = mockMvc.perform(multipart("/sample/excel/onboarding/validate-header")
                        .file(wrongFile)
                        .param("strategy", "STRICT"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode wrongNode = objectMapper.readTree(wrongResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(wrongNode.get("code").asInt()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("入职防呆端点 5：读取与 JSR-303 行级校验复合结果（含错误聚合）")
    void testOnboarding_ImportResult() throws Exception {
        // 下载脏数据测试样本
        MvcResult dirtyResult = mockMvc.perform(get("/sample/excel/onboarding/sample-dirty"))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile dirtyFile = new MockMultipartFile(
                "file", "dirty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                dirtyResult.getResponse().getContentAsByteArray()
        );

        MvcResult importResult = mockMvc.perform(multipart("/sample/excel/onboarding/import-result")
                        .file(dirtyFile))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(importResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(jsonNode.get("code").asInt()).isEqualTo(200);
        JsonNode dataNode = jsonNode.get("data");
        assertThat(dataNode.get("data").size()).isEqualTo(1); // 1 条合规
        assertThat(dataNode.get("errors").size()).isGreaterThanOrEqualTo(2); // 至少 2 行存在错误
        assertThat(dataNode.get("success").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("入职防呆端点 6：差错数据独立剥离导出")
    void testOnboarding_ExportFailedRows() throws Exception {
        MvcResult dirtyResult = mockMvc.perform(get("/sample/excel/onboarding/sample-dirty"))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile dirtyFile = new MockMultipartFile(
                "file", "dirty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                dirtyResult.getResponse().getContentAsByteArray()
        );

        MvcResult exportResult = mockMvc.perform(multipart("/sample/excel/onboarding/export-failed-rows")
                        .file(dirtyFile))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = exportResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isGreaterThanOrEqualTo(2); // 表头 + 失败行
        }

        saveTempExcel("onboarding-failed-rows.xlsx", content);
    }

    @Test
    @DisplayName("入职防呆端点 7：原样标红与悬浮批注错误反馈 Excel")
    void testOnboarding_ExportFeedback() throws Exception {
        MvcResult dirtyResult = mockMvc.perform(get("/sample/excel/onboarding/sample-dirty"))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile dirtyFile = new MockMultipartFile(
                "file", "dirty.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                dirtyResult.getResponse().getContentAsByteArray()
        );

        MvcResult feedbackResult = mockMvc.perform(multipart("/sample/excel/onboarding/export-feedback")
                        .file(dirtyFile))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = feedbackResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            // 验证存在标红单元格与批注
            boolean hasComment = false;
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row != null) {
                    for (int c = 0; c < row.getLastCellNum(); c++) {
                        Cell cell = row.getCell(c);
                        if (cell != null && cell.getCellComment() != null) {
                            hasComment = true;
                            break;
                        }
                    }
                }
            }
            assertThat(hasComment).isTrue();
        }

        saveTempExcel("onboarding-feedback.xlsx", content);
    }

    @Test
    @DisplayName("入职防呆端点 8：分批流式校验与消费")
    void testOnboarding_ImportBatch() throws Exception {
        MvcResult validResult = mockMvc.perform(get("/sample/excel/onboarding/sample-valid"))
                .andExpect(status().isOk())
                .andReturn();
        MockMultipartFile validFile = new MockMultipartFile(
                "file", "valid.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                validResult.getResponse().getContentAsByteArray()
        );

        MvcResult batchResult = mockMvc.perform(multipart("/sample/excel/onboarding/import-batch")
                        .file(validFile)
                        .param("batchSize", "2"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(batchResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.get("code").asInt()).isEqualTo(200);
        assertThat(node.get("data").get("totalSuccess").asInt()).isEqualTo(3);
        assertThat(node.get("data").get("totalErrors").asInt()).isEqualTo(0);
    }

    // ==========================================
    // 领域控制器 3：WorkbookReportController 测试 (/sample/excel/report/*)
    // ==========================================

    @Test
    @DisplayName("报表端点 1 & 2：异构多 Sheet 联合导出与反向解析闭环")
    void testReport_MultiSheet_ExportAndParse() throws Exception {
        // 1. 导出多 Sheet 报表
        MvcResult exportResult = mockMvc.perform(get("/sample/excel/report/multi-sheet"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = exportResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("员工花名册");
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("部门考勤");
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("财务动态流水");
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(3); // 1 表头 + 2 数据
            assertThat(wb.getSheetAt(1).getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 数据
        }

        saveTempExcel("report-multi-sheet.xlsx", content);

        // 2. 上传该多 Sheet 文件执行联合解析
        MockMultipartFile file = new MockMultipartFile(
                "file", "multi-sheet.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                content
        );

        MvcResult parseResult = mockMvc.perform(multipart("/sample/excel/report/multi-sheet-parse")
                        .file(file))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(parseResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.get("code").asInt()).isEqualTo(200);
        assertThat(node.get("data").get("rosterCount").asInt()).isEqualTo(2);
        assertThat(node.get("data").get("attendanceCount").asInt()).isEqualTo(3);
        assertThat(node.get("data").get("totalRowCount").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("报表端点 3：无实体动态表头与 Map 键值映射动态导出")
    void testReport_ExportDynamic() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/report/dynamic"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheet("指标统计");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 行数据
            Row headRow = sheet.getRow(0);
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("指标代号");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("统计维度");
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getStringCellValue()).isEqualTo("MTR-001");
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("华东大区");
        }

        saveTempExcel("report-dynamic.xlsx", content);
    }

    @Test
    @DisplayName("报表端点 4：商品图文画册导出（@ExcelImage 75pt 行高与图片内嵌）")
    void testReport_ExportImageCatalog() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/report/image-catalog"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 条商品
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("旗舰智能手机 Pro");
            // 验证自适应撑起的行高约为 75pt
            assertThat(row1.getHeightInPoints()).isGreaterThanOrEqualTo(70.0f);
            assertThat(sheet.getDrawingPatriarch()).isNotNull();
        }

        saveTempExcel("report-image-catalog.xlsx", content);
    }

    @Test
    @DisplayName("报表端点 5 & 6：费用报销单空白模板下载与数据复合填充导出")
    void testReport_Template_BlankAndFill() throws Exception {
        // 1. 空白模板下载
        MvcResult blankResult = mockMvc.perform(get("/sample/excel/report/template-blank"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] blankBytes = blankResult.getResponse().getContentAsByteArray();
        assertThat(blankBytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(blankBytes))) {
            Sheet sheet = wb.getSheet("费用报销单");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).contains("费用报销审批单");
        }

        saveTempExcel("report-blank-template.xlsx", blankBytes);

        // 2. 复合数据填充导出
        MvcResult fillResult = mockMvc.perform(get("/sample/excel/report/fill"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] filledBytes = fillResult.getResponse().getContentAsByteArray();
        assertThat(filledBytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(filledBytes))) {
            Sheet sheet = wb.getSheet("费用报销单");
            assertThat(sheet).isNotNull();
            // 验证单对象占位符替换
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("EXP-202611-0089");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).contains("张三");
            // 验证列表行填充
            assertThat(sheet.getRow(4).getCell(0).getStringCellValue()).isEqualTo("差旅交通费");
            assertThat(sheet.getRow(5).getCell(0).getStringCellValue()).isEqualTo("差旅住宿费");
            assertThat(sheet.getRow(6).getCell(0).getStringCellValue()).isEqualTo("餐饮招待费");
        }

        saveTempExcel("report-filled-reimbursement.xlsx", filledBytes);
    }

    // ==========================================
    // 领域控制器 4：PayrollSecurityController 测试 (/sample/excel/security/*)
    // ==========================================

    @Test
    @DisplayName("薪酬安全端点 1：脱敏与公式防注入对照演示导出")
    void testSecurity_ExportContrast() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/security/export-contrast"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            Row row1 = sheet.getRow(1);

            // 验证 @ExcelSensitive 脱敏
            assertThat(row1.getCell(1).getStringCellValue()).contains("*"); // 姓名脱敏
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("138****8000"); // 手机脱敏
            assertThat(row1.getCell(3).getStringCellValue()).contains("********"); // 身份证脱敏

            // 验证 @ExcelFormulaAllowed 白名单豁免（不被转义）
            assertThat(row1.getCell(5).getStringCellValue()).isEqualTo("=SUM(E2*1.2)");

            // 验证未豁免公式注入字符前置单引号转义
            assertThat(row1.getCell(6).getStringCellValue()).startsWith("'=");
        }

        saveTempExcel("security-contrast.xlsx", content);
    }

    @Test
    @DisplayName("薪酬安全端点 2：防伪暗水印与工作表只读保护导出")
    void testSecurity_ExportWatermark() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/security/export-watermark"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getProtect()).isTrue();
        }

        saveTempExcel("security-watermark.xlsx", content);
    }

    @Test
    @DisplayName("薪酬安全端点 3 & 5：整工作簿 AES-256 强加密导出与带密码解密上传闭环")
    void testSecurity_ExportEncrypt_And_ImportDecrypt() throws Exception {
        String testPassword = "AgilePass@2026";

        // 1. 导出 AES-256 密文工作簿
        MvcResult encryptResult = mockMvc.perform(get("/sample/excel/security/export-encrypt")
                        .param("password", testPassword))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] cipherBytes = encryptResult.getResponse().getContentAsByteArray();
        assertThat(cipherBytes).isNotEmpty();

        // 验证文件已被 OLE2 AES-256 强加密
        assertThat(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(cipherBytes))).isTrue();

        saveTempExcel("security-encrypted.xlsx", cipherBytes);

        // 2. 正确密码解密上传：成功还原 3 条数据
        MockMultipartFile validFile = new MockMultipartFile(
                "file", "encrypted.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                cipherBytes
        );

        MvcResult decryptSuccess = mockMvc.perform(multipart("/sample/excel/security/import-decrypt")
                        .file(validFile)
                        .param("password", testPassword))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode successNode = objectMapper.readTree(decryptSuccess.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(successNode.get("code").asInt()).isEqualTo(200);
        assertThat(successNode.get("data").get("data").size()).isEqualTo(3);

        // 3. 错误密码解密上传：快速失败阻断
        MvcResult decryptWrong = mockMvc.perform(multipart("/sample/excel/security/import-decrypt")
                        .file(validFile)
                        .param("password", "WrongPassword!"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode wrongNode = objectMapper.readTree(decryptWrong.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(wrongNode.get("code").asInt()).isNotEqualTo(200);

        // 4. 未传密码解密上传：快速失败阻断
        MvcResult decryptNoPwd = mockMvc.perform(multipart("/sample/excel/security/import-decrypt")
                        .file(validFile))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode noPwdNode = objectMapper.readTree(decryptNoPwd.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(noPwdNode.get("code").asInt()).isNotEqualTo(200);
    }

    @Test
    @DisplayName("薪酬安全端点 4：下载加密测试样本工作簿")
    void testSecurity_DownloadEncryptedSample() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/security/sample-encrypted"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();
        assertThat(ExcelSecurityUtil.isEncryptedWorkbook(new ByteArrayInputStream(content))).isTrue();
    }

    // ==========================================
    // 领域控制器 5：OrderStreamingController 测试 (/sample/excel/streaming/*)
    // ==========================================

    @Test
    @DisplayName("海量流式端点 1：基于 PageResult 的多 Sheet 自动阈值分片轮转导出")
    void testStreaming_ExportPage_WithSheetRotation() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/streaming/export-page")
                        .param("totalRows", "250")
                        .param("pageSize", "100")
                        .param("maxRowsPerSheet", "100"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("订单流水");
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(101); // 1 表头 + 100 数据
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("订单流水_2");
            assertThat(wb.getSheetAt(1).getPhysicalNumberOfRows()).isEqualTo(101);
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("订单流水_3");
            assertThat(wb.getSheetAt(2).getPhysicalNumberOfRows()).isEqualTo(51); // 1 表头 + 50 数据
        }

        saveTempExcel("streaming-page-rotation.xlsx", content);
    }

    @Test
    @DisplayName("海量流式端点 2：基于主键游标的零深分页跨表流式导出")
    void testStreaming_ExportCursor() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/streaming/export-cursor")
                        .param("totalRows", "180")
                        .param("pageSize", "50")
                        .param("maxRowsPerSheet", "100"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("交易账单");
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(101);
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("交易账单_2");
            assertThat(wb.getSheetAt(1).getPhysicalNumberOfRows()).isEqualTo(81);
        }

        saveTempExcel("streaming-cursor-export.xlsx", content);
    }

    @Test
    @DisplayName("海量流式端点 3：流式分页模板显式写控制导出")
    void testStreaming_ExportTemplate() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/sample/excel/streaming/export-template"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(151);
        }

        saveTempExcel("streaming-template-export.xlsx", content);
    }

    @Test
    @DisplayName("海量流式端点 4 & 5：下载模拟订单账单并执行 readBatch 流式消费")
    void testStreaming_DownloadSample_And_ImportBatch() throws Exception {
        // 1. 下载 80 条模拟订单样本
        MvcResult sampleResult = mockMvc.perform(get("/sample/excel/streaming/sample-order-bill")
                        .param("count", "80"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] sampleBytes = sampleResult.getResponse().getContentAsByteArray();
        assertThat(sampleBytes).isNotEmpty();

        // 2. 上传该样本文件，以批次 25 条进行分批消费解析
        MockMultipartFile file = new MockMultipartFile(
                "file", "orders.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                sampleBytes
        );

        MvcResult importResult = mockMvc.perform(multipart("/sample/excel/streaming/import-batch")
                        .file(file)
                        .param("batchSize", "25"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode node = objectMapper.readTree(importResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.get("code").asInt()).isEqualTo(200);
        assertThat(node.get("data").get("totalRead").asInt()).isEqualTo(80);
    }

    // ==========================================
    // 领域控制器 6：ExcelAsyncExportController 测试 (/sample/excel/async/*)
    // ==========================================

    @Test
    @DisplayName("异步导出端点 1 & 2 & 4：异步提交、X-Trace-Id 跨线程透传、状态轮询与受管文件下载")
    void testAsync_Submit_And_CheckTraceId_And_Download() throws Exception {
        String testTraceId = "trace-excel-async-7777";

        // 1. 发起异步导出任务提交并显式携带 X-Trace-Id 请求头
        MvcResult submitResult = mockMvc.perform(post("/sample/excel/async/submit")
                        .header("X-Trace-Id", testTraceId)
                        .param("taskName", "海量交易账单自动化自验")
                        .param("count", "120"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(submitNode.get("code").asInt()).isEqualTo(200);
        String taskId = submitNode.get("data").get("taskId").asText();
        assertThat(taskId).isNotBlank();

        // 2. 轮询任务状态直至 SUCCESS（至多等待 5 秒）
        JsonNode statusNode = null;
        int maxAttempts = 50;
        for (int i = 0; i < maxAttempts; i++) {
            MvcResult statusResult = mockMvc.perform(get("/sample/excel/async/status")
                            .param("taskId", taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            statusNode = objectMapper.readTree(statusResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
            String taskStatus = statusNode.get("data").get("status").asText();
            if ("SUCCESS".equals(taskStatus)) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(statusNode).isNotNull();
        assertThat(statusNode.get("code").asInt()).isEqualTo(200);
        JsonNode data = statusNode.get("data");
        assertThat(data.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(data.get("progress").asInt()).isEqualTo(100);
        assertThat(data.get("downloadUrl").asText()).isEqualTo("/sample/excel/async/download?taskId=" + taskId);
        assertThat(data.get("fileSize").asLong()).isGreaterThan(0L);

        // 验证 X-Trace-Id 在后台工作线程中的 100% 完整透传
        assertThat(data.get("workerTraceId").asText()).isEqualTo(testTraceId);

        // 3. 通过受管下载端点拉取二进制 Excel 并核验
        MvcResult downloadResult = mockMvc.perform(get("/sample/excel/async/download")
                        .param("taskId", taskId))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] downloadBytes = downloadResult.getResponse().getContentAsByteArray();
        assertThat(downloadBytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(downloadBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getSheetName()).isEqualTo("交易账单");
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(121); // 1 表头 + 120 行数据
        }

        saveTempExcel("async-export-result.xlsx", downloadBytes);
    }

    @Test
    @DisplayName("异步导出端点 3：任务取消生命周期验证")
    void testAsync_Cancel_Task() throws Exception {
        // 1. 提交一个任务
        MvcResult submitResult = mockMvc.perform(post("/sample/excel/async/submit")
                        .param("taskName", "待取消测试任务")
                        .param("count", "500"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode submitNode = objectMapper.readTree(submitResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String taskId = submitNode.get("data").get("taskId").asText();

        // 2. 取消该任务
        MvcResult cancelResult = mockMvc.perform(post("/sample/excel/async/cancel")
                        .param("taskId", taskId))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode cancelNode = objectMapper.readTree(cancelResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(cancelNode.get("code").asInt()).isEqualTo(200);
        assertThat(cancelNode.get("data").get("taskId").asText()).isEqualTo(taskId);
    }

    @Test
    @DisplayName("SPI 扩展验证：自定义 ExportFileStorage 条件化装配")
    void testAsync_CustomStorage_SPI_Verification() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(
                        io.github.atengk.boot.sample.excel.storage.CustomOssExportFileStorage.class,
                        io.github.atengk.boot.excel.properties.ExcelProperties.class
                )
                .withPropertyValues("ateng.boot.excel.sample.custom-storage.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(io.github.atengk.boot.excel.async.storage.ExportFileStorage.class);
                    io.github.atengk.boot.excel.async.storage.ExportFileStorage storage =
                            context.getBean(io.github.atengk.boot.excel.async.storage.ExportFileStorage.class);
                    assertThat(storage).isInstanceOf(io.github.atengk.boot.sample.excel.storage.CustomOssExportFileStorage.class);
                    assertThat(storage.getDownloadUrl("task_999", "key_999"))
                            .startsWith(io.github.atengk.boot.sample.excel.storage.CustomOssExportFileStorage.OSS_URL_PREFIX);
                });
    }

    // ==========================================
    // 场景 1：StreamingPageExportScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 1：海量数据游标分页流式写出与跨 Sheet 自动分割轮转")
    void testScenario1_StreamingPageExport_ByCursor() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 导出 250 条数据，限制单表 100 条 -> 应自动分割为 3 个 Sheet（100, 100, 50）
        long exported = streamingPageExportService.exportBigDataByCursor(out, 250, 100);
        assertThat(exported).isEqualTo(250);

        byte[] bytes = out.toByteArray();
        saveTempExcel("scenario1-cursor-export.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("销售流水");
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(101); // 1 表头 + 100 数据
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("销售流水_2");
            assertThat(wb.getSheetAt(1).getPhysicalNumberOfRows()).isEqualTo(101);
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("销售流水_3");
            assertThat(wb.getSheetAt(2).getPhysicalNumberOfRows()).isEqualTo(51); // 1 表头 + 50 数据
        }
    }

    @Test
    @DisplayName("场景 1：海量数据页码驱动流式分页写出")
    void testScenario1_StreamingPageExport_ByPage() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long exported = streamingPageExportService.exportBigDataByPage(out, 150, 200);
        assertThat(exported).isEqualTo(150);

        byte[] bytes = out.toByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(1);
            assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(151);
        }
    }

    // ==========================================
    // 场景 2：MultiSheetScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 2：异构多 Sheet 流式 Fluent 编排导出与异构流式模型读取全真闭环")
    void testScenario2_MultiSheet_FluentBuilderAndReader() throws Exception {
        // 1. 导出包含 3 个异构 Sheet 的复合工作簿
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        multiSheetService.exportHeterogeneousMultiSheet(out);
        byte[] bytes = out.toByteArray();
        assertThat(bytes).isNotEmpty();
        saveTempExcel("scenario2-multi-sheet.xlsx", bytes);

        // 2. 校验 Sheet 结构与内容
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheetAt(0).getSheetName()).isEqualTo("用户清单");
            assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("部门架构");
            assertThat(wb.getSheetAt(2).getSheetName()).isEqualTo("交易流水");
        }

        // 3. 使用 ExcelReaderBuilder 逆向流式读取解析为强类型对象
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            ExcelMultiSheetResult result = multiSheetService.readHeterogeneousMultiSheet(in);
            assertThat(result).isNotNull();

            List<UserExcelVO> users = result.getSheetData(0);
            assertThat(users).hasSize(3);
            assertThat(users.get(0).getUsername()).isEqualTo("张三");

            List<DepartmentUserMergeVO> departments = result.getSheetData(1);
            assertThat(departments).hasSize(5);
            assertThat(departments.get(0).getDepartment()).isEqualTo("研发部");
        }
    }

    // ==========================================
    // 场景 3：ValidationAndFeedbackScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 3：严格表头契约校验合法放行与非法阻断")
    void testScenario3_Validation_HeaderStrictCheck() throws Exception {
        // 1. 合法表头
        byte[] validExcel;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("用户ID");
            head.createCell(1).setCellValue("用户姓名");
            head.createCell(2).setCellValue("联系手机");
            head.createCell(3).setCellValue("电子邮箱");
            head.createCell(4).setCellValue("用户性别");
            head.createCell(5).setCellValue("所属部门");
            head.createCell(6).setCellValue("账号状态");
            wb.write(out);
            validExcel = out.toByteArray();
        }

        try (InputStream in = new ByteArrayInputStream(validExcel)) {
            validationService.validateHeader(in, HeaderValidationStrategy.STRICT);
        }

        // 2. 非法表头（缺少必须列）
        byte[] invalidExcel;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("用户ID");
            head.createCell(1).setCellValue("错误列");
            wb.write(out);
            invalidExcel = out.toByteArray();
        }

        try (InputStream in = new ByteArrayInputStream(invalidExcel)) {
            assertThatThrownBy(() -> validationService.validateHeader(in, HeaderValidationStrategy.STRICT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("表头");
        }
    }

    @Test
    @DisplayName("场景 3：JSR-303 行级校验与错误熔断阈值机制")
    void testScenario3_Validation_ReadAndCircuitBreaker() throws Exception {
        List<UserExcelVO> mixedList = List.of(
                new UserExcelVO(1L, "合规用户", "13800000000", "ok@example.com", SampleGenderEnum.MALE, "研发部", "0"),
                new UserExcelVO(2L, "", "110", "bad-email", SampleGenderEnum.FEMALE, "研发部", "0"),
                new UserExcelVO(3L, "", "120", "bad-email", SampleGenderEnum.MALE, "市场部", "0")
        );
        byte[] excelBytes = createRawExcel(mixedList);

        // 1. 全量校验聚合
        try (InputStream in = new ByteArrayInputStream(excelBytes)) {
            ExcelReadResult<UserExcelVO> result = validationService.readAndValidate(in);
            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getErrorCount()).isEqualTo(6);
        }

        // 2. 错误熔断（限制错误行数上限为 1，第 2 行错误直接触发熔断）
        try (InputStream in = new ByteArrayInputStream(excelBytes)) {
            ExcelReadResult<UserExcelVO> result = validationService.readWithCircuitBreaker(in, 1);
            assertThat(result.isCircuitBroken()).isTrue();
            assertThat(result.getErrorCount()).isLessThan(6);
        }
    }

    @Test
    @DisplayName("场景 3：错题集生成：单元格标红与浮动批注回写")
    void testScenario3_Validation_GenerateErrorFeedback() throws Exception {
        List<UserExcelVO> invalidList = List.of(
                new UserExcelVO(4001L, "", "13800138000", "good@example.com",
                        SampleGenderEnum.FEMALE, "研发部", "0")
        );
        byte[] rawExcelBytes = createRawExcel(invalidList);

        ExcelReadResult<UserExcelVO> readResult;
        try (InputStream in = new ByteArrayInputStream(rawExcelBytes)) {
            readResult = validationService.readAndValidate(in);
        }

        ByteArrayOutputStream feedbackOut = new ByteArrayOutputStream();
        try (InputStream in = new ByteArrayInputStream(rawExcelBytes)) {
            validationService.generateErrorFeedback(in, feedbackOut, readResult.getErrors());
        }

        byte[] feedbackBytes = feedbackOut.toByteArray();
        saveTempExcel("scenario3-feedback.xlsx", feedbackBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(feedbackBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row row1 = sheet.getRow(1);
            Cell nameCell = row1.getCell(1);

            // 标红与批注断言
            assertThat(nameCell.getCellStyle().getFillForegroundColor()).isGreaterThan((short) 0);
            Comment comment = nameCell.getCellComment();
            assertThat(comment).isNotNull();
            assertThat(comment.getString().getString()).contains("用户姓名不能为空");
        }
    }

    @Test
    @DisplayName("场景 3：差错数据隔离提取导出：仅将校验失败的数据生成专属纠错 Excel 文件")
    void testScenario3_Validation_ExportFailedRows() throws Exception {
        List<UserExcelVO> mixedList = List.of(
                new UserExcelVO(1L, "合规用户", "13800000000", "ok@example.com", SampleGenderEnum.MALE, "研发部", "0"),
                new UserExcelVO(2L, "", "110", "bad-email", SampleGenderEnum.FEMALE, "研发部", "0"),
                new UserExcelVO(3L, "", "120", "bad-email", SampleGenderEnum.MALE, "市场部", "0")
        );
        byte[] rawExcelBytes = createRawExcel(mixedList);

        ExcelReadResult<UserExcelVO> readResult;
        try (InputStream in = new ByteArrayInputStream(rawExcelBytes)) {
            readResult = validationService.readAndValidate(in);
        }
        assertThat(readResult.getFailedData()).hasSize(2);

        ByteArrayOutputStream failedOut = new ByteArrayOutputStream();
        validationService.exportFailedRows(failedOut, readResult.getFailedData(), readResult.getErrors());
        byte[] failedBytes = failedOut.toByteArray();
        assertThat(failedBytes).isNotEmpty();
        saveTempExcel("scenario3-failed-rows.xlsx", failedBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(failedBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet).isNotNull();
            // 包含 1 个表头行 + 2 个校验失败行
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(3);
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("用户ID");
        }
    }

    // ==========================================
    // 场景 4：AsyncExportScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 4：后台受管异步导出任务提交、状态轮询、物理文件下载与过期清理")
    void testScenario4_AsyncExport_SubmitAndPollWorkflow() throws Exception {
        String taskId = asyncExportService.submitTask("场景4异步导出测试", 50);
        assertThat(taskId).isNotBlank();

        // 轮询直至任务成功
        long deadline = System.currentTimeMillis() + 8000;
        ExportTask task = null;
        while (System.currentTimeMillis() < deadline) {
            task = asyncExportService.getTask(taskId);
            if (task != null && task.getStatus() == ExportTaskStatus.SUCCESS) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(ExportTaskStatus.SUCCESS);

        // 下载任务文件并校验有效性
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        asyncExportService.downloadTaskFile(taskId, out);
        byte[] fileBytes = out.toByteArray();
        assertThat(fileBytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(fileBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(51); // 1 表头 + 50 条数据
        }

        // 测试过期任务清理
        asyncExportService.cleanExpiredTasks(Duration.ofHours(24));
    }

    // ==========================================
    // 场景 5：SecurityWorkbookScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 5：POI 原生 Agile AES-256 工作簿强加密与解密闭环")
    void testScenario5_SecurityWorkbook_Aes256Encryption() throws Exception {
        List<UserExcelVO> users = createMockUserList();
        String password = "StrongPassword@2026";

        ByteArrayOutputStream cipherOut = new ByteArrayOutputStream();
        securityWorkbookService.exportEncryptedWorkbook(cipherOut, users, password);
        byte[] cipherBytes = cipherOut.toByteArray();
        assertThat(cipherBytes).isNotEmpty();
        saveTempExcel("scenario5-encrypted.xlsx", cipherBytes);

        // 验证密文工作簿使用 POI 裸打开必然抛出 EncryptedDocumentException
        assertThatThrownBy(() -> WorkbookFactory.create(new ByteArrayInputStream(cipherBytes)))
                .isInstanceOf(org.apache.poi.EncryptedDocumentException.class);

        // 验证受管解密门面恢复明文
        ByteArrayOutputStream plainOut = new ByteArrayOutputStream();
        try (InputStream cipherIn = new ByteArrayInputStream(cipherBytes)) {
            securityWorkbookService.decryptWorkbook(cipherIn, plainOut, password);
        }
        byte[] plainBytes = plainOut.toByteArray();
        assertThat(plainBytes).isNotEmpty();

        try (Workbook plainWb = WorkbookFactory.create(new ByteArrayInputStream(plainBytes))) {
            Sheet sheet = plainWb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5); // 1 表头 + 4 数据
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("张三");
        }
    }

    @Test
    @DisplayName("场景 5：防拍照半透明全景文字水印与工作表只读保护密码")
    void testScenario5_SecurityWorkbook_WatermarkAndSheetLock() throws Exception {
        List<UserExcelVO> users = createMockUserList();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        securityWorkbookService.exportWithWatermarkAndProtection(
                out, users, "机密 · 内部严禁外传", "protect123"
        );
        byte[] bytes = out.toByteArray();
        saveTempExcel("scenario5-watermark.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getProtect()).isTrue();
            assertThat(wb.getAllPictures()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("场景 5：公式注入安全防御（CWE-1236）、白名单豁免与列级只读锁")
    void testScenario5_SecurityWorkbook_SecurityTemplate() throws Exception {
        // 1. 导出安全防呆空模板并验证表头与下拉约束
        ByteArrayOutputStream templateOut = new ByteArrayOutputStream();
        securityWorkbookService.exportSecurityTemplate(templateOut);
        byte[] templateBytes = templateOut.toByteArray();
        saveTempExcel("scenario5-security-template.xlsx", templateBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(templateBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertThat(headRow).isNotNull();
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("系统工号");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("员工姓名");
            assertThat(headRow.getCell(2).getStringCellValue()).isEqualTo("薪资计算公式");
            assertThat(headRow.getCell(3).getStringCellValue()).isEqualTo("核定职级");
        }

        // 2. 导出包含高危公式注入前缀与白名单计算公式的真实数据，验证单引号转译与白名单豁免
        List<SecurityUserExcelVO> securityData = createMockSecurityUserList();
        ByteArrayOutputStream dataOut = new ByteArrayOutputStream();
        securityWorkbookService.exportSecurityData(dataOut, securityData);
        byte[] dataBytes = dataOut.toByteArray();
        saveTempExcel("scenario5-security-data.xlsx", dataBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(dataBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(4); // 1 表头 + 3 数据

            // 第 1 行：=cmd|'/C calc'!A0 应被前置单引号转译；=SUM(A1:B1) 因 @ExcelFormulaAllowed 保留原样
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("'=cmd|'/C calc'!A0");
            assertThat(row1.getCell(2).getStringCellValue()).isEqualTo("=SUM(A1:B1)");

            // 第 2 行：+12345678 也应被前置单引号转译；=AVERAGE(C1:C10) 保留原样
            Row row2 = sheet.getRow(2);
            assertThat(row2.getCell(1).getStringCellValue()).isEqualTo("'+12345678");
            assertThat(row2.getCell(2).getStringCellValue()).isEqualTo("=AVERAGE(C1:C10)");

            // 第 3 行：普通文本保持不变
            Row row3 = sheet.getRow(3);
            assertThat(row3.getCell(1).getStringCellValue()).isEqualTo("普通员工");
            assertThat(row3.getCell(2).getStringCellValue()).isEqualTo("普通文本");
        }
    }

    // ==========================================
    // 场景 6：DynamicColumnProjectionScenarioService 实测
    // ==========================================

    @Test
    @DisplayName("场景 6：强类型 VO 动态列投影裁剪（白名单包含模式与黑名单排除模式）")
    void testScenario6_DynamicProjection_IncludeAndExclude() throws Exception {
        // 1. 白名单模式：仅导出 userId 与 email
        ByteArrayOutputStream includeOut = new ByteArrayOutputStream();
        dynamicProjectionService.exportWithColumnInclude(includeOut, List.of("userId", "email"));
        byte[] includeBytes = includeOut.toByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(includeBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            assertThat(head.getPhysicalNumberOfCells()).isEqualTo(2);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("用户ID");
            assertThat(head.getCell(1).getStringCellValue()).isEqualTo("电子邮箱");

            // 数据行与脱敏断言：白名单列投影紧凑左移且电子邮箱脱敏真实生效
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(row1.getCell(1).getStringCellValue()).contains("***").endsWith("@example.com");
        }

        // 2. 黑名单模式：排除 phone 与 gender
        ByteArrayOutputStream excludeOut = new ByteArrayOutputStream();
        dynamicProjectionService.exportWithColumnExclude(excludeOut, Set.of("phone", "gender"));
        byte[] excludeBytes = excludeOut.toByteArray();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(excludeBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            // 原 7 列 - 排除 2 列 = 5 列
            assertThat(head.getPhysicalNumberOfCells()).isEqualTo(5);
            for (int i = 0; i < head.getPhysicalNumberOfCells(); i++) {
                String colName = head.getCell(i).getStringCellValue();
                assertThat(colName).isNotEqualTo("联系手机");
                assertThat(colName).isNotEqualTo("用户性别");
            }

            // 数据行断言：黑名单排除后，其余字段（如电子邮箱）依然保真脱敏
            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(0).getNumericCellValue()).isEqualTo(1.0);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("张三");
            assertThat(row1.getCell(2).getStringCellValue()).contains("***").endsWith("@example.com");
        }
    }

    @Test
    @DisplayName("场景 6：无实体动态流式导出二维报表")
    void testScenario6_DynamicProjection_DynamicMatrix() throws Exception {
        List<List<String>> headList = List.of(
                List.of("报表日期"),
                List.of("销售大区"),
                List.of("业绩金额")
        );
        List<List<Object>> dataList = List.of(
                List.of("2026-10-28", "华东大区", 88888.88),
                List.of("2026-10-28", "华南大区", 66666.66)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dynamicProjectionService.exportDynamic(out, headList, dataList);
        byte[] bytes = out.toByteArray();
        saveTempExcel("scenario6-dynamic-matrix.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(3); // 1 表头 + 2 数据
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("报表日期");
            assertThat(head.getCell(1).getStringCellValue()).isEqualTo("销售大区");
            assertThat(head.getCell(2).getStringCellValue()).isEqualTo("业绩金额");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("华东大区");
            assertThat(row1.getCell(2).getNumericCellValue()).isEqualTo(88888.88);
        }
    }

    @Test
    @DisplayName("场景 6：多语言国际化表头导出与纵向相邻单元格自动合并")
    void testScenario6_DynamicProjection_I18nAndMerge() throws Exception {
        // 1. 国际化导出
        ByteArrayOutputStream i18nOut = new ByteArrayOutputStream();
        dynamicProjectionService.exportI18nUsers(i18nOut);
        byte[] i18nBytes = i18nOut.toByteArray();
        assertThat(i18nBytes).isNotEmpty();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(i18nBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isNotBlank();
        }

        // 2. 纵向合并导出
        ByteArrayOutputStream mergeOut = new ByteArrayOutputStream();
        dynamicProjectionService.exportMerge(mergeOut);
        byte[] mergeBytes = mergeOut.toByteArray();
        saveTempExcel("scenario6-merge.xlsx", mergeBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(mergeBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertThat(sheet.getNumMergedRegions()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("场景 6：防呆导入模板导出（下拉列表约束与表头悬浮批注）")
    void testScenario6_DynamicProjection_DropdownTemplate() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        dynamicProjectionService.exportDropdownTemplate(out);
        byte[] bytes = out.toByteArray();
        saveTempExcel("scenario6-dropdown-template.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);

            // 批注验证
            Cell nameCell = headRow.getCell(1);
            assertThat(nameCell.getStringCellValue()).isEqualTo("用户姓名");
            Comment nameComment = nameCell.getCellComment();
            assertThat(nameComment).isNotNull();
            assertThat(nameComment.getString().getString()).contains("请输入 2-20 位真实姓名");

            // 下拉验证
            List<? extends DataValidation> validations = sheet.getDataValidations();
            assertThat(validations).isNotEmpty();
        }
    }

    @Test
    @DisplayName("企业级内置主题：生成 5 套主题 Excel 样式输出至临时目录")
    void testThemes_AllSampleThemesOutput() throws Exception {
        List<UserExcelVO> sampleUsers = createMockUserList();

        for (ExcelThemeEnum theme : ExcelThemeEnum.values()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ExcelBuilder builder = ExcelUtil.builder(out)) {
                builder.theme(theme)
                        .zebraStripe(true)
                        .autoColumnWidth(12, 60)
                        .sheet(theme.name(), UserExcelVO.class, sampleUsers);
            }
            byte[] bytes = out.toByteArray();
            saveTempExcel("theme-" + theme.name().toLowerCase().replace('_', '-') + ".xlsx", bytes);
            assertThat(bytes).isNotEmpty();
        }
    }

    /**
     * 生成未经脱敏的原始用户上传 Excel 文件字节（模拟用户手工填写的真实明文表单）
     */
    private byte[] createRawExcel(List<UserExcelVO> list) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        org.apache.fesod.sheet.FesodSheet.write(out, UserExcelVO.class)
                .registerConverter(new io.github.atengk.boot.excel.converter.ExcelEnumConverter(SampleGenderEnum.class, null, null))
                .sheet("Sheet1")
                .doWrite(list);
        return out.toByteArray();
    }

    private List<UserExcelVO> createMockUserList() {
        return List.of(
                new UserExcelVO(1L, "张三", "13800138000", "zhangsan@example.com", SampleGenderEnum.MALE, "研发部", "0"),
                new UserExcelVO(2L, "李四", "13900139000", "lisi@example.com", SampleGenderEnum.FEMALE, "研发部", "0"),
                new UserExcelVO(3L, "王五", "13700137000", "wangwu@example.com", SampleGenderEnum.MALE, "市场部", "1"),
                new UserExcelVO(4L, "赵六", "13600136000", "zhaoliu@example.com", SampleGenderEnum.FEMALE, "财务部", "0")
        );
    }

    private List<SecurityUserExcelVO> createMockSecurityUserList() {
        return List.of(
                new SecurityUserExcelVO(1001L, "=cmd|'/C calc'!A0", "=SUM(A1:B1)", "P7 专家"),
                new SecurityUserExcelVO(1002L, "+12345678", "=AVERAGE(C1:C10)", "P8 架构师"),
                new SecurityUserExcelVO(1003L, "普通员工", "普通文本", "P6 工程师")
        );
    }
}
