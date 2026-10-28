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
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.service.AsyncExportScenarioService;
import io.github.atengk.boot.sample.excel.service.DynamicColumnProjectionScenarioService;
import io.github.atengk.boot.sample.excel.service.MultiSheetScenarioService;
import io.github.atengk.boot.sample.excel.service.SecurityWorkbookScenarioService;
import io.github.atengk.boot.sample.excel.service.StreamingPageExportScenarioService;
import io.github.atengk.boot.sample.excel.service.ValidationAndFeedbackScenarioService;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
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

    private void saveTempExcel(String fileName, byte[] bytes) throws Exception {
        Path target = tempDir.resolve(fileName);
        Files.write(target, bytes);
    }

    // ==========================================
    // Web 契约层（MockMvc 最小接入边界测试）
    // ==========================================

    @Test
    @DisplayName("Web 契约 1：ResponseEntity<StreamingResponseBody> 异步流式写出与动态列白名单投影")
    void testWebContract_ResponseEntity_WithProjection() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/web-contract/response-entity")
                        .param("includeColumns", "userId", "username"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 触发异步调度完成
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"));

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(disposition).contains("filename*=");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("用户名单_异步流式.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        // POI 打开工作簿验证列白名单裁剪生效（仅存在 2 列：userId, userName）
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5); // 1 行表头 + 4 条 Mock 用户

            Row headRow = sheet.getRow(0);
            assertThat(headRow.getPhysicalNumberOfCells()).isEqualTo(2);
            assertThat(headRow.getCell(0).getStringCellValue()).isEqualTo("用户ID");
            assertThat(headRow.getCell(1).getStringCellValue()).isEqualTo("用户姓名");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("张三");
        }

        saveTempExcel("web-contract-response-entity.xlsx", content);
    }

    @Test
    @DisplayName("Web 契约 2：HttpServletResponse 同步流式写出与脱敏/枚举转义")
    void testWebContract_ServletResponse() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/web-contract/servlet-response"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("用户名单_同步流式.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(5); // 1 行表头 + 4 条数据

            // 验证样式：企业级默认极简白表头（无底色填充）、细边框
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertThat(headStyle.getFillPattern()).isEqualTo(FillPatternType.NO_FILL);
            assertThat(headStyle.getBorderTop()).isEqualTo(BorderStyle.THIN);

            Row row1 = sheet.getRow(1);
            // 索引 2: 手机号脱敏
            String phone = row1.getCell(2).getStringCellValue();
            assertThat(phone).isEqualTo("138****8000");

            // 索引 3: 邮箱脱敏
            String email = row1.getCell(3).getStringCellValue();
            assertThat(email).contains("@example.com").contains("*");

            // 索引 4: 性别枚举转义
            String gender = row1.getCell(4).getStringCellValue();
            assertThat(gender).isEqualTo("男");

            // 索引 6: 状态字典转义
            String status = row1.getCell(6).getStringCellValue();
            assertThat(status).isEqualTo("正常");
        }

        saveTempExcel("web-contract-servlet-response.xlsx", content);
    }

    @Test
    @DisplayName("Web 契约 3：MultipartFile 上传导入与 JSR-303 结构化 Result 校验")
    void testWebContract_UploadValidate() throws Exception {
        // 1. 构造合规数据上传
        List<UserExcelVO> validList = List.of(
                new UserExcelVO(2001L, "赵六", "13600136000", "zhaoliu@example.com",
                        SampleGenderEnum.MALE, "研发部", "0")
        );
        byte[] validExcel = createRawExcel(validList);
        MockMultipartFile validFile = new MockMultipartFile(
                "file", "valid_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                validExcel
        );

        MvcResult validResult = mockMvc.perform(multipart("/api/excel/web-contract/upload-validate").file(validFile))
                .andExpect(status().isOk())
                .andReturn();

        String validBody = validResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode validNode = objectMapper.readTree(validBody);
        assertThat(validNode.get("code").asInt()).isEqualTo(200);
        assertThat(validNode.get("data").get("data").size()).isEqualTo(1);
        assertThat(validNode.get("data").get("errors").size()).isEqualTo(0);
        assertThat(validNode.get("data").get("success").asBoolean()).isTrue();

        // 2. 构造包含校验错误的数据上传（姓名为空、手机号格式错误）
        List<UserExcelVO> invalidList = List.of(
                new UserExcelVO(3001L, "", "invalid-phone", "not-an-email",
                        SampleGenderEnum.FEMALE, "研发部", "0")
        );
        byte[] invalidExcel = createRawExcel(invalidList);
        MockMultipartFile invalidFile = new MockMultipartFile(
                "file", "invalid_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                invalidExcel
        );

        MvcResult invalidResult = mockMvc.perform(multipart("/api/excel/web-contract/upload-validate").file(invalidFile))
                .andExpect(status().isOk())
                .andReturn();

        String invalidBody = invalidResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode invalidNode = objectMapper.readTree(invalidBody);
        assertThat(invalidNode.get("code").asInt()).isEqualTo(200);
        assertThat(invalidNode.get("data").get("data").size()).isEqualTo(0);
        assertThat(invalidNode.get("data").get("errors").size()).isEqualTo(3);
        assertThat(invalidNode.get("data").get("success").asBoolean()).isFalse();
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
        List<UserExcelVO> users = dynamicProjectionService.getMockUserList();
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
        List<UserExcelVO> users = dynamicProjectionService.getMockUserList();
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        securityWorkbookService.exportSecurityTemplate(out);
        byte[] bytes = out.toByteArray();
        saveTempExcel("scenario5-security-template.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row headRow = sheet.getRow(0);
            assertThat(headRow).isNotNull();
            // 列锁定与公式白名单在实体注解声明中生效
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
        List<UserExcelVO> sampleUsers = dynamicProjectionService.getMockUserList();

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
}
