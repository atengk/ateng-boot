package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.model.ExcelRowAware;
import io.github.atengk.boot.excel.model.ExcelRowData;
import io.github.atengk.boot.excel.util.ExcelColumnMetadataResolver;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工单 #1167 物理行号追踪、父子类同名字段元数据消歧与业务错误便捷构建单元测试
 *
 * @author Ateng
 * @since 2026-11-23
 */
@DisplayName("Excel 物理行号追踪与错误行便捷构建单元测试 (#1167)")
class ExcelRowTrackingAndErrorFactoryTest {

    @Data
    public static class BaseImportRow {
        @ExcelProperty("基类工号")
        private String code;

        @ExcelProperty("姓名")
        private String name;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ShadowedChildImportRow extends BaseImportRow {
        @ExcelProperty("子类工号")
        private String code;

        @ExcelIgnore
        private String name;

        @ExcelProperty("部门")
        private String department;
    }

    @Test
    @DisplayName("1. ExcelColumnMetadataResolver 正确消歧父子类同名字段遮蔽与 @ExcelIgnore 覆盖")
    void shouldResolveParentChildShadowedFieldsWithoutDuplication() {
        ExcelColumnMetadataResolver.EntityColumnMetadata meta =
                ExcelColumnMetadataResolver.resolve(ShadowedChildImportRow.class);

        // name 被子类标注 @ExcelIgnore 应剔除；code 被子类覆盖应仅保留 1 列且标题为"子类工号"
        assertEquals(2, meta.columns().size());
        assertEquals("子类工号", meta.getColumnByFieldName("code").leafHeaderName());
        assertEquals(0, meta.getColumnByFieldName("code").columnIndex());
        assertNull(meta.getColumnByFieldName("name"));
        assertEquals("部门", meta.getColumnByFieldName("department").leafHeaderName());
        assertEquals(1, meta.getColumnByFieldName("department").columnIndex());
    }

    @Test
    @DisplayName("2. ExcelRowData 1-based 行号不变量校验与 ExcelRowAware 默认契约验证")
    void shouldEnforcePositiveRowIndexAndExcelRowAwareContract() {
        ExcelRowData<String> rowData = new ExcelRowData<>(3, "payload");
        assertEquals(3, rowData.rowIndex());
        assertEquals("payload", rowData.data());

        assertThrows(IllegalArgumentException.class, () -> new ExcelRowData<>(0, "bad"));
        assertThrows(IllegalArgumentException.class, () -> new ExcelRowData<>(-2, "bad"));

        ExcelRowAware awareOnlySetter = idx -> {};
        assertDoesNotThrow(() -> awareOnlySetter.setRowIndex(2));
        assertNull(awareOnlySetter.getRowIndex());
    }

    @Data
    public static class MultiHeaderAwareImportRow implements ExcelRowAware {
        @ExcelIgnore
        private Integer rowIndex;

        @ExcelProperty({"员工基础信息", "工号"})
        @NotBlank(message = "工号不能为空")
        private String code;

        @ExcelProperty({"员工基础信息", "手机号"})
        @NotBlank(message = "手机号不能为空")
        private String phone;

        @ExcelProperty({"员工基础信息", "年龄"})
        @Min(value = 18, message = "年龄必须大于等于 18")
        private Integer age;
    }

