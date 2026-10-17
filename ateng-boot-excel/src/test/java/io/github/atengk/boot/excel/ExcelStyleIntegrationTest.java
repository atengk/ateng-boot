package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.write.style.ContentStyle;
import org.apache.fesod.sheet.enums.poi.HorizontalAlignmentEnum;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.BeforeAll;
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
 * Excel 企业级样式、主题覆盖与列宽端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class ExcelStyleIntegrationTest {

    @BeforeAll
    static void initGlobalConfig() {
        ExcelProperties properties = new ExcelProperties();
        properties.getStyle().setEnabled(true);
        properties.getStyle().setTheme("minimal-clean");
        properties.getStyle().setZebraStripe(true);
        properties.getStyle().setAutoColumnWidth(true);
        properties.getStyle().setMinColumnWidth(12);
        properties.getStyle().setMaxColumnWidth(60);
        properties.getStyle().setFontName("微软雅黑");
        ExcelUtil.initProperties(properties);
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [IntegrationTest] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("================================================");
    }

    // 默认模型（无注解，继承全局配置）
    public static class DefaultUserModel {
        @ExcelProperty("编号")
        private Long id;

        @ExcelProperty("用户名")
        private String username;

        @ExcelProperty("金额")
        private Double amount;

        public DefaultUserModel(Long id, String username, Double amount) {
            this.id = id;
            this.username = username;
            this.amount = amount;
        }

        public Long getId() { return id; }
        public String getUsername() { return username; }
        public Double getAmount() { return amount; }
    }

    // 注解定制模型：绿色主题 + 金额列右对齐
    @ExcelTheme(theme = ExcelThemeEnum.FRESH_GREEN, zebraStripe = true)
    public static class AnnotatedGreenModel {
        @ExcelProperty("订单号")
        private Long orderId;

        @ExcelProperty("商品名称")
        private String productName;

        @ExcelProperty("结算金额")
        @ContentStyle(horizontalAlignment = HorizontalAlignmentEnum.RIGHT)
        private Double price;

        public AnnotatedGreenModel(Long orderId, String productName, Double price) {
            this.orderId = orderId;
            this.productName = productName;
            this.price = price;
        }

        public Long getOrderId() { return orderId; }
        public String getProductName() { return productName; }
        public Double getPrice() { return price; }
    }

    // 注解关闭样式模型
    @ExcelTheme(enabled = false)
    public static class DisabledStyleModel {
        @ExcelProperty("编码")
        private String code;

        public DisabledStyleModel(String code) { this.code = code; }
        public String getCode() { return code; }
    }

    // 注解字符串快捷主题模型（使用 value 属性）
    @ExcelTheme("warm-orange")
    public static class AnnotatedValueOrangeModel {
        @ExcelProperty("活动代号")
        private String code;

        public AnnotatedValueOrangeModel(String code) { this.code = code; }
        public String getCode() { return code; }
    }

    @Test
    @DisplayName("测试全局默认样式：自动挂载极简白(MINIMAL_CLEAN)与自适应列宽")
    void testGlobalDefaultStyleExport() throws IOException {
        List<DefaultUserModel> list = List.of(
                new DefaultUserModel(1L, "张三", 100.0),
                new DefaultUserModel(2L, "李四丰", 200.5)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, DefaultUserModel.class);

        byte[] bytes = out.toByteArray();
        saveTempExcel("test-global-minimal-clean.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);

            // 验证表头无背景填充，保留细边框
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertEquals(FillPatternType.NO_FILL, headStyle.getFillPattern(), "极简白表头应无背景填充");
            assertEquals(BorderStyle.THIN, headStyle.getBorderTop(), "极简白表头应具备细实线边框");

            // 验证第0列“编号”受最小列宽12保护
            assertEquals(12 * 256, sheet.getColumnWidth(0));
        }
    }

    @Test
    @DisplayName("测试实体注解覆盖为清新绿主题，并保持原生 @ContentStyle 金额右对齐")
    void testAnnotatedGreenThemeWithNativeAnnotation() throws IOException {
        List<AnnotatedGreenModel> list = List.of(
                new AnnotatedGreenModel(10001L, "高效人体工学椅", 1299.00),
                new AnnotatedGreenModel(10002L, "4K超高清显示器", 2499.50)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, AnnotatedGreenModel.class);

        byte[] bytes = out.toByteArray();
        saveTempExcel("test-annotated-fresh-green.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);

            // 验证表头为 LIGHT_GREEN 绿色
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertEquals(IndexedColors.LIGHT_GREEN.getIndex(), headStyle.getFillForegroundColor());

            // 验证金额列右对齐
            CellStyle priceStyle = sheet.getRow(1).getCell(2).getCellStyle();
            assertEquals(HorizontalAlignment.RIGHT, priceStyle.getAlignment());
        }
    }

    @Test
    @DisplayName("测试实体注解关闭样式：@ExcelTheme(enabled = false)")
    void testAnnotatedDisabledStyle() throws IOException {
        List<DisabledStyleModel> list = List.of(new DisabledStyleModel("A-001"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, DisabledStyleModel.class);

        byte[] bytes = out.toByteArray();
        saveTempExcel("test-style-disabled.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            // 关闭样式后，表头不会是商务蓝 PALE_BLUE，而是 Fesod 原生灰或无背景
            assertNotEquals(IndexedColors.PALE_BLUE.getIndex(), headStyle.getFillForegroundColor());
        }
    }

    @Test
    @DisplayName("测试实体注解字符串主题：@ExcelTheme(\"warm-orange\") 快捷属性")
    void testAnnotatedValueStringTheme() throws IOException {
        List<AnnotatedValueOrangeModel> list = List.of(new AnnotatedValueOrangeModel("SPRING_SALE"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, list, AnnotatedValueOrangeModel.class);

        byte[] bytes = out.toByteArray();
        saveTempExcel("test-annotated-value-orange.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            CellStyle headStyle = sheet.getRow(0).getCell(0).getCellStyle();
            assertEquals(IndexedColors.LIGHT_ORANGE.getIndex(), headStyle.getFillForegroundColor());
        }
    }

    @Test
    @DisplayName("测试 ExcelBuilder 链式指定主题与列宽：多 Sheet 独立样式")
    void testExcelBuilderFluentStyling() throws IOException {
        List<DefaultUserModel> users = List.of(new DefaultUserModel(1L, "小明", 50.0));
        List<AnnotatedGreenModel> goods = List.of(new AnnotatedGreenModel(999L, "键盘", 199.0));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExcelBuilder builder = ExcelUtil.builder(out)) {
            // Sheet 1: 显式链式设为暖阳橙
            builder.theme(ExcelThemeEnum.WARM_ORANGE)
                    .autoColumnWidth(15, 50)
                    .sheet("用户清单", DefaultUserModel.class, users);

            // Sheet 2: 使用自身注解覆盖为清新绿
            builder.sheet("商品清单", AnnotatedGreenModel.class, goods);
        }

        byte[] bytes = out.toByteArray();
        saveTempExcel("test-builder-multi-theme.xlsx", bytes);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet1 = wb.getSheetAt(0);
            assertEquals(IndexedColors.LIGHT_ORANGE.getIndex(), sheet1.getRow(0).getCell(0).getCellStyle().getFillForegroundColor());
            assertEquals(15 * 256, sheet1.getColumnWidth(0), "第1列受最小列宽15保护");

            Sheet sheet2 = wb.getSheetAt(1);
            assertEquals(IndexedColors.LIGHT_GREEN.getIndex(), sheet2.getRow(0).getCell(0).getCellStyle().getFillForegroundColor());
        }
    }
}
