package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.model.DemoUser;
import org.apache.fesod.sheet.FesodSheet;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 智能自适应列宽处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class SmartAutoColumnWidthWriteHandlerTest {

    public static class AutoWidthModel {
        @ExcelProperty("编号")
        private Long id;

        @ExcelProperty("用户名")
        private String username;

        @ExcelProperty("账户余额")
        private Double balance;

        @ExcelProperty("超长详细备注信息字段演示")
        private String longDescription;

        public AutoWidthModel() {
        }

        public AutoWidthModel(Long id, String username, Double balance, String longDescription) {
            this.id = id;
            this.username = username;
            this.balance = balance;
            this.longDescription = longDescription;
        }

        public Long getId() {
            return id;
        }

        public String getUsername() {
            return username;
        }

        public Double getBalance() {
            return balance;
        }

        public String getLongDescription() {
            return longDescription;
        }
    }

    @Test
    @DisplayName("测试智能列宽自适应：中英文字符加权、安全内边距与 min/max 阈值保护，并落盘至临时目录")
    void testAutoColumnWidthCalculationAndTempFileOutput() throws IOException {
        String longText = "这是一段非常非常长的详细备注说明文本，用于模拟业务系统中的大段备注、异常堆栈或富文本内容。".repeat(3);
        List<AutoWidthModel> dataList = List.of(
                new AutoWidthModel(1L, "张三", 100.50, "简要描述"),
                new AutoWidthModel(2000000000L, "诸葛孔明先生", 99999999.99, longText)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SmartAutoColumnWidthWriteHandler widthHandler = new SmartAutoColumnWidthWriteHandler(12, 60);

        FesodSheet.write(out, AutoWidthModel.class)
                .registerWriteHandler(widthHandler)
                .sheet("自适应列宽测试")
                .doWrite(dataList);

        byte[] bytes = out.toByteArray();
        assertTrue(bytes.length > 0);

        // 写入系统临时目录供人工核验
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve("test-smart-auto-column-width.xlsx").toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [AutoColumnWidth] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("================================================");

        // 使用 POI 读取并断言列宽
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);

            // 0 编号列：ID虽然较短，但有表头“编号”（4字符宽）和最小列宽12保护，宽度应为 12 * 256
            int col0Width = sheet.getColumnWidth(0);
            assertEquals(12 * 256, col0Width, "第0列应受最小列宽12字符保护");

            // 1 用户名列：“诸葛孔明先生” 6个中文 = 12字符宽，安全内边距 2 = 14，应为 14 * 256
            int col1Width = sheet.getColumnWidth(1);
            assertEquals(14 * 256, col1Width, "第1列应为14字符宽");

            // 3 超长备注列：长文本虽然很长，但受到最大列宽 60 保护，应为 60 * 256
            int col3Width = sheet.getColumnWidth(3);
            assertEquals(60 * 256, col3Width, "第3列超长文本应被截断在最大列宽60字符");
        }
    }
}
