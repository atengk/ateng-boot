package io.github.atengk.boot.excel.listener;

import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 导入错误行数熔断器 (Circuit Breaker) 单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class ExcelCircuitBreakerTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RowDTO {
        @ExcelProperty("用户名")
        @NotBlank(message = "用户名不可为空")
        private String username;

        @ExcelProperty("年龄")
        @Min(value = 18, message = "年龄必须大于等于 18 岁")
        private Integer age;
    }

    private byte[] createTestExcel(int rowCount, boolean allInvalid) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            Row head = sheet.createRow(0);
            head.createCell(0).setCellValue("用户名");
            head.createCell(1).setCellValue("年龄");

            for (int i = 1; i <= rowCount; i++) {
                Row row = sheet.createRow(i);
                if (allInvalid) {
                    row.createCell(0).setCellValue("");      // 触发 @NotBlank 错误
                    row.createCell(1).setCellValue(10);      // 触发 @Min(18) 错误
                } else {
                    row.createCell(0).setCellValue("用户_" + i);
                    row.createCell(1).setCellValue(20);
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("错误行数达到熔断阈值：立即终止后续解析并标记 circuitBroken=true")
    void testCircuitBreakerTriggersOnThreshold() {
        int totalRows = 50;
        int maxErrors = 10;
        byte[] excelBytes = createTestExcel(totalRows, true);

        // 使用指定 maxErrors = 10 读取
        ExcelReadResult<RowDTO> result = ExcelUtil.readWithResult(
                new ByteArrayInputStream(excelBytes),
                RowDTO.class,
                HeaderValidationStrategy.LOOSE,
                maxErrors
        );

        assertTrue(result.isCircuitBroken(), "达到熔断阈值必须标记 circuitBroken 为 true");
        assertTrue(result.hasErrors());
        // 因为单行可能有 2 处错误（用户名为空+年龄<18），错误条数至少为 10
        assertTrue(result.getErrors().size() >= maxErrors, "错误数应达到熔断阈值");
        // 因为触发了 hasNext = false，不会继续读取剩余的 40+ 行
        assertTrue(result.getErrors().size() < totalRows * 2, "熔断后应立即终止后续行读取，错误总数应远小于全量解析");
    }

    @Test
    @DisplayName("错误行数未达阈值：解析全部完成且 circuitBroken=false")
    void testCircuitBreakerDoesNotTriggerWhenErrorsBelowThreshold() {
        int totalRows = 3;
        int maxErrors = 20;
        byte[] excelBytes = createTestExcel(totalRows, true);

        ExcelReadResult<RowDTO> result = ExcelUtil.readWithResult(
                new ByteArrayInputStream(excelBytes),
                RowDTO.class,
                HeaderValidationStrategy.LOOSE,
                maxErrors
        );

        assertFalse(result.isCircuitBroken(), "错误数未超限时不应触发熔断");
        assertEquals(6, result.getErrors().size(), "3 行每行 2 处错误，共计 6 处");
    }

    @Test
    @DisplayName("readWithValidation 触发熔断时抛出明确包含熔断信息的 ExcelValidationException")
    void testReadWithValidationCircuitBreakException() {
        int totalRows = 30;
        byte[] excelBytes = createTestExcel(totalRows, true);

        // 构造指定 maxErrors = 5 的监听器
        BatchValidationListener<RowDTO> listener = new BatchValidationListener<>(
                RowDTO.class,
                HeaderValidationStrategy.LOOSE,
                5
        );

        var readerBuilder = org.apache.fesod.sheet.FesodSheet.read(new ByteArrayInputStream(excelBytes), RowDTO.class, listener);
        readerBuilder.sheet().doRead();

        assertTrue(listener.isCircuitBroken());
        assertTrue(listener.getErrorList().size() >= 5);
        assertTrue(listener.getErrorList().size() < totalRows * 2);
    }
}