    @Test
    @DisplayName("3. 多级表头 + 跳过空行 + 前序失败场景下，精确保留有效行 1-based 物理行号及回填 ExcelRowAware")
    void shouldTrackExactOneBasedPhysicalRowIndexAcrossMultiHeaderBlankAndInvalidRows() throws Exception {
        byte[] workbookBytes = buildMultiHeaderMixedWorkbook();

        ExcelReadResult<MultiHeaderAwareImportRow> result = ExcelUtil.readWithResult(
                new ByteArrayInputStream(workbookBytes), MultiHeaderAwareImportRow.class);

        // 合法数据集合兼容不变
        assertEquals(2, result.getData().size());
        assertEquals("E001", result.getData().get(0).getCode());
        assertEquals("E003", result.getData().get(1).getCode());

        // 验证 ExcelRowAware 对合法行与失败行均准确回填 1-based 物理行号
        assertEquals(3, result.getData().get(0).getRowIndex());
        assertEquals(6, result.getData().get(1).getRowIndex());

        // 验证无侵入 rowRecords 精确对齐物理行号 3 与 6（跳过空行 4 与失败行 5）
        List<ExcelRowData<MultiHeaderAwareImportRow>> rowRecords = result.getRowRecords();
        assertEquals(2, rowRecords.size());
        assertEquals(3, rowRecords.get(0).rowIndex());
        assertEquals("E001", rowRecords.get(0).data().getCode());
        assertEquals(6, rowRecords.get(1).rowIndex());
        assertEquals("E003", rowRecords.get(1).data().getCode());

        // 验证第 5 行失败行物理行号与 rowData 回填
        assertEquals(1, result.getErrors().size());
        ExcelErrorRow jsrError = result.getErrors().get(0);
        assertEquals(5, jsrError.getRowIndex());
        assertEquals(2, jsrError.getColumnIndex());
        assertEquals("年龄", jsrError.getColumnName());
        assertInstanceOf(MultiHeaderAwareImportRow.class, jsrError.getRowData());
        assertEquals(5, ((MultiHeaderAwareImportRow) jsrError.getRowData()).getRowIndex());

        // 直接使用 BatchValidationListener#getValidRowRecords 验证
        BatchValidationListener<MultiHeaderAwareImportRow> listener =
                new BatchValidationListener<>(MultiHeaderAwareImportRow.class);
        ExcelUtil.read(new ByteArrayInputStream(workbookBytes), MultiHeaderAwareImportRow.class, listener);
        assertEquals(2, listener.getValidRowRecords().size());
        assertEquals(3, listener.getValidRowRecords().get(0).rowIndex());
        assertEquals(6, listener.getValidRowRecords().get(1).rowIndex());
    }

