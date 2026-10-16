package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.model.ImportUserVO;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel JSR-303 数据校验与错误标红批注回写测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelValidationTest {

    @Test
    @DisplayName("测试校验通过：全部合法数据无损解析，零错误")
    void testValidDataSuccess() {
        List<ImportUserVO> validList = List.of(
                ImportUserVO.builder().name("张三").phone("13800138000").age(25).email("zhangsan@example.com").build(),
                ImportUserVO.builder().name("李四").phone("13912345678").age(30).email("lisi@example.com").build()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, validList, ImportUserVO.class);

        // 1. readWithValidation 读取
        List<ImportUserVO> readList = ExcelUtil.readWithValidation(new ByteArrayInputStream(out.toByteArray()), ImportUserVO.class);
        assertEquals(2, readList.size());
        assertEquals("张三", readList.get(0).getName());
        assertEquals("李四", readList.get(1).getName());

        // 2. readWithResult 读取
        ExcelReadResult<ImportUserVO> result = ExcelUtil.readWithResult(new ByteArrayInputStream(out.toByteArray()), ImportUserVO.class);
        assertFalse(result.hasErrors());
        assertEquals(2, result.getData().size());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("测试校验聚合模式：多行多列失败时精确捕获物理行号、表头名称与错误信息")
    void testInvalidDataAggregation() {
        // 第 1 行是表头
        // 第 2 行数据：手机号不符合正则
        // 第 3 行数据：姓名为空且年龄小于18（单行2处错误）
        // 第 4 行数据：邮箱格式非法
        List<ImportUserVO> rawList = List.of(
                ImportUserVO.builder().name("王五").phone("12345").age(20).email("wangwu@test.com").build(),
                ImportUserVO.builder().name("").phone("13811112222").age(16).email("zhaoliu@test.com").build(),
                ImportUserVO.builder().name("钱七").phone("13922223333").age(28).email("not-an-email").build()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, rawList, ImportUserVO.class);
        byte[] excelBytes = out.toByteArray();

        // 1. 测试 readWithResult 不抛异常模式
        ExcelReadResult<ImportUserVO> result = ExcelUtil.readWithResult(new ByteArrayInputStream(excelBytes), ImportUserVO.class);
        assertTrue(result.hasErrors());
        assertEquals(4, result.getErrors().size(), "应聚合 4 处校验错误");

        List<ExcelErrorRow> errors = result.getErrors();

        // 验证物理行号：第一行数据对应 Excel 第 2 行
        assertTrue(errors.stream().anyMatch(e -> e.getRowIndex() == 2 && "手机号码".equals(e.getColumnName())));
        // 验证第二行数据对应 Excel 第 3 行（2 处错误）
        assertTrue(errors.stream().anyMatch(e -> e.getRowIndex() == 3 && "用户姓名".equals(e.getColumnName())));
        assertTrue(errors.stream().anyMatch(e -> e.getRowIndex() == 3 && "用户年龄".equals(e.getColumnName())));
        // 验证第三行数据对应 Excel 第 4 行
        assertTrue(errors.stream().anyMatch(e -> e.getRowIndex() == 4 && "电子邮箱".equals(e.getColumnName())));

        // 2. 测试 readWithValidation 默认聚合模式抛出 ExcelValidationException
        ExcelValidationException ex = assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.readWithValidation(new ByteArrayInputStream(excelBytes), ImportUserVO.class)
        );
        assertEquals(4, ex.getErrorRows().size());
        assertTrue(ex.getMessage().contains("Excel 数据校验失败"));
    }

    @Test
    @DisplayName("测试快速失败模式 (fail-fast)：遇到首个错误立即中断")
    void testFailFastMode() {
        List<ImportUserVO> rawList = List.of(
                ImportUserVO.builder().name("测试一").phone("invalid-phone").age(20).email("t1@test.com").build(),
                ImportUserVO.builder().name("测试二").phone("13800001111").age(10).email("t2@test.com").build()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, rawList, ImportUserVO.class);

        ExcelValidationException ex = assertThrows(ExcelValidationException.class, () ->
                ExcelUtil.readWithValidation(new ByteArrayInputStream(out.toByteArray()), ImportUserVO.class, true)
        );

        // 快速失败仅捕获第 2 行的错误即终止，不会继续扫描第 3 行
        assertTrue(ex.getErrorRows().size() >= 1);
        assertEquals(2, ex.getErrorRows().get(0).getRowIndex(), "首个错误必须发生在物理第 2 行");
    }

    @Test
    @DisplayName("测试流式分批校验读取与错误回调")
    void testReadBatchWithValidation() {
        List<ImportUserVO> rawList = List.of(
                ImportUserVO.builder().name("合法一").phone("13811111111").age(22).email("h1@test.com").build(),
                ImportUserVO.builder().name("非法二").phone("110").age(22).email("h2@test.com").build(),
                ImportUserVO.builder().name("合法三").phone("13833333333").age(25).email("h3@test.com").build()
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, rawList, ImportUserVO.class);

        List<ImportUserVO> consumedValid = new ArrayList<>();
        List<ExcelErrorRow> consumedErrors = new ArrayList<>();

        ExcelUtil.readBatchWithValidation(
                new ByteArrayInputStream(out.toByteArray()),
                ImportUserVO.class,
                2,
                consumedValid::addAll,
                consumedErrors::addAll
        );

        assertEquals(2, consumedValid.size(), "应消费 2 条合法数据");
        assertEquals(1, consumedErrors.size(), "应消费 1 条非法数据错误");
        assertEquals(3, consumedErrors.get(0).getRowIndex(), "非法数据物理行号为第 3 行");
    }

    @Test
    @DisplayName("测试错误反馈回写：校验失败单元格标红并嵌入悬浮批注")
    void testExportErrorFeedbackWithHighlightAndComment() throws Exception {
        // 构造一个包含错误数据的原始上传 Excel
        List<ImportUserVO> rawList = List.of(
                ImportUserVO.builder().name("正常用户").phone("13812345678").age(25).email("ok@test.com").build(),
                ImportUserVO.builder().name("异常用户").phone("error-phone").age(20).email("err@test.com").build()
        );

        ByteArrayOutputStream rawExcelOut = new ByteArrayOutputStream();
        ExcelUtil.export(rawExcelOut, rawList, ImportUserVO.class);
        byte[] originalExcelBytes = rawExcelOut.toByteArray();

        // 1. 读取并收集错误
        ExcelReadResult<ImportUserVO> readResult = ExcelUtil.readWithResult(
                new ByteArrayInputStream(originalExcelBytes), ImportUserVO.class
        );
        assertTrue(readResult.hasErrors());
        List<ExcelErrorRow> errorRows = readResult.getErrors();

        // 2. 一键生成标红并带批注的错误反馈 Excel
        ByteArrayOutputStream feedbackOut = new ByteArrayOutputStream();
        ExcelUtil.exportErrorFeedback(
                new ByteArrayInputStream(originalExcelBytes),
                feedbackOut,
                errorRows
        );
        byte[] feedbackBytes = feedbackOut.toByteArray();
        assertTrue(feedbackBytes.length > 0);

        // 3. 通过 POI 读取验证生成的反馈 Excel
        try (InputStream in = new ByteArrayInputStream(feedbackBytes);
             Workbook workbook = WorkbookFactory.create(in)) {

            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet);

            // 物理第 2 行（索引 1）是正常数据，手机号在列 1
            Row row1 = sheet.getRow(1);
            Cell normalCell = row1.getCell(1);
            assertNull(normalCell.getCellComment(), "正常单元格不应有批注");

            // 物理第 3 行（索引 2）是异常数据，手机号列（索引 1）应标红并有批注
            Row row2 = sheet.getRow(2);
            assertNotNull(row2);
            Cell errorCell = row2.getCell(1);
            assertNotNull(errorCell);

            // 验证背景色标红 (IndexedColors.ROSE)
            assertEquals(IndexedColors.ROSE.getIndex(), errorCell.getCellStyle().getFillForegroundColor(),
                    "出错单元格背景色应填充为 ROSE 警示粉红");
            assertEquals(FillPatternType.SOLID_FOREGROUND, errorCell.getCellStyle().getFillPattern());

            // 验证悬浮批注
            Comment comment = errorCell.getCellComment();
            assertNotNull(comment, "出错单元格必须挂载悬浮批注");
            String commentText = comment.getString().getString();
            assertTrue(commentText.contains("手机号码格式不合法"), "批注内容必须包含 JSR-303 错误提示");
        }
    }
}
