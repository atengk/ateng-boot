package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.util.ExcelUtil;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 导入差错隔离、错误数据提取与红标回写导出集成测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
class ExcelFailSplitTest {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FailSplitUserDTO {
        @ExcelProperty("用户名称")
        @NotBlank(message = "用户名称不可为空")
        private String username;

        @ExcelProperty("年龄")
        @Min(value = 18, message = "年龄不能低于 18 岁")
        private Integer age;

        @ExcelProperty("手机号码")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String phone;
    }

    private void saveTempExcel(String fileName, byte[] bytes) throws IOException {
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "ateng-excel-test");
        Files.createDirectories(tempDir);
        File tempFile = tempDir.resolve(fileName).toFile();
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(bytes);
        }
        System.out.println("====== [FailSplitTest] Excel 文件已落盘 ======");
        System.out.println("文件绝对路径: " + tempFile.getAbsolutePath());
        System.out.println("本地直达链接: file:///" + tempFile.getAbsolutePath().replace("\\", "/"));
        System.out.println("==============================================");
    }

    @Test
    @DisplayName("测试批量导入差错数据隔离、去重提取与纠错标注导出")
    void testFailSplitExport() throws Exception {
        // 1. 构造原始 Excel 数据（1 条合法，1 条非法，1 条部分字段非法）
        List<FailSplitUserDTO> originalList = new ArrayList<>();
        originalList.add(new FailSplitUserDTO("张三", 25, "13800138000")); // 合法
        originalList.add(new FailSplitUserDTO("", 15, "110"));             // 非法：3 处校验失败
        originalList.add(new FailSplitUserDTO("李四", 30, "12345"));        // 非法：手机号格式错误

        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        ExcelUtil.export(rawOut, originalList, FailSplitUserDTO.class);
        byte[] rawExcelBytes = rawOut.toByteArray();

        // 2. 流式同步读取并执行 JSR-380 校验
        ExcelReadResult<FailSplitUserDTO> readResult = ExcelUtil.readWithResult(
                new ByteArrayInputStream(rawExcelBytes),
                FailSplitUserDTO.class
        );

        // 校验合规与非法分流
        assertEquals(1, readResult.getData().size(), "应仅有 1 条合法合规数据入库");
        assertEquals("张三", readResult.getData().get(0).getUsername());

        List<ExcelErrorRow> errorRows = readResult.getErrors();
        assertFalse(errorRows.isEmpty(), "错误行集合不应为空");

        // 验证错误实体提取与按行去重
        List<FailSplitUserDTO> failedList = readResult.getFailedData();
        assertEquals(2, failedList.size(), "校验失败的原始实体应准确去重提取为 2 条");

        // 3. 差错数据隔离导出：生成纠错清单
        ByteArrayOutputStream failOut = new ByteArrayOutputStream();
        ExcelUtil.exportFailedRows(failOut, FailSplitUserDTO.class, errorRows);
        byte[] failedExcelBytes = failOut.toByteArray();
        assertNotNull(failedExcelBytes);
        assertTrue(failedExcelBytes.length > 0);

        saveTempExcel("test-fail-split-error-export.xlsx", failedExcelBytes);

        // 4. 解析纠错 Excel，验证末尾追加【失败原因】表头及单元格标红
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(failedExcelBytes))) {
            Sheet sheet = workbook.getSheet("纠错清单");
            assertNotNull(sheet, "应生成名称为【纠错清单】的工作表");

            // 验证表头行
            Row headerRow = sheet.getRow(0);
            assertNotNull(headerRow);
            assertEquals("用户名称", headerRow.getCell(0).getStringCellValue());
            assertEquals("年龄", headerRow.getCell(1).getStringCellValue());
            assertEquals("手机号码", headerRow.getCell(2).getStringCellValue());
            assertEquals("失败原因", headerRow.getCell(3).getStringCellValue(), "表头末尾应自动追加【失败原因】列");

            // 验证第 1 行错误数据（原始数据 2：空用户名，15岁，110手机号）
            Row dataRow1 = sheet.getRow(1);
            assertNotNull(dataRow1);
            Cell reasonCell1 = dataRow1.getCell(3);
            assertNotNull(reasonCell1);
            String reasonText1 = reasonCell1.getStringCellValue();
            assertTrue(reasonText1.contains("用户名称不可为空"), "应包含用户名错误信息");
            assertTrue(reasonText1.contains("年龄不能低于 18 岁"), "应包含年龄错误信息");
            assertTrue(reasonText1.contains("手机号格式不正确"), "应包含手机号错误信息");

            // 验证第 2 行错误数据（李四，手机号格式错误）
            Row dataRow2 = sheet.getRow(2);
            assertNotNull(dataRow2);
            Cell reasonCell2 = dataRow2.getCell(3);
            assertNotNull(reasonCell2);
            String reasonText2 = reasonCell2.getStringCellValue();
            assertTrue(reasonText2.contains("手机号格式不正确"));
            assertFalse(reasonText2.contains("用户名称不可为空"));
        }
    }
}