    @Test
    @DisplayName("4. ExcelErrorRow.of / ofRow 便捷构建 + 未知字段 Fail-Fast + ExcelFeedbackUtil 物理定位回写")
    void shouldBuildErrorRowViaResolverAndRenderFeedbackAtExactPhysicalCells() throws Exception {
        byte[] workbookBytes = buildMultiHeaderMixedWorkbook();
        ExcelReadResult<MultiHeaderAwareImportRow> result = ExcelUtil.readWithResult(
                new ByteArrayInputStream(workbookBytes), MultiHeaderAwareImportRow.class);

        ExcelRowData<MultiHeaderAwareImportRow> validSecondRecord = result.getRowRecords().get(1);
        assertEquals(6, validSecondRecord.rowIndex());

        // 1. Lambda 与字符串两种 of 重载构建业务唯一性错误
        ExcelErrorRow bizPhoneDup = ExcelErrorRow.of(
                MultiHeaderAwareImportRow.class,
                validSecondRecord.rowIndex(),
                MultiHeaderAwareImportRow::getPhone,
                validSecondRecord.data().getPhone(),
                "手机号在数据库中已存在",
                validSecondRecord.data());
        assertEquals(6, bizPhoneDup.getRowIndex());
        assertEquals(1, bizPhoneDup.getColumnIndex());
        assertEquals("手机号", bizPhoneDup.getColumnName());
        assertEquals("phone", bizPhoneDup.getFieldName());
        assertSame(validSecondRecord.data(), bizPhoneDup.getRowData());

        ExcelErrorRow bizCodeDup = ExcelErrorRow.of(
                MultiHeaderAwareImportRow.class,
                result.getRowRecords().get(0).rowIndex(),
                "code",
                "E001",
                "工号重复");
        assertEquals(3, bizCodeDup.getRowIndex());
        assertEquals(0, bizCodeDup.getColumnIndex());
        assertEquals("工号", bizCodeDup.getColumnName());

        // 2. 整行级工厂 ofRow
        ExcelErrorRow wholeRowErr = ExcelErrorRow.ofRow(6, "跨字段审批状态冲突", validSecondRecord.data());
        assertEquals(6, wholeRowErr.getRowIndex());
        assertNull(wholeRowErr.getColumnIndex());
        assertSame(validSecondRecord.data(), wholeRowErr.getRowData());

        // 3. 未知或 @ExcelIgnore 字段 Fail-Fast 防错回 A 列
        assertThrows(IllegalArgumentException.class, () ->
                ExcelErrorRow.of(MultiHeaderAwareImportRow.class, 3, "nonExistentField", "x", "bad"));
        assertThrows(IllegalArgumentException.class, () ->
                ExcelErrorRow.of(MultiHeaderAwareImportRow.class, 3, MultiHeaderAwareImportRow::getRowIndex, 3, "ignored"));
        assertThrows(IllegalArgumentException.class, () ->
                ExcelErrorRow.of(MultiHeaderAwareImportRow.class, 0, "code", "E001", "invalid rowIndex"));
        assertThrows(IllegalArgumentException.class, () ->
                ExcelErrorRow.ofRow(0, "invalid rowIndex"));

        // 4. 合并 JSR-303 错误(第 5 行 age 列=2) 与 业务校验错误(第 6 行 phone 列=1) 渲染回写反馈工作簿
        List<ExcelErrorRow> mergedErrors = new ArrayList<>(result.getErrors());
        mergedErrors.add(bizPhoneDup);

        ByteArrayOutputStream feedbackOut = new ByteArrayOutputStream();
        ExcelUtil.exportErrorFeedback(new ByteArrayInputStream(workbookBytes), feedbackOut, mergedErrors);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(feedbackOut.toByteArray()))) {
            Sheet sheet = wb.getSheetAt(0);
            // 物理第 5 行（0-based row=4），age 列（col=2）标红并带批注
            Cell row5Age = sheet.getRow(4).getCell(2);
            assertEquals(IndexedColors.ROSE.getIndex(), row5Age.getCellStyle().getFillForegroundColor());
            assertNotNull(row5Age.getCellComment());
            assertTrue(row5Age.getCellComment().getString().getString().contains("年龄必须大于等于 18"));

            // 物理第 6 行（0-based row=5），phone 列（col=1）标红并带批注，A 列未误标
            Cell row6Phone = sheet.getRow(5).getCell(1);
            assertEquals(IndexedColors.ROSE.getIndex(), row6Phone.getCellStyle().getFillForegroundColor());
            assertNotNull(row6Phone.getCellComment());
            assertTrue(row6Phone.getCellComment().getString().getString().contains("手机号在数据库中已存在"));
            assertNull(sheet.getRow(5).getCell(0).getCellComment());
        }
    }

    private byte[] buildMultiHeaderMixedWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("入职导入");
            // 多级表头第 1、2 行（0-based 0, 1）
            Row head0 = sheet.createRow(0);
            head0.createCell(0).setCellValue("员工基础信息");
            head0.createCell(1).setCellValue("员工基础信息");
            head0.createCell(2).setCellValue("员工基础信息");

            Row head1 = sheet.createRow(1);
            head1.createCell(0).setCellValue("工号");
            head1.createCell(1).setCellValue("手机号");
            head1.createCell(2).setCellValue("年龄");

            // 物理第 3 行（0-based 2）：合法行 E001
            Row row3 = sheet.createRow(2);
            row3.createCell(0).setCellValue("E001");
            row3.createCell(1).setCellValue("13800138001");
            row3.createCell(2).setCellValue(25);

            // 物理第 4 行（0-based 3）：完全空白行（不创建任何 Cell 或创建空 Row，由 Fesod 自动跳过）
            sheet.createRow(3);

            // 物理第 5 行（0-based 4）：JSR-303 校验失败行（年龄=16 < 18）
            Row row5 = sheet.createRow(4);
            row5.createCell(0).setCellValue("E002");
            row5.createCell(1).setCellValue("13800138002");
            row5.createCell(2).setCellValue(16);

            // 物理第 6 行（0-based 5）：通过格式校验但后续业务校验冲突行 E003
            Row row6 = sheet.createRow(5);
            row6.createCell(0).setCellValue("E003");
            row6.createCell(1).setCellValue("13800138003");
            row6.createCell(2).setCellValue(30);

            wb.write(out);
            return out.toByteArray();
        }
    }
}
