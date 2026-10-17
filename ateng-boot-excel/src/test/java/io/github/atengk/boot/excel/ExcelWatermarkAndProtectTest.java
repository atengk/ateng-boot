package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.util.ExcelUtil;
import io.github.atengk.boot.excel.util.ExcelWatermarkUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 防拍照半透明暗水印与工作表只读保护集成测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class ExcelWatermarkAndProtectTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ConfidentialDataVO {
        @ExcelProperty("员工编号")
        private String employeeId;

        @ExcelProperty("员工姓名")
        private String name;

        @ExcelProperty("月度薪资")
        private Double salary;
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [WatermarkTest] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("==============================================");
    }

    @Test
    @DisplayName("测试 ExcelWatermarkUtil 动态生成半透明防伪切片")
    void testWatermarkUtilPngGeneration() {
        byte[] pngBytes = ExcelWatermarkUtil.createWatermarkImage("内部绝密 · 张三 1001");
        assertNotNull(pngBytes);
        assertTrue(pngBytes.length > 0);

        // 验证 PNG 规范魔数 (Magic Number: 0x89 0x50 0x4E 0x47)
        assertEquals((byte) 0x89, pngBytes[0]);
        assertEquals((byte) 0x50, pngBytes[1]);
        assertEquals((byte) 0x4E, pngBytes[2]);
        assertEquals((byte) 0x47, pngBytes[3]);
    }

    @Test
    @DisplayName("测试 ExcelUtil.exportWithWatermark 导出全屏暗水印与密码只读锁定")
    void testExportWithWatermarkAndProtect() throws Exception {
        List<ConfidentialDataVO> list = new ArrayList<>();
        list.add(new ConfidentialDataVO("E1001", "张三", 18500.0));
        list.add(new ConfidentialDataVO("E1002", "李四", 22000.0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.exportWithWatermark(
                out,
                "机密薪资表",
                list,
                ConfidentialDataVO.class,
                "机密 · 张三 1001 · 2026-10-17",
                "pwd123456"
        );
        byte[] excelBytes = out.toByteArray();
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 0);

        saveTempExcel("test-watermark-confidential.xlsx", excelBytes);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheet("机密薪资表");
            assertNotNull(sheet);

            // 验证工作表只读保护已激活
            assertTrue(sheet.getProtect(), "工作表应已被密码锁定保护");

            // 验证背景水印图片已被注入工作簿图片表
            assertFalse(workbook.getAllPictures().isEmpty(), "工作簿应包含注入的水印图片切片");
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 链式按 Sheet 独立配置水印与只读保护")
    void testBuilderWatermarkAndProtect() throws Exception {
        List<ConfidentialDataVO> list = new ArrayList<>();
        list.add(new ConfidentialDataVO("E1001", "张三", 18500.0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 1: 机密表，带有暗水印和密码保护
            builder.watermark("财务内参 · 2026-10-17")
                   .protect("financeSecret")
                   .sheet("机密Sheet", ConfidentialDataVO.class, list);

            // Sheet 2: 公开表，不设置水印和保护
            builder.sheet("公开Sheet", ConfidentialDataVO.class, list);
        }

        byte[] excelBytes = out.toByteArray();
        saveTempExcel("test-builder-watermark-multi-sheet.xlsx", excelBytes);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet confidentialSheet = workbook.getSheet("机密Sheet");
            assertNotNull(confidentialSheet);
            assertTrue(confidentialSheet.getProtect(), "机密Sheet 应开启密码保护");

            Sheet publicSheet = workbook.getSheet("公开Sheet");
            assertNotNull(publicSheet);
            assertFalse(publicSheet.getProtect(), "公开Sheet 应保持未锁定状态");
        }
    }
}
