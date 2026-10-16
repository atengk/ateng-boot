package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.model.TemplateUserVO;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 模板智能生成与下拉/提示防呆约束测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelTemplateTest {

    @Test
    @DisplayName("测试模板智能生成：表头批注嵌入与原生 DataValidation 下拉约束正确注入")
    void testExportTemplateWithDropdownAndPrompt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 1. 调用模板生成门面
        ExcelUtil.exportTemplate(out, "员工导入模板", TemplateUserVO.class);

        byte[] templateBytes = out.toByteArray();
        assertTrue(templateBytes.length > 0, "导出的模板二进制流不可为空");

        // 2. 通过 POI 读取验证生成的 Excel 模板
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(templateBytes))) {
            Sheet sheet = workbook.getSheet("员工导入模板");
            assertNotNull(sheet, "应生成名为'员工导入模板'的工作表");

            // 验证表头
            Row headRow = sheet.getRow(0);
            assertNotNull(headRow, "表头行必须存在");
            assertEquals("用户名称", headRow.getCell(0).getStringCellValue());
            assertEquals("性别", headRow.getCell(1).getStringCellValue());
            assertEquals("所属部门", headRow.getCell(2).getStringCellValue());
            assertEquals("手机号码", headRow.getCell(3).getStringCellValue());

            // 验证只有表头，数据行为空
            assertNull(sheet.getRow(1), "模板数据首行应当为空，供用户填报");

            // 3. 验证表头单元格嵌入的悬浮批注
            Comment nameComment = headRow.getCell(0).getCellComment();
            assertNotNull(nameComment, "用户名称表头应存在批注");
            assertTrue(nameComment.getString().getString().contains("请填写 2-20 位真实中文姓名"));

            Comment mobileComment = headRow.getCell(3).getCellComment();
            assertNotNull(mobileComment, "手机号码表头应存在批注");
            String mobileText = mobileComment.getString().getString();
            assertTrue(mobileText.contains("[联系方式]"));
            assertTrue(mobileText.contains("仅支持 11 位大陆有效手机号"));

            // 4. 验证数据验证 (DataValidation) 下拉框
            List<? extends DataValidation> validations = sheet.getDataValidations();
            assertFalse(validations.isEmpty(), "模板中必须包含数据验证规则");

            // 查找列 1 (性别) 的静态下拉校验
            DataValidation genderValidation = validations.stream()
                    .filter(v -> Arrays.stream(v.getRegions().getCellRangeAddresses())
                            .anyMatch(addr -> addr.getFirstColumn() == 1 && addr.getLastColumn() == 1))
                    .findFirst()
                    .orElse(null);
            assertNotNull(genderValidation, "列 1 (性别) 必须配置 DataValidation 下拉选择");
            String[] genderOptions = genderValidation.getValidationConstraint().getExplicitListValues();
            assertNotNull(genderOptions);
            assertArrayEquals(new String[]{"男", "女", "保密"}, genderOptions);

            // 查找列 2 (部门) 的动态 Provider 下拉校验
            DataValidation deptValidation = validations.stream()
                    .filter(v -> Arrays.stream(v.getRegions().getCellRangeAddresses())
                            .anyMatch(addr -> addr.getFirstColumn() == 2 && addr.getLastColumn() == 2))
                    .findFirst()
                    .orElse(null);
            assertNotNull(deptValidation, "列 2 (所属部门) 必须配置 DataValidation 下拉选择");
            String[] deptOptions = deptValidation.getValidationConstraint().getExplicitListValues();
            assertNotNull(deptOptions);
            assertArrayEquals(new String[]{"技术研发中心", "产品设计部", "市场运营部", "综合财务部"}, deptOptions);
        }
    }

    @Test
    @DisplayName("测试 Web 环境下模板直接下载：断言 UTF-8 防乱码响应头与 MIME 类型")
    void testExportTemplateWebResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ExcelUtil.exportTemplate(response, "用户填报模板", TemplateUserVO.class);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        String disposition = response.getHeader("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("attachment"));
        assertTrue(disposition.contains("filename="));

        byte[] content = response.getContentAsByteArray();
        assertTrue(content.length > 0, "响应体应输出模板文件流");

        // 简单断言解析
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);
            assertEquals("用户名称", sheet.getRow(0).getCell(0).getStringCellValue());
        }
    }
}
