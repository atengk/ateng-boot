package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.handler.ColumnLockSheetWriteHandler;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 列级防呆只读锁定与工作表保护 (@ExcelColumnLock) 集成测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class ExcelColumnLockTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ExcelColumnLock(password = "lock123456")
    public static class TemplateImportVO {

        @ExcelProperty(value = "系统主键", index = 0)
        @ExcelColumnLock(locked = true)
        private Long id;

        @ExcelProperty(value = "用户名称", index = 1)
        @ExcelColumnLock(locked = false) // 显式允许编辑
        private String username;

        @ExcelProperty(value = "部门编码", index = 2)
        @ExcelColumnLock(locked = true)
        private String deptCode;

        @ExcelProperty(value = "联系电话", index = 3)
        // 未显式标注注解，默认根据规则属于非锁定列
        private String phone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UnlockedClassVO {

        @ExcelProperty("字段1")
        private String f1;

        @ExcelProperty("字段2")
        private String f2;
    }

    @Test
    @DisplayName("测试 hasColumnLock 元数据解析与识别")
    void testHasColumnLock() {
        assertTrue(ColumnLockSheetWriteHandler.hasColumnLock(TemplateImportVO.class));
        assertFalse(ColumnLockSheetWriteHandler.hasColumnLock(UnlockedClassVO.class));
        assertFalse(ColumnLockSheetWriteHandler.hasColumnLock(null));
    }

    @Test
    @DisplayName("测试导出带有列锁的数据表（验证工作表保护与单元格锁定状态）")
    void testExportWithColumnLock() throws Exception {
        List<TemplateImportVO> list = new ArrayList<>();
        list.add(new TemplateImportVO(1001L, "张三", "D01", "13800000001"));
        list.add(new TemplateImportVO(1002L, "李四", "D02", "13800000002"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, TemplateImportVO.class, list);
        byte[] bytes = out.toByteArray();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            // 1. 验证工作表只读保护已激活
            assertTrue(sheet.getProtect(), "工作表必须已激活保护");

            // 2. 验证行数据中指定列的锁定与解锁状态
            Row row1 = sheet.getRow(1);
            assertNotNull(row1);

            Cell cell0 = row1.getCell(0); // id (locked = true)
            Cell cell1 = row1.getCell(1); // username (locked = false)
            Cell cell2 = row1.getCell(2); // deptCode (locked = true)
            Cell cell3 = row1.getCell(3); // phone (unlocked)

            assertTrue(cell0.getCellStyle().getLocked(), "列 0 (ID) 必须锁定只读");
            assertFalse(cell1.getCellStyle().getLocked(), "列 1 (用户名) 必须解锁允许用户输入");
            assertTrue(cell2.getCellStyle().getLocked(), "列 2 (部门编码) 必须锁定只读");
            assertFalse(cell3.getCellStyle().getLocked(), "列 3 (电话) 必须解锁允许用户输入");
        }
    }

    @Test
    @DisplayName("测试 exportTemplate 生成防呆空白导入模板")
    void testExportTemplateWithColumnLock() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.exportTemplate(out, TemplateImportVO.class);
        byte[] bytes = out.toByteArray();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            // 验证工作表已保护
            assertTrue(sheet.getProtect(), "模板工作表必须已激活保护");

            // 验证非锁定列拥有默认解锁列样式
            assertNotNull(sheet.getColumnStyle(1), "非锁定列应具备默认列样式");
            assertFalse(sheet.getColumnStyle(1).getLocked(), "非锁定列默认列样式应为解锁状态");
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 链式构建中列锁生效")
    void testBuilderWithColumnLock() throws Exception {
        List<TemplateImportVO> list = List.of(
                new TemplateImportVO(1L, "王五", "D03", "13900000000")
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            builder.sheet("模板Sheet", TemplateImportVO.class, list);
        }

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheet("模板Sheet");
            assertNotNull(sheet);
            assertTrue(sheet.getProtect());

            Row row1 = sheet.getRow(1);
            assertTrue(row1.getCell(0).getCellStyle().getLocked());
            assertFalse(row1.getCell(1).getCellStyle().getLocked());
        }
    }
}
