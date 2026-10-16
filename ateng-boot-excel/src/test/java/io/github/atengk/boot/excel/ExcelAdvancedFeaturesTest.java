package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.model.DemoUser;
import io.github.atengk.boot.excel.model.FillItemVO;
import io.github.atengk.boot.excel.model.MergeUserVO;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复杂报表填充、单元格合并与多 Sheet 链式构建器集成测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelAdvancedFeaturesTest {

    @Test
    @DisplayName("测试 @ExcelMerge：纵向相邻行相同值单元格自动合并与 dependParent 联动")
    void testExcelMergeVerticalAndParentDependency() throws Exception {
        List<MergeUserVO> list = List.of(
                new MergeUserVO("技术部", "后端研发", "张三", 10000.0),
                new MergeUserVO("技术部", "后端研发", "李四", 12000.0),
                new MergeUserVO("技术部", "前端研发", "王五", 11000.0),
                new MergeUserVO("运营部", "产品运营", "赵六", 9000.0),
                new MergeUserVO("运营部", "产品运营", "钱七", 9500.0)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, MergeUserVO.class);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();
            assertEquals(4, mergedRegions.size(), "应包含 4 个合并区间");

            // 验证部门列（Column 0）合并：技术部（第 1-3 行），运营部（第 4-5 行）
            assertTrue(containsMerge(mergedRegions, 1, 3, 0, 0), "技术部应跨 1-3 行合并");
            assertTrue(containsMerge(mergedRegions, 4, 5, 0, 0), "运营部应跨 4-5 行合并");

            // 验证岗位列（Column 1，dependParent = true）：后端研发（第 1-2 行），产品运营（第 4-5 行）
            assertTrue(containsMerge(mergedRegions, 1, 2, 1, 1), "后端研发应跨 1-2 行合并");
            assertTrue(containsMerge(mergedRegions, 4, 5, 1, 1), "产品运营应跨 4-5 行合并");
        }
    }

    @Test
    @DisplayName("测试 ExcelUtil.fill：模板填充单变量与列表循环并支持复合参数")
    void testExcelTemplateFill() throws Exception {
        // 1. 动态生成内存中的 Excel 模板
        byte[] templateBytes = createTemplateExcelBytes();

        // 2. 准备填充数据
        Map<String, Object> variables = new HashMap<>();
        variables.put("title", "2026年度预算报表");
        variables.put("date", "2026-10-16");
        variables.put("author", "Ateng");

        List<FillItemVO> items = List.of(
                new FillItemVO("高性能服务器", 50000.0),
                new FillItemVO("万兆交换机", 20000.0),
                new FillItemVO("云存储订阅", 8000.0)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.fill(out, new ByteArrayInputStream(templateBytes), "预算表", variables, items, FillConfig.builder().forceNewRow(Boolean.TRUE).build());

        // 3. 断言填充后生成的工作簿
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("预算表");
            assertNotNull(sheet);

            // 验证单对象占位符替换
            Row row0 = sheet.getRow(0);
            assertNotNull(row0);
            assertEquals("标题: 2026年度预算报表", row0.getCell(0).getStringCellValue());

            Row row1 = sheet.getRow(1);
            assertNotNull(row1);
            assertEquals("编制日期: 2026-10-16", row1.getCell(0).getStringCellValue());

            // 验证列表行填充
            Row row3 = sheet.getRow(3);
            assertEquals("高性能服务器", row3.getCell(0).getStringCellValue());
            assertEquals(50000.0, row3.getCell(1).getNumericCellValue(), 0.001);

            Row row4 = sheet.getRow(4);
            assertEquals("万兆交换机", row4.getCell(0).getStringCellValue());
            assertEquals(20000.0, row4.getCell(1).getNumericCellValue(), 0.001);

            Row row5 = sheet.getRow(5);
            assertEquals("云存储订阅", row5.getCell(0).getStringCellValue());
            assertEquals(8000.0, row5.getCell(1).getNumericCellValue(), 0.001);

            // 验证下方被下推的尾部占位符
            Row row6 = sheet.getRow(6);
            assertEquals("编制人: Ateng", row6.getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 ExcelUtil.exportDynamic：无实体动态表头与 Map / 二维 List 导出")
    void testExportDynamic() throws Exception {
        // 1. 测试 List<List<Object>> 动态数据
        List<String> flatHeaders = List.of("工号", "姓名", "薪资");
        List<List<Object>> rowList = List.of(
                List.of(101, "张三", 8500.5),
                List.of(102, "李四", 9200.0)
        );

        ByteArrayOutputStream out1 = new ByteArrayOutputStream();
        ExcelUtil.exportDynamic(out1, "二维列表", flatHeaders, rowList);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out1.toByteArray()))) {
            Sheet sheet = workbook.getSheet("二维列表");
            assertNotNull(sheet);
            assertEquals("工号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("李四", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals(9200.0, sheet.getRow(2).getCell(2).getNumericCellValue(), 0.01);
        }

        // 2. 测试 List<Map<String, Object>> 动态数据
        Map<String, Object> map1 = new LinkedHashMap<>();
        map1.put("工号", 201);
        map1.put("姓名", "王五");
        map1.put("薪资", 12000.0);

        Map<String, Object> map2 = new LinkedHashMap<>();
        map2.put("工号", 202);
        map2.put("姓名", "赵六");
        map2.put("薪资", 15000.0);

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        ExcelUtil.exportDynamic(out2, "Map列表", flatHeaders, List.of(map1, map2));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out2.toByteArray()))) {
            Sheet sheet = workbook.getSheet("Map列表");
            assertNotNull(sheet);
            assertEquals("王五", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals(15000.0, sheet.getRow(2).getCell(2).getNumericCellValue(), 0.01);
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder：多 Sheet 链式流式编排与资源自动释放")
    void testExcelBuilderMultiSheet() throws Exception {
        List<DemoUser> users = List.of(
                DemoUser.builder().id(1L).username("admin").age(28).balance(new BigDecimal("999.50")).createTime(new Date()).build(),
                DemoUser.builder().id(2L).username("guest").age(22).balance(new BigDecimal("100.00")).createTime(new Date()).build()
        );

        List<MergeUserVO> mergeUsers = List.of(
                new MergeUserVO("市场部", "商务经理", "小李", 8000.0),
                new MergeUserVO("市场部", "商务经理", "小王", 8500.0)
        );

        List<String> dynamicHeaders = List.of("类别", "统计数量");
        List<List<Object>> dynamicData = List.of(
                List.of("活跃用户", 1200),
                List.of("新注册用户", 350)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            builder.sheet("用户列表", DemoUser.class, users)
                    .sheet("部门合并", MergeUserVO.class, mergeUsers)
                    .sheetDynamic("动态报表", dynamicHeaders, dynamicData);
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals(3, workbook.getNumberOfSheets(), "应创建 3 个独立工作表");
            assertEquals("用户列表", workbook.getSheetName(0));
            assertEquals("部门合并", workbook.getSheetName(1));
            assertEquals("动态报表", workbook.getSheetName(2));

            // 断言 Sheet 1 实体数据
            Sheet sheet1 = workbook.getSheetAt(0);
            assertEquals("admin", sheet1.getRow(1).getCell(1).getStringCellValue());

            // 断言 Sheet 2 单元格合并已生效
            Sheet sheet2 = workbook.getSheetAt(1);
            assertEquals(2, sheet2.getNumMergedRegions(), "Sheet 2 应包含部门与岗位的 2 个合并区间");

            // 断言 Sheet 3 动态数据
            Sheet sheet3 = workbook.getSheetAt(2);
            assertEquals("类别", sheet3.getRow(0).getCell(0).getStringCellValue());
            assertEquals("活跃用户", sheet3.getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 支持空数据集：仍能完整生成表头与空子表")
    void testExcelBuilderWithEmptyData() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            builder.sheet("空实体表", DemoUser.class, List.of())
                    .sheetDynamic("空动态表", List.of("字段A", "字段B"), List.of());
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            assertEquals(2, workbook.getNumberOfSheets(), "应创建 2 个空表工作簿");
            Sheet sheet1 = workbook.getSheet("空实体表");
            assertNotNull(sheet1);
            assertEquals("用户ID", sheet1.getRow(0).getCell(0).getStringCellValue());

            Sheet sheet2 = workbook.getSheet("空动态表");
            assertNotNull(sheet2);
            assertEquals("字段A", sheet2.getRow(0).getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试 Web 环境下模板填充与动态导出响应头正确性")
    void testWebFillAndExportDynamic() throws Exception {
        byte[] templateBytes = createTemplateExcelBytes();
        MockHttpServletResponse response1 = new MockHttpServletResponse();
        ExcelUtil.fill(response1, "web-template-export", new ByteArrayInputStream(templateBytes), Map.of("title", "在线模板导出"));

        assertTrue(response1.getContentType().contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(response1.getHeader("Content-Disposition").contains("web-template-export.xlsx"));
        assertTrue(response1.getContentAsByteArray().length > 0);

        MockHttpServletResponse response2 = new MockHttpServletResponse();
        ExcelUtil.exportDynamic(response2, "web-dynamic-export", List.of("A", "B"), List.of(List.of(1, 2)));

        assertTrue(response2.getContentType().contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(response2.getHeader("Content-Disposition").contains("web-dynamic-export.xlsx"));
        assertTrue(response2.getContentAsByteArray().length > 0);
    }

    private boolean containsMerge(List<CellRangeAddress> regions, int firstRow, int lastRow, int firstCol, int lastCol) {
        for (CellRangeAddress address : regions) {
            if (address.getFirstRow() == firstRow && address.getLastRow() == lastRow
                    && address.getFirstColumn() == firstCol && address.getLastColumn() == lastCol) {
                return true;
            }
        }
        return false;
    }

    private byte[] createTemplateExcelBytes() throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("预算表");
            sheet.createRow(0).createCell(0).setCellValue("标题: {title}");
            sheet.createRow(1).createCell(0).setCellValue("编制日期: {date}");

            Row head = sheet.createRow(2);
            head.createCell(0).setCellValue("项目");
            head.createCell(1).setCellValue("预算金额");

            Row item = sheet.createRow(3);
            item.createCell(0).setCellValue("{.name}");
            item.createCell(1).setCellValue("{.amount}");

            sheet.createRow(4).createCell(0).setCellValue("编制人: {author}");

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
