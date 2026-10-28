package io.github.atengk.boot.excel.listener;

import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.exception.ExcelHeaderValidationException;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Excel 表头契约校验器 (ExcelHeaderValidator) 单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
class ExcelHeaderValidatorTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SimpleUserDTO {
        @ExcelProperty("用户名称")
        @NotBlank
        private String username;

        @ExcelProperty("用户年龄")
        @NotNull
        private Integer age;

        @ExcelProperty("手机号码")
        private String phone;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MultiLevelUserDTO {
        @ExcelProperty({"基本信息", "姓名"})
        private String name;

        @ExcelProperty({"基本信息", "年龄"})
        private Integer age;

        @ExcelProperty({"联系方式", "手机"})
        private String phone;
    }

    private byte[] createExcel(List<List<String>> headerRows, List<List<Object>> dataRows) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            int r = 0;
            if (headerRows != null) {
                for (List<String> headRow : headerRows) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < headRow.size(); c++) {
                        row.createCell(c).setCellValue(headRow.get(c));
                    }
                }
            }
            if (dataRows != null) {
                for (List<Object> dataRow : dataRows) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < dataRow.size(); c++) {
                        Object val = dataRow.get(c);
                        if (val instanceof Number n) {
                            row.createCell(c).setCellValue(n.doubleValue());
                        } else {
                            row.createCell(c).setCellValue(val != null ? val.toString() : "");
                        }
                    }
                }
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("STRICT 模式：表头列名与顺序 100% 一致校验通过")
    void testStrictStrategySuccess() {
        byte[] excelBytes = createExcel(
                List.of(List.of("用户名称", "用户年龄", "手机号码")),
                List.of(List.of("张三", 25, "13800000000"))
        );

        List<SimpleUserDTO> list = ExcelUtil.readWithValidation(
                new ByteArrayInputStream(excelBytes),
                SimpleUserDTO.class,
                HeaderValidationStrategy.STRICT
        );

        assertEquals(1, list.size());
        assertEquals("张三", list.get(0).getUsername());
        assertEquals(25, list.get(0).getAge());
    }

    @Test
    @DisplayName("STRICT 模式：列顺序颠倒立即 Fail-Fast 阻断")
    void testStrictStrategyOrderMismatchFailFast() {
        // 列顺序变为：用户年龄, 用户名称, 手机号码
        byte[] excelBytes = createExcel(
                List.of(List.of("用户年龄", "用户名称", "手机号码")),
                List.of(List.of(25, "张三", "13800000000"))
        );

        ExcelHeaderValidationException ex = assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.readWithValidation(
                        new ByteArrayInputStream(excelBytes),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.STRICT
                )
        );

        assertEquals(HeaderValidationStrategy.STRICT, ex.getStrategy());
        assertTrue(ex.getMessage().contains("STRICT 模式"));
        assertTrue(ex.getMessage().contains("第 1 列表头不匹配"));
        assertTrue(ex.getExpectedHeaders().contains("用户名称"));
    }

    @Test
    @DisplayName("STRICT 模式：表头缺少列或列名不匹配立即 Fail-Fast 阻断")
    void testStrictStrategyColumnNameMismatch() {
        byte[] excelBytes = createExcel(
                List.of(List.of("姓名", "用户年龄", "手机号码")),
                List.of(List.of("张三", 25, "13800000000"))
        );

        ExcelHeaderValidationException ex = assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.readWithValidation(
                        new ByteArrayInputStream(excelBytes),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.STRICT
                )
        );

        assertTrue(ex.getMessage().contains("第 1 列表头不匹配"));
        assertTrue(ex.getMissingHeaders().contains("用户名称"));
    }

    @Test
    @DisplayName("LOOSE 模式：列顺序调换且包含无关扩展列时依然能正常匹配通过")
    void testLooseStrategyOrderAndExtraColumnsSuccess() {
        // 顺序颠倒，且末尾多了一列 "备注"
        byte[] excelBytes = createExcel(
                List.of(List.of("手机号码", "用户名称", "用户年龄", "备注")),
                List.of(List.of("13800000000", "李四", 30, "额外说明"))
        );

        List<SimpleUserDTO> list = ExcelUtil.readWithValidation(
                new ByteArrayInputStream(excelBytes),
                SimpleUserDTO.class,
                HeaderValidationStrategy.LOOSE
        );

        assertEquals(1, list.size());
        assertEquals("李四", list.get(0).getUsername());
        assertEquals(30, list.get(0).getAge());
        assertEquals("13800000000", list.get(0).getPhone());
    }

    @Test
    @DisplayName("LOOSE 模式：缺少必要列时 Fail-Fast 阻断并准确报告缺失列名")
    void testLooseStrategyMissingRequiredColumnFailFast() {
        // 缺少 "手机号码" 列
        byte[] excelBytes = createExcel(
                List.of(List.of("用户名称", "用户年龄")),
                List.of(List.of("王五", 28))
        );

        ExcelHeaderValidationException ex = assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.readWithValidation(
                        new ByteArrayInputStream(excelBytes),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.LOOSE
                )
        );

        assertEquals(HeaderValidationStrategy.LOOSE, ex.getStrategy());
        assertTrue(ex.getMessage().contains("LOOSE 模式"));
        assertTrue(ex.getMessage().contains("手机号码"));
        assertEquals(List.of("手机号码"), ex.getMissingHeaders());
    }

    @Test
    @DisplayName("DISABLED 模式：表头完全不匹配也不会抛出表头契约异常")
    void testDisabledStrategyIgnoresHeaders() {
        byte[] excelBytes = createExcel(
                List.of(List.of("完全无关的列1", "完全无关的列2")),
                List.of(List.of("数据1", "数据2"))
        );

        // DISABLED 下不会触发 ExcelHeaderValidationException
        // 但由于数据不满足 SimpleUserDTO 的 @NotBlank @NotNull，会触发 ExcelValidationException
        assertDoesNotThrow(() -> {
            try {
                ExcelUtil.readWithValidation(
                        new ByteArrayInputStream(excelBytes),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.DISABLED
                );
            } catch (io.github.atengk.boot.excel.exception.ExcelValidationException expected) {
                // 预期的数据行校验异常，证明成功穿透了表头校验
            }
        });
    }

    @Test
    @DisplayName("多级表头校验：层级路径严格比对")
    void testMultiLevelHeaderValidation() {
        // 正确的 2 行表头
        byte[] validExcel = createExcel(
                List.of(
                        List.of("基本信息", "基本信息", "联系方式"),
                        List.of("姓名", "年龄", "手机")
                ),
                List.of(List.of("赵六", 20, "13900000000"))
        );

        List<MultiLevelUserDTO> list = ExcelUtil.readWithValidation(
                new ByteArrayInputStream(validExcel),
                MultiLevelUserDTO.class,
                HeaderValidationStrategy.STRICT
        );
        assertEquals(1, list.size());
        assertEquals("赵六", list.get(0).getName());

        // 错误的 2 行表头：第二行 "手机" 写成了 "电话"
        byte[] invalidExcel = createExcel(
                List.of(
                        List.of("基本信息", "基本信息", "联系方式"),
                        List.of("姓名", "年龄", "电话")
                ),
                List.of(List.of("赵六", 20, "13900000000"))
        );

        assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.readWithValidation(
                        new ByteArrayInputStream(invalidExcel),
                        MultiLevelUserDTO.class,
                        HeaderValidationStrategy.STRICT
                )
        );
    }

    @Test
    @DisplayName("ExcelUtil.validateHeader 独立门面：仅探测表头秒级退出，不读取解析数据行")
    void testValidateHeaderStandaloneFacade() {
        byte[] excelBytes = createExcel(
                List.of(List.of("用户名称", "用户年龄", "手机号码")),
                List.of(List.of("孙七", 35, "13700000000"))
        );

        assertDoesNotThrow(() ->
                ExcelUtil.validateHeader(
                        new ByteArrayInputStream(excelBytes),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.STRICT
                )
        );

        byte[] invalidExcel = createExcel(
                List.of(List.of("错误表头1", "错误表头2")),
                List.of()
        );

        assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.validateHeader(
                        new ByteArrayInputStream(invalidExcel),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.LOOSE
                )
        );
    }

    @Test
    @DisplayName("STRICT 严格模式下严禁多余冗余列：包含多余表头列时立即 Fail-Fast 阻断")
    void testStrictValidationFailsOnRedundantExtraColumns() {
        // SimpleUserDTO 仅包含 3 列：用户名称、用户年龄、手机号码
        // 传入包含第 4 列 "多余备注" 的 Excel 表格
        byte[] excelWithExtraColumn = createExcel(
                List.of(List.of("用户名称", "用户年龄", "手机号码", "多余备注")),
                List.of(List.of("周八", 28, "13600000000", "一些备注"))
        );

        // 1. STRICT 模式必须拦截多余列
        ExcelHeaderValidationException ex = assertThrows(ExcelHeaderValidationException.class, () ->
                ExcelUtil.validateHeader(
                        new ByteArrayInputStream(excelWithExtraColumn),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.STRICT
                )
        );
        assertTrue(ex.getMessage().contains("多余的未知表头列"));
        assertTrue(ex.getMessage().contains("多余备注"));

        // 2. 相同文件在 LOOSE 宽松模式下应正常通过（因为包含了所有预期列）
        assertDoesNotThrow(() ->
                ExcelUtil.validateHeader(
                        new ByteArrayInputStream(excelWithExtraColumn),
                        SimpleUserDTO.class,
                        HeaderValidationStrategy.LOOSE
                )
        );
    }
}
