package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.annotation.ExcelFormulaAllowed;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 公式注入安全防御 (CWE-1236) 与白名单豁免集成测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class ExcelFormulaProtectionTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InjectionTestDataVO {

        @ExcelProperty("公式注入字符串")
        private String injectionContent;

        @ExcelProperty("白名单合法公式")
        @ExcelFormulaAllowed
        private String allowedFormula;

        @ExcelProperty("普通业务文本")
        private String normalText;
    }

    @BeforeEach
    void setUp() {
        ExcelProperties properties = new ExcelProperties();
        properties.getSecurity().setFormulaProtection(true);
        ExcelUtil.initProperties(properties);
    }

    @AfterEach
    void tearDown() {
        ExcelProperties properties = new ExcelProperties();
        properties.getSecurity().setFormulaProtection(true);
        ExcelUtil.initProperties(properties);
    }

    @Test
    @DisplayName("测试导出端公式前缀自动转译与 @ExcelFormulaAllowed 白名单豁免")
    void testExportFormulaProtection() throws Exception {
        List<InjectionTestDataVO> list = new ArrayList<>();
        list.add(new InjectionTestDataVO("=cmd|'/C calc'!A0", "=SUM(A1:B1)", "正常文本1"));
        list.add(new InjectionTestDataVO("+8613800000000", "=AVERAGE(C1:C10)", "正常文本2"));
        list.add(new InjectionTestDataVO("-5000", "+A1+B1", "正常文本3"));
        list.add(new InjectionTestDataVO("@Admin", "@SUM(1,2)", "正常文本4"));
        list.add(new InjectionTestDataVO("\tcmd", "\t合法制表符", "正常文本5"));
        list.add(new InjectionTestDataVO("\rcmd", "\r合法回车", "正常文本6"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, InjectionTestDataVO.class, list);
        byte[] excelBytes = out.toByteArray();
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 0);

        // 使用 POI 底层验证写出的单元格原始字符串是否前置了单引号保护
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            // Row 1 (Header = Row 0, Data = Row 1)
            Row row1 = sheet.getRow(1);
            Cell col0 = row1.getCell(0);
            Cell col1 = row1.getCell(1);
            Cell col2 = row1.getCell(2);

            // col 0 必须被单引号转译保护
            assertEquals("'=cmd|'/C calc'!A0", col0.getStringCellValue());
            // col 1 标注了 @ExcelFormulaAllowed，必须保持原始公式字符串，绝不加单引号
            assertEquals("=SUM(A1:B1)", col1.getStringCellValue());
            // col 2 普通文本不加单引号
            assertEquals("正常文本1", col2.getStringCellValue());

            // Row 2 (+86 手机号)
            Row row2 = sheet.getRow(2);
            assertEquals("'+8613800000000", row2.getCell(0).getStringCellValue());
            assertEquals("=AVERAGE(C1:C10)", row2.getCell(1).getStringCellValue());

            // Row 3 (-5000 负数文本)
            Row row3 = sheet.getRow(3);
            assertEquals("'-5000", row3.getCell(0).getStringCellValue());

            // Row 4 (@Admin)
            Row row4 = sheet.getRow(4);
            assertEquals("'@Admin", row4.getCell(0).getStringCellValue());

            // Row 5 (\tcmd)
            Row row5 = sheet.getRow(5);
            assertEquals("'\tcmd", row5.getCell(0).getStringCellValue());

            // Row 6 (\rcmd)
            Row row6 = sheet.getRow(6);
            assertEquals("'\rcmd", row6.getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试导入端对称反转译脱去单引号，严格守卫“内存真明文”不变量")
    void testImportFormulaUnescapingSymmetry() {
        List<InjectionTestDataVO> exportList = new ArrayList<>();
        exportList.add(new InjectionTestDataVO("=cmd|'/C calc'!A0", "=SUM(A1:B1)", "正常文本1"));
        exportList.add(new InjectionTestDataVO("+8613800000000", "+A1+B1", "正常文本2"));
        exportList.add(new InjectionTestDataVO("-5000", "-A1", "正常文本3"));
        exportList.add(new InjectionTestDataVO("@Admin", "@Admin", "正常文本4"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, InjectionTestDataVO.class, exportList);

        // 导入读取并验证内存实体对象为原始真明文（无前置单引号）
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<InjectionTestDataVO> readList = ExcelUtil.read(in, InjectionTestDataVO.class);

        assertEquals(4, readList.size());
        assertEquals("=cmd|'/C calc'!A0", readList.get(0).getInjectionContent());
        assertEquals("=SUM(A1:B1)", readList.get(0).getAllowedFormula());
        assertEquals("正常文本1", readList.get(0).getNormalText());

        assertEquals("+8613800000000", readList.get(1).getInjectionContent());
        assertEquals("-5000", readList.get(2).getInjectionContent());
        assertEquals("@Admin", readList.get(3).getInjectionContent());
    }

    @Test
    @DisplayName("测试无实体动态导出公式防护生效")
    void testDynamicExportFormulaProtection() throws Exception {
        List<String> headers = List.of("指令", "普通");
        List<List<Object>> data = List.of(
                List.of("=2+5", "hello"),
                List.of("+123", "world")
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.exportDynamic(out, headers, data);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row1 = sheet.getRow(1);
            assertEquals("'=2+5", row1.getCell(0).getStringCellValue());
            assertEquals("hello", row1.getCell(1).getStringCellValue());

            Row row2 = sheet.getRow(2);
            assertEquals("'+123", row2.getCell(0).getStringCellValue());
        }
    }

    @Test
    @DisplayName("测试全局配置关闭公式防护时原样输出")
    void testConfigDisableFormulaProtection() throws Exception {
        ExcelProperties properties = new ExcelProperties();
        properties.getSecurity().setFormulaProtection(false);
        ExcelUtil.initProperties(properties);

        List<InjectionTestDataVO> list = List.of(
                new InjectionTestDataVO("=SUM(1,2)", "=SUM(3,4)", "文本")
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, InjectionTestDataVO.class, list);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Row row1 = sheet.getRow(1);
            // 关闭防护后不前置单引号
            assertEquals("=SUM(1,2)", row1.getCell(0).getStringCellValue());
        }
    }
}
