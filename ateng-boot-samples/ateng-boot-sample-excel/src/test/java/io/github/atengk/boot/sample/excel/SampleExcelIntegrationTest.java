package io.github.atengk.boot.sample.excel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.excel.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.excel.vo.DepartmentUserMergeVO;
import io.github.atengk.boot.sample.excel.vo.UserExcelVO;
import org.apache.poi.ss.usermodel.*;
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
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel Starter 全栈端到端自动化验收集成测试
 * 覆盖流式导出、多 Sheet 编排、单元格合并、动态脱敏、防呆模板下载、JSR-303 导入校验与错题集标红批注回写全场景
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

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("测试流式导出用户列表：验证 HTTP 响应头、动态脱敏掩码与枚举/字典转义")
    void testExportUsers() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/export"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(disposition).contains("attachment");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("用户列表.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        // 使用 POI 打开工作簿断言脱敏字面量与枚举转义
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(4); // 1 行表头 + 3 行数据

            // 验证样式：企业级默认极简白表头（无底色填充）、细边框与加粗字体
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

        saveTempExcel("sample-exported-users.xlsx", content);
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws Exception {
        Path target = tempDir.resolve(fileName);
        Files.write(target, bytes);
    }

    @Test
    @DisplayName("测试 Fluent 多 Sheet 复杂流式导出：验证工作表数量、各表名称与数据流式写出")
    void testExportMultiSheetViaController() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/export-multi-sheet"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("综合多工作表报表.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);

            // Sheet 0: 用户清单
            Sheet sheet1 = workbook.getSheetAt(0);
            assertThat(sheet1.getSheetName()).isEqualTo("用户清单");
            assertThat(sheet1.getPhysicalNumberOfRows()).isEqualTo(4); // 1 行表头 + 3 行数据

            // Sheet 1: 销售汇总
            Sheet sheet2 = workbook.getSheetAt(1);
            assertThat(sheet2.getSheetName()).isEqualTo("销售汇总");
            assertThat(sheet2.getPhysicalNumberOfRows()).isEqualTo(4); // 1 行表头 + 3 行数据
            Row head2 = sheet2.getRow(0);
            assertThat(head2.getCell(0).getStringCellValue()).isEqualTo("销售大区");
            assertThat(head2.getCell(1).getStringCellValue()).isEqualTo("季度目标");
        }

        saveTempExcel("sample-multi-sheet.xlsx", content);
    }

    @Test
    @DisplayName("测试纵向相邻单元格合并导出：验证 @ExcelMerge 区域与合并单元格数量")
    void testExportMergeViaController() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/export-merge"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("部门员工合并报表.xlsx");

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            // 验证存在纵向合并区域
            assertThat(sheet.getNumMergedRegions()).isGreaterThan(0);
            // 部门列第 0 列发生相邻行纵向合并（研发部 2 行、市场部 2 行）
            boolean foundCol0Merge = false;
            for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
                var range = sheet.getMergedRegion(i);
                if (range.getFirstColumn() == 0 && range.getLastColumn() == 0 && range.getFirstRow() < range.getLastRow()) {
                    foundCol0Merge = true;
                    break;
                }
            }
            assertThat(foundCol0Merge).isTrue();
        }

        saveTempExcel("sample-merge.xlsx", content);
    }

    @Test
    @DisplayName("测试下载防呆导入模板：验证表头批注指南与静态及动态下拉选单数据约束")
    void testDownloadTemplate() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/template"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headRow = sheet.getRow(0);

            // 验证表头批注指南
            Cell nameCell = headRow.getCell(1);
            assertThat(nameCell.getStringCellValue()).isEqualTo("用户姓名");
            Comment nameComment = nameCell.getCellComment();
            assertThat(nameComment).isNotNull();
            assertThat(nameComment.getString().getString()).contains("请输入 2-20 位真实姓名");

            Cell deptCell = headRow.getCell(5);
            assertThat(deptCell.getStringCellValue()).isEqualTo("所属部门");
            Comment deptComment = deptCell.getCellComment();
            assertThat(deptComment).isNotNull();
            assertThat(deptComment.getString().getString()).contains("请从下拉列表中选择所属部门");

            // 验证下拉数据约束（包含性别静态下拉与部门动态下拉）
            List<? extends DataValidation> validations = sheet.getDataValidations();
            assertThat(validations).isNotEmpty();
        }
    }

    @Test
    @DisplayName("测试导入用户数据成功：合规数据解析为 Java 集合并显式返回 Result")
    void testImportUsersSuccess() throws Exception {
        List<UserExcelVO> validList = new ArrayList<>();
        validList.add(new UserExcelVO(2001L, "赵六", "13600136000", "zhaoliu@example.com",
                SampleGenderEnum.MALE, "研发部", "0"));

        byte[] rawExcelBytes = createRawExcel(validList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "import_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rawExcelBytes
        );

        MvcResult mvcResult = mockMvc.perform(multipart("/api/excel/import").file(file))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.get("code").asInt()).isEqualTo(200);
        assertThat(root.get("data").isArray()).isTrue();
        assertThat(root.get("data").size()).isEqualTo(1);
        assertThat(root.get("data").get(0).get("username").asText()).isEqualTo("赵六");
    }

    @Test
    @DisplayName("测试双向业务枚举与字典 SPI 反向解析：表格中文字面量精确还原为枚举与业务代码")
    void testImportEnumAndDictReverseMapping() throws Exception {
        // 构建带有中文展示字面量的用户列表：性别="女"、状态="停用"
        List<UserExcelVO> inputList = List.of(
                new UserExcelVO(2002L, "孙俪", "13700137000", "sunli@example.com",
                        SampleGenderEnum.FEMALE, "财务部", "1")
        );

        byte[] rawExcelBytes = createRawExcel(inputList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "reverse_mapping.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rawExcelBytes
        );

        MvcResult mvcResult = mockMvc.perform(multipart("/api/excel/import").file(file))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.get("code").asInt()).isEqualTo(200);
        JsonNode firstUser = root.get("data").get(0);
        // 断言反向解析结果：枚举还原为 FEMALE，状态还原为 "1"
        assertThat(firstUser.get("gender").asText()).isEqualTo("FEMALE");
        assertThat(firstUser.get("status").asText()).isEqualTo("1");
    }

    @Test
    @DisplayName("测试导入用户数据校验失败：结构化返回错误行数与业务提示")
    void testImportUsersValidationError() throws Exception {
        List<UserExcelVO> invalidList = new ArrayList<>();
        // 姓名为空、手机号格式错误、邮箱格式错误
        invalidList.add(new UserExcelVO(3001L, "", "invalid-phone", "not-an-email",
                SampleGenderEnum.FEMALE, "研发部", "0"));

        byte[] rawExcelBytes = createRawExcel(invalidList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "invalid_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rawExcelBytes
        );

        MvcResult mvcResult = mockMvc.perform(multipart("/api/excel/import").file(file))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.get("code").asInt()).isEqualTo(400);
        assertThat(root.get("msg").asText()).contains("Excel 数据校验不通过");
    }

    @Test
    @DisplayName("测试导入失败错题集回写：验证生成的错误反馈 Excel 中单元格标红与悬浮批注")
    void testImportWithFeedbackExcel() throws Exception {
        List<UserExcelVO> invalidList = new ArrayList<>();
        invalidList.add(new UserExcelVO(4001L, "", "13800138000", "good@example.com",
                SampleGenderEnum.FEMALE, "研发部", "0"));

        byte[] rawExcelBytes = createRawExcel(invalidList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "feedback_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rawExcelBytes
        );

        MvcResult mvcResult = mockMvc.perform(multipart("/api/excel/import-feedback").file(file))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        String disposition = mvcResult.getResponse().getHeader("Content-Disposition");
        assertThat(URLDecoder.decode(disposition, StandardCharsets.UTF_8)).contains("导入错误反馈.xlsx");

        byte[] feedbackBytes = mvcResult.getResponse().getContentAsByteArray();
        assertThat(feedbackBytes).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(feedbackBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row1 = sheet.getRow(1);
            Cell nameCell = row1.getCell(1);

            // 校验失败单元格标红背景并挂载悬浮批注
            assertThat(nameCell.getCellStyle().getFillForegroundColor()).isGreaterThan((short) 0);
            Comment comment = nameCell.getCellComment();
            assertThat(comment).isNotNull();
            assertThat(comment.getString().getString()).contains("用户姓名不能为空");
        }
    }

    @Test
    @DisplayName("测试导入反馈端点校验通过场景：全部合规时写回强类型成功 Result")
    void testImportWithFeedbackSuccess() throws Exception {
        List<UserExcelVO> validList = new ArrayList<>();
        validList.add(new UserExcelVO(5001L, "孙七", "13500135000", "sunqi@example.com",
                SampleGenderEnum.MALE, "研发部", "0"));

        byte[] rawExcelBytes = createRawExcel(validList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "valid_feedback.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                rawExcelBytes
        );

        MvcResult mvcResult = mockMvc.perform(multipart("/api/excel/import-feedback").file(file))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = mvcResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(responseBody);
        assertThat(root.get("code").asInt()).isEqualTo(200);
        assertThat(root.get("data").asInt()).isEqualTo(1);
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

    @Test
    @DisplayName("测试无实体动态导出：验证动态表头与二维数据流式写出")
    void testExportDynamic() throws Exception {
        MvcResult mvcResult = mockMvc.perform(get("/api/excel/export-dynamic"))
                .andExpect(status().isOk())
                .andExpect(header().exists("Content-Disposition"))
                .andReturn();

        byte[] content = mvcResult.getResponse().getContentAsByteArray();
        assertThat(content).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getPhysicalNumberOfRows()).isEqualTo(4); // 1 行表头 + 3 行数据

            Row head = sheet.getRow(0);
            assertThat(head.getCell(0).getStringCellValue()).isEqualTo("报表日期");
            assertThat(head.getCell(1).getStringCellValue()).isEqualTo("销售大区");
            assertThat(head.getCell(2).getStringCellValue()).isEqualTo("业绩金额");

            Row row1 = sheet.getRow(1);
            assertThat(row1.getCell(1).getStringCellValue()).isEqualTo("华东大区");
            assertThat(row1.getCell(2).getNumericCellValue()).isEqualTo(88888.88);
        }

        saveTempExcel("sample-exported-dynamic.xlsx", content);
    }

    @Test
    @DisplayName("测试生成 5 套企业级内置主题样例 Excel 并输出至临时目录供人工核验")
    void testAllSampleThemesOutputToTempDirectory() throws Exception {
        List<UserExcelVO> sampleUsers = List.of(
                new UserExcelVO(101L, "张三丰", "13800000001", "zhang@example.com", SampleGenderEnum.MALE, "研发部", "0"),
                new UserExcelVO(102L, "李清照", "13800000002", "li@example.com", SampleGenderEnum.FEMALE, "市场部", "0"),
                new UserExcelVO(103L, "王阳明", "13800000003", "wang@example.com", SampleGenderEnum.MALE, "财务部", "1")
        );

        for (ExcelThemeEnum theme : ExcelThemeEnum.values()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ExcelBuilder builder = ExcelUtil.builder(out)) {
                builder.theme(theme)
                        .zebraStripe(true)
                        .autoColumnWidth(12, 60)
                        .sheet(theme.name(), UserExcelVO.class, sampleUsers);
            }
            byte[] bytes = out.toByteArray();
            saveTempExcel("sample-theme-" + theme.name().toLowerCase().replace('_', '-') + ".xlsx", bytes);
            assertThat(bytes).isNotEmpty();
        }
    }

    @Test
    @DisplayName("测试防拍照全屏暗水印与工作表密码锁定端到端集成")
    void testExportWithWatermarkAndProtection() throws Exception {
        List<UserExcelVO> sampleUsers = List.of(
                new UserExcelVO(101L, "张三丰", "13800000001", "zhang@example.com", SampleGenderEnum.MALE, "研发部", "0")
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.exportWithWatermark(
                out,
                "机密内参表",
                sampleUsers,
                UserExcelVO.class,
                "机密 · 示例项目 · 2026-10-17",
                "samplePass123"
        );
        byte[] bytes = out.toByteArray();
        saveTempExcel("sample-watermark-confidential.xlsx", bytes);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheet("机密内参表");
            assertThat(sheet).isNotNull();
            assertThat(sheet.getProtect()).isTrue();
            assertThat(workbook.getAllPictures()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("测试异步导出任务中心 Controller 端点：提交任务、查询进度并验证文件落盘")
    void testAsyncExportControllerWorkflow() throws Exception {
        MvcResult submitResult = mockMvc.perform(post("/api/excel/async-export")
                        .param("taskName", "全量用户异步导出测试"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        String body = submitResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(body);
        String taskId = root.get("data").asText();
        assertThat(taskId).isNotBlank();

        // 轮询查询任务状态
        long deadline = System.currentTimeMillis() + 8000;
        JsonNode taskNode = null;
        while (System.currentTimeMillis() < deadline) {
            MvcResult queryResult = mockMvc.perform(get("/api/excel/async-task/" + taskId))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode queryRoot = objectMapper.readTree(queryResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
            taskNode = queryRoot.get("data");
            String status = taskNode.get("status").asText();
            if ("SUCCESS".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }

        assertThat(taskNode).isNotNull();
        assertThat(taskNode.get("status").asText()).isEqualTo("SUCCESS");
        String filePath = taskNode.get("filePath").asText();
        assertThat(new java.io.File(filePath)).exists();
    }

    @Test
    @DisplayName("测试万级大数据量流式读写与内存有界分批消费仿真")
    void testLargeDatasetStreamingSimulation() throws Exception {
        int totalRows = 5000;
        List<UserExcelVO> largeList = new ArrayList<>(totalRows);
        for (int i = 1; i <= totalRows; i++) {
            largeList.add(new UserExcelVO(
                    (long) i,
                    "批量用户_" + i,
                    "138" + String.format("%08d", i),
                    "user_" + i + "@example.com",
                    (i % 2 == 0) ? SampleGenderEnum.MALE : SampleGenderEnum.FEMALE,
                    "研发部",
                    "0"
            ));
        }

        // 1. 流式写出到输出流
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, largeList, UserExcelVO.class);
        byte[] excelBytes = out.toByteArray();
        assertThat(excelBytes).isNotEmpty();

        // 2. 分批流式读取消费（每批 1000 条），验证有界内存缓冲区与完整性
        ByteArrayInputStream in = new ByteArrayInputStream(excelBytes);
        java.util.concurrent.atomic.AtomicInteger batchCounter = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger totalCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        ExcelUtil.readBatch(in, UserExcelVO.class, 1000, batch -> {
            batchCounter.incrementAndGet();
            totalCounter.addAndGet(batch.size());
            // 校验批次数据脱敏前瞻（写出时已由 SensitiveCellWriteHandler 掩码）
            for (UserExcelVO user : batch) {
                assertThat(user.getPhone()).contains("****");
            }
        });

        assertThat(batchCounter.get()).isEqualTo(5);
        assertThat(totalCounter.get()).isEqualTo(totalRows);
    }
}
