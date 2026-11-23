package io.github.atengk.boot.excel;

import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.excel.annotation.DropdownProvider;
import io.github.atengk.boot.excel.annotation.ExcelColumnLock;
import io.github.atengk.boot.excel.annotation.ExcelDropdown;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.exception.ExcelHeaderValidationException;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.annotation.format.NumberFormat;
import org.apache.fesod.sheet.annotation.write.style.ContentStyle;
import org.apache.fesod.sheet.enums.poi.HorizontalAlignmentEnum;
import org.apache.poi.ss.SpreadsheetVersion;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.AreaReference;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单 #942 垂直切片回归测试：统一列索引解析、无损错误样式克隆与超长下拉隐藏字典表
 *
 * @author Ateng
 * @since 2026-11-13
 */
class ExcelHardeningSlice1Test {

    @AfterEach
    void tearDown() {
        SpringUtil.clear();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ExcelIgnoreUnannotated
    @ExcelColumnLock(password = "123456")
    public static class ReorderedAnnotatedVO {

        private static final String STATIC_CONSTANT = "IGNORE";

        private transient String transientMemo;

        private String unannotatedInternalCode;

        @ExcelIgnore
        @ExcelProperty("显式忽略字段")
        private String explicitlyIgnored;

        @ExcelProperty(value = "所属部门", index = 2)
        @ExcelDropdown({"研发部", "财务部"})
        @NotBlank(message = "所属部门不可为空")
        private String department;

        @ExcelProperty(value = "员工编号", index = 0)
        @ExcelColumnLock(locked = true)
        private Long empId;

        @ExcelProperty(value = "员工年龄", index = 1)
        @Min(value = 18, message = "年龄不能低于 18 岁")
        private Integer age;
    }

    @Test
    @DisplayName("混合 @ExcelIgnoreUnannotated 与乱序 @ExcelProperty(index) 时读写校验、模板下拉与列锁定列索引 100% 一致")
    void shouldKeepColumnIndexConsistentAcrossReadWriteHeaderDropdownAndColumnLock() throws Exception {
        // 1. 构造含非法数据的样本并导出
        ReorderedAnnotatedVO row = new ReorderedAnnotatedVO("memo", "INTERNAL-01", "ignored", "", 1001L, 16);
        ByteArrayOutputStream exportOut = new ByteArrayOutputStream();
        ExcelUtil.export(exportOut, List.of(row), ReorderedAnnotatedVO.class);
        byte[] exportedBytes = exportOut.toByteArray();

        // 2. 验证物理表头顺序并验证 STRICT 表头契约校验通过
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(exportedBytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertEquals("员工编号", header.getCell(0).getStringCellValue());
            assertEquals("员工年龄", header.getCell(1).getStringCellValue());
            assertEquals("所属部门", header.getCell(2).getStringCellValue());
            assertEquals(3, header.getLastCellNum(), "被忽略或未标注字段不应写入 Excel");

            // 验证列锁定位准确（index 0 锁定，index 1、2 解锁）
            Row dataRow = sheet.getRow(1);
            assertTrue(dataRow.getCell(0).getCellStyle().getLocked(), "员工编号 (index=0) 应锁定");
            assertFalse(dataRow.getCell(1).getCellStyle().getLocked(), "员工年龄 (index=1) 应解锁");
            assertFalse(dataRow.getCell(2).getCellStyle().getLocked(), "所属部门 (index=2) 应解锁");
        }

        assertDoesNotThrow(() -> ExcelUtil.validateHeader(
                new ByteArrayInputStream(exportedBytes),
                ReorderedAnnotatedVO.class,
                HeaderValidationStrategy.STRICT
        ), "STRICT 表头校验应跳过 unannotated/ignored 并匹配 index 0,1,2");

        // 3. 验证 JSR-303 校验聚合精准报告 index=1 与 index=2
        ExcelReadResult<ReorderedAnnotatedVO> readResult = ExcelUtil.readWithResult(
                new ByteArrayInputStream(exportedBytes),
                ReorderedAnnotatedVO.class,
                HeaderValidationStrategy.STRICT
        );
        assertTrue(readResult.hasErrors());
        List<ExcelErrorRow> errors = readResult.getErrors();
        assertEquals(2, errors.size());

        ExcelErrorRow ageError = errors.stream()
                .filter(e -> "age".equals(e.getFieldName()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, ageError.getColumnIndex(), "员工年龄 columnIndex 应为 1");
        assertEquals("员工年龄", ageError.getColumnName());

        ExcelErrorRow deptError = errors.stream()
                .filter(e -> "department".equals(e.getFieldName()))
                .findFirst()
                .orElseThrow();
        assertEquals(2, deptError.getColumnIndex(), "所属部门 columnIndex 应为 2");
        assertEquals("所属部门", deptError.getColumnName());

        // 4. 验证模板导出时 @ExcelDropdown 挂载到正确的 columnIndex=2
        ByteArrayOutputStream templateOut = new ByteArrayOutputStream();
        ExcelUtil.exportTemplate(templateOut, ReorderedAnnotatedVO.class);
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(templateOut.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            DataValidation deptValidation = sheet.getDataValidations().stream()
                    .filter(v -> Arrays.stream(v.getRegions().getCellRangeAddresses())
                            .anyMatch(addr -> addr.getFirstColumn() == 2 && addr.getLastColumn() == 2))
                    .findFirst()
                    .orElse(null);
            assertNotNull(deptValidation, "所属部门下拉约束必须精确绑定至列 2");
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StyledFailSplitVO {
        @ExcelProperty("单据编号")
        @ContentStyle(horizontalAlignment = HorizontalAlignmentEnum.CENTER)
        @NotBlank(message = "单据编号不可为空")
        private String orderNo;

        @ExcelProperty("报销金额")
        @NumberFormat("#,##0.00")
        @ContentStyle(horizontalAlignment = HorizontalAlignmentEnum.RIGHT)
        @NotNull
        @DecimalMin(value = "0.01", message = "金额必须大于 0")
        private BigDecimal amount;
    }

    @Test
    @DisplayName("ExcelFeedbackUtil 标红批注回写应无损克隆保留原单元格数字/日期格式与边框字体")
    void shouldPreserveOriginalCellStylesWhenRenderingErrorFeedback() throws Exception {
        // 1. 构造带自定义日期格式、千分位货币格式与边框字体的原始工作簿
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        try (Workbook rawWb = new XSSFWorkbook()) {
            Sheet sheet = rawWb.createSheet("对账单");
            DataFormat dataFormat = rawWb.createDataFormat();

            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("姓名");
            header.createCell(1).setCellValue("入职日期");
            header.createCell(2).setCellValue("薪资金额");

            Font boldFont = rawWb.createFont();
            boldFont.setBold(true);
            CellStyle nameStyle = rawWb.createCellStyle();
            nameStyle.setFont(boldFont);
            nameStyle.setBorderBottom(BorderStyle.THIN);

            CellStyle dateStyle = rawWb.createCellStyle();
            dateStyle.setDataFormat(dataFormat.getFormat("yyyy/MM/dd"));
            dateStyle.setAlignment(HorizontalAlignment.CENTER);

            CellStyle moneyStyle = rawWb.createCellStyle();
            moneyStyle.setDataFormat(dataFormat.getFormat("#,##0.00"));
            moneyStyle.setAlignment(HorizontalAlignment.RIGHT);

            Row row = sheet.createRow(1);
            Cell nameCell = row.createCell(0);
            nameCell.setCellValue("");
            nameCell.setCellStyle(nameStyle);

            Cell dateCell = row.createCell(1);
            dateCell.setCellValue(45500.0);
            dateCell.setCellStyle(dateStyle);

            Cell moneyCell = row.createCell(2);
            moneyCell.setCellValue(-100.5);
            moneyCell.setCellStyle(moneyStyle);

            rawWb.write(rawOut);
        }

        List<ExcelErrorRow> errors = List.of(
                ExcelErrorRow.builder().rowIndex(2).columnIndex(0).message("姓名不能为空").build(),
                ExcelErrorRow.builder().rowIndex(2).columnIndex(1).message("日期不在有效区间").build(),
                ExcelErrorRow.builder().rowIndex(2).columnIndex(2).message("薪资不可为负数").build()
        );

        ByteArrayOutputStream feedbackOut = new ByteArrayOutputStream();
        ExcelUtil.exportErrorFeedback(new ByteArrayInputStream(rawOut.toByteArray()), feedbackOut, errors);

        try (Workbook resultWb = WorkbookFactory.create(new ByteArrayInputStream(feedbackOut.toByteArray()))) {
            Sheet sheet = resultWb.getSheetAt(0);
            Row row = sheet.getRow(1);

            Cell c0 = row.getCell(0);
            assertEquals(IndexedColors.ROSE.getIndex(), c0.getCellStyle().getFillForegroundColor());
            assertTrue(resultWb.getFontAt(c0.getCellStyle().getFontIndex()).getBold(), "原加粗字体应无损保留");
            assertEquals(BorderStyle.THIN, c0.getCellStyle().getBorderBottom(), "原下边框应无损保留");

            Cell c1 = row.getCell(1);
            assertEquals(IndexedColors.ROSE.getIndex(), c1.getCellStyle().getFillForegroundColor());
            assertEquals("yyyy/MM/dd", c1.getCellStyle().getDataFormatString(), "原日期格式应无损保留");
            assertEquals(HorizontalAlignment.CENTER, c1.getCellStyle().getAlignment(), "原居中对齐应无损保留");

            Cell c2 = row.getCell(2);
            assertEquals(IndexedColors.ROSE.getIndex(), c2.getCellStyle().getFillForegroundColor());
            assertEquals("#,##0.00", c2.getCellStyle().getDataFormatString(), "原千分位金额格式应无损保留");
            assertEquals(HorizontalAlignment.RIGHT, c2.getCellStyle().getAlignment(), "原右对齐应无损保留");
        }
    }

    @Test
    @DisplayName("exportFailedRows 同一行多列错误各自无损克隆原单元格格式与对齐方式")
    void shouldPreserveDistinctCellStylesAcrossColumnsInFailSplitExport() throws Exception {
        StyledFailSplitVO badRow = new StyledFailSplitVO("", new BigDecimal("-50.00"));
        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        ExcelUtil.export(rawOut, List.of(badRow), StyledFailSplitVO.class);

        ExcelReadResult<StyledFailSplitVO> readResult = ExcelUtil.readWithResult(
                new ByteArrayInputStream(rawOut.toByteArray()), StyledFailSplitVO.class);
        assertTrue(readResult.hasErrors());

        ByteArrayOutputStream failOut = new ByteArrayOutputStream();
        ExcelUtil.exportFailedRows(failOut, StyledFailSplitVO.class, readResult.getErrors());

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(failOut.toByteArray()))) {
            Sheet sheet = wb.getSheet("纠错清单");
            Row row = sheet.getRow(1);

            Cell orderNoCell = row.getCell(0);
            Cell amountCell = row.getCell(1);

            assertEquals(IndexedColors.ROSE.getIndex(), orderNoCell.getCellStyle().getFillForegroundColor());
            assertEquals(HorizontalAlignment.CENTER, orderNoCell.getCellStyle().getAlignment(), "单据编号列应保留居中对齐");

            assertEquals(IndexedColors.ROSE.getIndex(), amountCell.getCellStyle().getFillForegroundColor());
            assertEquals(HorizontalAlignment.RIGHT, amountCell.getCellStyle().getAlignment(), "报销金额列应保留右对齐，不能被前序出错单元格样式污染");
            assertEquals("#,##0.00", amountCell.getCellStyle().getDataFormatString(), "报销金额列应保留千分位数字格式");
        }
    }

    /**
     * 仅含带参构造器的 DropdownProvider：只能通过 Spring 容器 Bean 获取，无法通过无参构造反射实例化
     */
    public static class SpringManagedCityProvider implements DropdownProvider {
        private final String prefix;

        public SpringManagedCityProvider(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public String[] getOptions() {
            return new String[]{prefix + "北京", prefix + "上海", prefix + "深圳"};
        }
    }

    /**
     * 超 255 字符与超 SXSSF 默认 100 行刷盘窗口的大容量动态下拉选项提供者（130 项）
     */
    public static class LargeCatalogDropdownProvider implements DropdownProvider {
        @Override
        public String[] getOptions() {
            return IntStream.rangeClosed(1, 130)
                    .mapToObj(i -> String.format("一级组织架构及成本中心节点-%03d", i))
                    .toArray(String[]::new);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnterpriseDropdownVO {
        @ExcelProperty("归属成本中心")
        @ExcelDropdown(source = LargeCatalogDropdownProvider.class)
        private String costCenter;

        @ExcelProperty("业务标签(含逗号)")
        @ExcelDropdown({"Platform, Core", "Data, AI"})
        private String commaTag;

        @ExcelProperty("工作城市(Spring Bean)")
        @ExcelDropdown(source = SpringManagedCityProvider.class)
        private String city;
    }

    @Test
    @DisplayName("DropdownProvider 优先从 Spring 容器获取且超 200 字符或含逗号下拉自动创建隐藏字典表")
    void shouldUseSpringBeanProviderAndHiddenSheetForLongOrCommaDropdowns() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("springManagedCityProvider", new SpringManagedCityProvider("中国-"));
            context.refresh();
            SpringUtil.setContext(context);

            try (ExcelBuilder builder = ExcelUtil.builder(out)) {
                builder.sheet("入职模板A", EnterpriseDropdownVO.class, List.of());
                builder.sheet("入职模板B", EnterpriseDropdownVO.class, List.of());
            }
        }

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray()))) {
            // 1. 验证隐藏字典工作表存在且处于隐藏状态
            Sheet dictSheet = wb.getSheet("_ateng_dropdown_dict");
            assertNotNull(dictSheet, "超长或含逗号下拉应自动生成隐藏字典工作表");
            assertTrue(wb.isSheetHidden(wb.getSheetIndex(dictSheet)), "字典工作表必须设为隐藏状态");

            // 2. 验证 Sheet1 三列下拉规则均正确挂载
            Sheet sheetA = wb.getSheet("入职模板A");
            assertNotNull(sheetA);

            DataValidation longDv = findColumnValidation(sheetA, 0);
            assertNotNull(longDv, "成本中心超长下拉必须成功挂载");
            assertEquals(DataValidationConstraint.ValidationType.LIST, longDv.getValidationConstraint().getValidationType());
            String longFormula = longDv.getValidationConstraint().getFormula1();
            assertNotNull(longFormula, "超长下拉应采用命名公式引用而非显式字面量");
            Name longName = wb.getName(longFormula);
            assertNotNull(longName, "工作簿必须注册对应的跨表名称引用: " + longFormula);
            assertTrue(longName.getRefersToFormula().contains("_ateng_dropdown_dict"));

            AreaReference areaRef = new AreaReference(longName.getRefersToFormula(), SpreadsheetVersion.EXCEL2007);
            CellReference[] refs = areaRef.getAllReferencedCells();
            assertEquals(130, refs.length, "隐藏字典工作表名称引用应完整覆盖全部 130 个选项（跨越 SXSSF 默认 100 行窗口）");
            assertEquals("一级组织架构及成本中心节点-001", dictSheet.getRow(refs[0].getRow()).getCell(refs[0].getCol()).getStringCellValue());
            assertEquals("一级组织架构及成本中心节点-130", dictSheet.getRow(refs[129].getRow()).getCell(refs[129].getCol()).getStringCellValue());

            DataValidation commaDv = findColumnValidation(sheetA, 1);
            assertNotNull(commaDv, "包含英文逗号的下拉选项必须走隐藏字典表公式防拆分");
            String commaFormula = commaDv.getValidationConstraint().getFormula1();
            Name commaName = wb.getName(commaFormula);
            assertNotNull(commaName);
            assertNotEquals(longName.getRefersToFormula(), commaName.getRefersToFormula(), "不同下拉列不能覆盖同一字典行区");

            DataValidation cityDv = findColumnValidation(sheetA, 2);
            assertNotNull(cityDv, "Spring Bean DropdownProvider 必须成功解析并生成下拉约束");
            assertArrayEquals(new String[]{"中国-北京", "中国-上海", "中国-深圳"},
                    cityDv.getValidationConstraint().getExplicitListValues());

            // 3. 验证同工作簿第二个 Sheet 复用同一隐藏字典表且独立引用范围不冲突
            Sheet sheetB = wb.getSheet("入职模板B");
            DataValidation longDvB = findColumnValidation(sheetB, 0);
            assertNotNull(longDvB);
            Name longNameB = wb.getName(longDvB.getValidationConstraint().getFormula1());
            assertNotNull(longNameB);
            assertNotEquals(longName.getRefersToFormula(), longNameB.getRefersToFormula());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SparseIndexVO {
        @ExcelProperty(value = "第一列", index = 0)
        private String firstCol;

        @ExcelProperty(value = "第三列", index = 2)
        private String thirdCol;
    }

    @Test
    @DisplayName("STRICT 表头校验兼容稀疏 @ExcelProperty(index) 且精确拦截间隙冗余列")
    void shouldValidateSparseIndexHeaderAndRejectGapRedundantColumnInStrictMode() throws Exception {
        ByteArrayOutputStream validOut = new ByteArrayOutputStream();
        ExcelUtil.export(validOut, List.of(new SparseIndexVO("A", "C")), SparseIndexVO.class);

        assertDoesNotThrow(() -> ExcelUtil.validateHeader(
                new ByteArrayInputStream(validOut.toByteArray()),
                SparseIndexVO.class,
                HeaderValidationStrategy.STRICT
        ), "稀疏 index=0 和 index=2 表头应通过 STRICT 校验");

        // 在第 2 列（index=1 间隙）注入非预期冗余表头，应触发 STRICT 拦截
        ByteArrayOutputStream tamperedOut = new ByteArrayOutputStream();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(validOut.toByteArray()))) {
            wb.getSheetAt(0).getRow(0).createCell(1).setCellValue("多余间隙列");
            wb.write(tamperedOut);
        }

        ExcelHeaderValidationException ex = assertThrows(ExcelHeaderValidationException.class, () -> ExcelUtil.validateHeader(
                new ByteArrayInputStream(tamperedOut.toByteArray()),
                SparseIndexVO.class,
                HeaderValidationStrategy.STRICT
        ));
        assertTrue(ex.getMessage().contains("多余间隙列"));
    }

    private DataValidation findColumnValidation(Sheet sheet, int columnIndex) {
        return sheet.getDataValidations().stream()
                .filter(v -> Arrays.stream(v.getRegions().getCellRangeAddresses())
                        .anyMatch(addr -> addr.getFirstColumn() == columnIndex && addr.getLastColumn() == columnIndex))
                .findFirst()
                .orElse(null);
    }
}
