package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.SensitiveUser;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 强类型 VO 动态列投影与自选列紧凑裁剪单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
class ExcelColumnProjectionTest {

    private List<DemoUser> createDemoUsers() {
        return List.of(
                DemoUser.builder()
                        .id(1L)
                        .username("张三")
                        .age(28)
                        .balance(new BigDecimal("100.50"))
                        .createTime(new Date())
                        .build(),
                DemoUser.builder()
                        .id(2L)
                        .username("李四")
                        .age(32)
                        .balance(new BigDecimal("888.88"))
                        .createTime(new Date())
                        .build()
        );
    }

    @Test
    @DisplayName("测试 ExcelUtil.export 白名单列投影：仅导出指定列且紧凑左移无空列")
    void testIncludeColumnsWithExcelUtil() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 仅导出 username 和 balance
        ExcelUtil.export(out, users, DemoUser.class, Set.of("username", "balance"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow);
            assertEquals(2, headerRow.getLastCellNum(), "表头应严格仅有 2 列");
            assertEquals("用户名", headerRow.getCell(0).getStringCellValue());
            assertEquals("账户余额", headerRow.getCell(1).getStringCellValue());

            Row dataRow = sheet.getRow(1);
            assertNotNull(dataRow);
            assertEquals("张三", dataRow.getCell(0).getStringCellValue());
            assertEquals(100.50, dataRow.getCell(1).getNumericCellValue(), 0.01);
        }
    }

    @Test
    @DisplayName("测试 ExcelUtil.export 黑名单过滤：排除指定列且剩余列紧凑对齐")
    void testExcludeColumnsWithExcelUtil() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 排除 id, createTime, age，剩余 username 和 balance
        ExcelUtil.export(out, "自定义Sheet", users, DemoUser.class, null, Set.of("id", "createTime", "age"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("自定义Sheet");
            assertNotNull(sheet);

            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow);
            assertEquals(2, headerRow.getLastCellNum(), "表头应严格仅有 2 列");
            assertEquals("用户名", headerRow.getCell(0).getStringCellValue());
            assertEquals("账户余额", headerRow.getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 动态列投影保留实体注解：@ExcelSensitive 动态掩码在保留列上依然生效")
    void testPreserveSensitiveAnnotationOnProjectedColumns() throws Exception {
        List<SensitiveUser> users = List.of(
                SensitiveUser.builder()
                        .name("诸葛孔明")
                        .mobile("13812345678")
                        .idCard("110101199003072395")
                        .email("zhuge@example.com")
                        .bankCard("6222021234567890123")
                        .build()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 仅包含 name 和 mobile 字段
        ExcelUtil.export(out, users, SensitiveUser.class, Set.of("name", "mobile"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            Row headerRow = sheet.getRow(0);
            assertEquals(2, headerRow.getLastCellNum(), "应严格仅保留 2 列");
            assertEquals("用户姓名", headerRow.getCell(0).getStringCellValue());
            assertEquals("手机号", headerRow.getCell(1).getStringCellValue());

            Row dataRow = sheet.getRow(1);
            // 验证 @ExcelSensitive 动态脱敏不变量生效
            assertEquals("诸**明", dataRow.getCell(0).getStringCellValue(), "姓名应脱敏为 诸**明");
            assertEquals("138****5678", dataRow.getCell(1).getStringCellValue(), "手机号应脱敏为 138****5678");
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 多 Sheet 分别独立配置动态列投影")
    void testExcelBuilderMultiSheetProjection() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 1 仅输出用户名（1 列）
            builder.sheet("用户姓名表", DemoUser.class, users, Set.of("username"));
            // Sheet 2 输出用户名、年龄与余额（3 列）
            builder.sheet("用户详情表", DemoUser.class, users, Set.of("username", "age", "balance"));
            builder.finish();
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet1 = workbook.getSheetAt(0);
            assertEquals("用户姓名表", sheet1.getSheetName());
            assertEquals(1, sheet1.getRow(0).getLastCellNum(), "Sheet 1 应仅有 1 列");
            assertEquals("用户名", sheet1.getRow(0).getCell(0).getStringCellValue());

            Sheet sheet2 = workbook.getSheetAt(1);
            assertEquals("用户详情表", sheet2.getSheetName());
            assertEquals(3, sheet2.getRow(0).getLastCellNum(), "Sheet 2 应有 3 列");
            assertEquals("用户名", sheet2.getRow(0).getCell(0).getStringCellValue());
            assertEquals("年龄", sheet2.getRow(0).getCell(1).getStringCellValue());
            assertEquals("账户余额", sheet2.getRow(0).getCell(2).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 全局 includeColumns 链式配置")
    void testExcelBuilderGlobalIncludeColumns() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            builder.includeColumns(Set.of("username", "balance"))
                    .sheet("用户表", DemoUser.class, users)
                    .finish();
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getRow(0).getLastCellNum(), "全局 includeColumns 应生效为 2 列");
        }
    }

    @Test
    @DisplayName("测试 StreamingPageExportTemplate 海量分页流式模板支持动态列投影")
    void testStreamingPageExportTemplateWithProjection() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ExcelUtil.streaming(out, DemoUser.class)
                .includeColumns(Set.of("username", "age"))
                .exportByPage(page -> (page == 1) ? PageResult.of(users, 2L, 1, 10) : PageResult.empty());

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertEquals(2, sheet.getRow(0).getLastCellNum(), "流式分页模板应输出 2 列");
            assertEquals("用户名", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("年龄", sheet.getRow(0).getCell(1).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 防御性行为：空集合或包含不存在的字段名称时平滑处理不崩溃")
    void testDefensiveHandlingEmptyAndNonExistentFields() throws Exception {
        List<DemoUser> users = createDemoUsers();

        // 1. 空集合 includeColumns：视为不设白名单，全量导出 5 列
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ExcelUtil.export(out1, users, DemoUser.class, Collections.emptySet());
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out1.toByteArray()))) {
            assertEquals(5, wb.getSheetAt(0).getRow(0).getLastCellNum(), "空白名单应回退全量导出 5 列");
        }

        // 2. 包含合法字段 + 不存在字段：导出合法字段，忽略不存在字段
        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ExcelUtil.export(out2, users, DemoUser.class, Set.of("username", "notExistField"));
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out2.toByteArray()))) {
            assertEquals(1, wb.getSheetAt(0).getRow(0).getLastCellNum(), "应仅导出存在的 username 列");
            assertEquals("用户名", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 Web 响应导出支持 includeColumns 动态列投影")
    void testWebResponseExportWithProjection() throws Exception {
        List<DemoUser> users = createDemoUsers();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ExcelUtil.export(response, "用户清单.xlsx", users, DemoUser.class, Set.of("username", "balance"));

        assertEquals(200, response.getStatus());
        assertTrue(response.containsHeader("Content-Disposition"));

        byte[] content = response.getContentAsByteArray();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            assertEquals(2, wb.getSheetAt(0).getRow(0).getLastCellNum(), "Web 导出应正确投影为 2 列");
        }
    }

    @Test
    @DisplayName("测试 List 显式有序列投影：按照用户指定的 List 顺序自定义排序列")
    void testExplicitOrderedIncludeColumns() throws Exception {
        List<DemoUser> users = createDemoUsers();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 显式指定颠倒顺序：balance 在前，username 在后
        ExcelUtil.export(out, users, DemoUser.class, List.of("balance", "username"));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            Row headerRow = sheet.getRow(0);
            assertEquals(2, headerRow.getLastCellNum());
            assertEquals("账户余额", headerRow.getCell(0).getStringCellValue());
            assertEquals("用户名", headerRow.getCell(1).getStringCellValue());

            Row dataRow = sheet.getRow(1);
            assertEquals(100.50, dataRow.getCell(0).getNumericCellValue(), 0.01);
            assertEquals("张三", dataRow.getCell(1).getStringCellValue());
        }
    }
}
