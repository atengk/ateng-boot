package io.github.atengk.boot.sample;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.sample.enums.SampleGenderEnum;
import io.github.atengk.boot.sample.vo.UserExcelVO;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Excel Starter 全栈端到端自动化验收集成测试
 * 覆盖流式导出、动态脱敏、防呆模板下载、JSR-303 导入校验与错题集标红批注回写全场景
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

            Row row1 = sheet.getRow(1);
            // 索引 2: 手机号脱敏
            String phone = row1.getCell(2).getStringCellValue();
            assertThat(phone).isEqualTo("138****8000");

            // 索引 3: 邮箱脱敏
            String email = row1.getCell(3).getStringCellValue();
            assertThat(email).contains("***@");

            // 索引 4: 性别枚举转义
            String gender = row1.getCell(4).getStringCellValue();
            assertThat(gender).isEqualTo("男");

            // 索引 6: 状态字典转义
            String status = row1.getCell(6).getStringCellValue();
            assertThat(status).isEqualTo("正常");
        }
    }

    @Test
    @DisplayName("测试下载防呆导入模板：验证表头批注指南与下拉选单数据约束")
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

            // 验证下拉数据约束
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, UserExcelVO.class, validList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "import_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
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
    @DisplayName("测试导入用户数据校验失败：结构化返回错误行数与业务提示")
    void testImportUsersValidationError() throws Exception {
        List<UserExcelVO> invalidList = new ArrayList<>();
        // 姓名为空、手机号格式错误、邮箱格式错误
        invalidList.add(new UserExcelVO(3001L, "", "invalid-phone", "not-an-email",
                SampleGenderEnum.FEMALE, "研发部", "0"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, UserExcelVO.class, invalidList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "invalid_users.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
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

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, UserExcelVO.class, invalidList);

        MockMultipartFile file = new MockMultipartFile(
                "file", "feedback_test.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray()
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
    }
}
