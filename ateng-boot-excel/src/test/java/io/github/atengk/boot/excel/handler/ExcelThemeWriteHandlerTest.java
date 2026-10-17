package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.*;
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
 * 企业级主题与斑马纹处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class ExcelThemeWriteHandlerTest {

    public static class SampleUser {
        @ExcelProperty("用户ID")
        private Long id;

        @ExcelProperty("姓名")
        private String name;

        @ExcelProperty("部门")
        private String department;

        public SampleUser() {
        }

        public SampleUser(Long id, String name, String department) {
            this.id = id;
            this.name = name;
            this.department = department;
        }

        public Long getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getDepartment() {
            return department;
        }
    }

    private List<SampleUser> createSampleUsers() {
        return List.of(
                new SampleUser(1001L, "张三", "研发部"),
                new SampleUser(1002L, "李四", "市场部"),
                new SampleUser(1003L, "王五", "财务部"),
                new SampleUser(1004L, "赵六", "运营部")
        );
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [ThemeDemo] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("==========================================");
    }

    @Test
    @DisplayName("测试商务蓝主题(BUSINESS_BLUE)导出：表头底色、边框、字体与正文斑马纹交替")
    void testBusinessBlueThemeWithZebraStripe() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelThemeWriteHandler themeHandler = new ExcelThemeWriteHandler(ExcelThemeEnum.BUSINESS_BLUE, true, "微软雅黑");

        FesodSheet.write(out, SampleUser.class)
                .registerWriteHandler(themeHandler)
                .sheet("商务蓝主题")
                .doWrite(createSampleUsers());

        byte[] bytes = out.toByteArray();
        saveTempExcel("demo-business-blue.xlsx", bytes);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);

            // 验证表头样式 (Row 0)
            Row headRow = sheet.getRow(0);
            Cell headCell = headRow.getCell(0);
            CellStyle headStyle = headCell.getCellStyle();
            assertEquals(IndexedColors.PALE_BLUE.getIndex(), headStyle.getFillForegroundColor(), "表头背景色应为 PALE_BLUE");
            assertEquals(BorderStyle.THIN, headStyle.getBorderTop(), "表头应有 THIN 边框");

            Font headFont = workbook.getFontAt(headStyle.getFontIndex());
            assertTrue(headFont.getBold(), "表头字体应加粗");
            assertEquals("微软雅黑", headFont.getFontName(), "表头字体应为微软雅黑");

            // 验证正文斑马纹 (Row 1 vs Row 2)
            Row row1 = sheet.getRow(1);
            Row row2 = sheet.getRow(2);
            CellStyle style1 = row1.getCell(0).getCellStyle();
            CellStyle style2 = row2.getCell(0).getCellStyle();

            // 偶数行纯白，奇数行微底纹交替
            assertNotEquals(style1.getFillForegroundColor(), style2.getFillForegroundColor(), "第1行与第2行正文应具备交替斑马纹背景");
        }
    }

    @Test
    @DisplayName("测试石墨灰主题高对比度与极简白默认样式")
    void testClassicGreyContrastAndMinimalClean() throws IOException {
        // 1. 验证石墨灰 (CLASSIC_GREY - 方案 B-2)：厚重灰表头(#B0B7C0) + 黑色加粗文字 + 清晰浅灰斑马纹(#DEE2E6)
        ByteArrayOutputStream outGrey = new ByteArrayOutputStream();
        ExcelThemeWriteHandler greyHandler = new ExcelThemeWriteHandler(ExcelThemeEnum.CLASSIC_GREY, true, "微软雅黑");
        FesodSheet.write(outGrey, SampleUser.class).registerWriteHandler(greyHandler).sheet("石墨灰").doWrite(createSampleUsers());
        byte[] greyBytes = outGrey.toByteArray();
        saveTempExcel("demo-theme-classic_grey.xlsx", greyBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(greyBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertTrue(headStyle instanceof org.apache.poi.xssf.usermodel.XSSFCellStyle);
            org.apache.poi.xssf.usermodel.XSSFCellStyle xssfHead = (org.apache.poi.xssf.usermodel.XSSFCellStyle) headStyle;
            byte[] headRgb = xssfHead.getFillForegroundColorColor().getRGB();
            assertEquals((byte) 0xB0, headRgb[0], "表头 R 应为 0xB0 (176)");
            assertEquals((byte) 0xB7, headRgb[1], "表头 G 应为 0xB7 (183)");
            assertEquals((byte) 0xC0, headRgb[2], "表头 B 应为 0xC0 (192)");

            Font headFont = wb.getFontAt(headStyle.getFontIndex());
            assertTrue(headFont.getBold(), "表头字体应加粗");

            CellStyle zebraStyle = sheet.getRow(2).getCell(0).getCellStyle();
            org.apache.poi.xssf.usermodel.XSSFCellStyle xssfZebra = (org.apache.poi.xssf.usermodel.XSSFCellStyle) zebraStyle;
            byte[] zebraRgb = xssfZebra.getFillForegroundColorColor().getRGB();
            assertEquals((byte) 0xDE, zebraRgb[0], "斑马纹 R 应为 0xDE (222)");
            assertEquals((byte) 0xE2, zebraRgb[1], "斑马纹 G 应为 0xE2 (226)");
            assertEquals((byte) 0xE6, zebraRgb[2], "斑马纹 B 应为 0xE6 (230)");
        }

        // 2. 验证极简白 (MINIMAL_CLEAN)：表头无底色加粗 + 细实线边框
        ByteArrayOutputStream outClean = new ByteArrayOutputStream();
        ExcelThemeWriteHandler cleanHandler = new ExcelThemeWriteHandler(ExcelThemeEnum.MINIMAL_CLEAN, true, "微软雅黑");
        FesodSheet.write(outClean, SampleUser.class).registerWriteHandler(cleanHandler).sheet("极简白").doWrite(createSampleUsers());
        byte[] cleanBytes = outClean.toByteArray();
        saveTempExcel("demo-theme-minimal_clean.xlsx", cleanBytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(cleanBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertEquals(FillPatternType.NO_FILL, headStyle.getFillPattern(), "极简白表头应无背景填充");
            assertEquals(BorderStyle.THIN, headStyle.getBorderTop(), "极简白表头应保持细实线边框");
        }
    }
}
