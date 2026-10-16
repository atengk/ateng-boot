package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.model.EnumAndDictUserVO;
import io.github.atengk.boot.excel.model.UserGenderEnum;
import io.github.atengk.boot.excel.model.UserStatusEnum;
import io.github.atengk.boot.excel.spi.DictProvider;
import io.github.atengk.boot.excel.spi.DictProviderHolder;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 枚举与字典双向转义端到端测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelEnumAndDictTest {

    @BeforeEach
    void setUp() {
        // 注册模拟字典 SPI 提供者
        DictProviderHolder.setProvider(new DictProvider() {
            @Override
            public String toLabel(String dictType, Object code) {
                if ("sys_user_role".equals(dictType)) {
                    if ("ADMIN".equals(code)) return "超级管理员";
                    if ("USER".equals(code)) return "普通用户";
                }
                if ("sys_user_level".equals(dictType)) {
                    if (Objects.equals(code, 1) || "1".equals(String.valueOf(code))) return "黄金会员";
                    if (Objects.equals(code, 2) || "2".equals(String.valueOf(code))) return "钻石会员";
                }
                return null;
            }

            @Override
            public String toCode(String dictType, String label) {
                if ("sys_user_role".equals(dictType)) {
                    if ("超级管理员".equals(label)) return "ADMIN";
                    if ("普通用户".equals(label)) return "USER";
                }
                if ("sys_user_level".equals(dictType)) {
                    if ("黄金会员".equals(label)) return "1";
                    if ("钻石会员".equals(label)) return "2";
                }
                return null;
            }
        });
    }

    @AfterEach
    void tearDown() {
        // 测试完毕后清空全局持有器
        DictProviderHolder.setProvider(null);
    }

    @Test
    @DisplayName("验证枚举与字典导出为中文文本，导入反向还原为枚举及原始编码")
    void testExportAndReadEnumAndDict() throws IOException {
        // 1. 准备业务实体
        List<EnumAndDictUserVO> originalList = List.of(
                new EnumAndDictUserVO(1001L, "张三", UserGenderEnum.MALE, UserStatusEnum.ENABLE, 1, "ADMIN", 1),
                new EnumAndDictUserVO(1002L, "李四", UserGenderEnum.FEMALE, UserStatusEnum.DISABLE, 2, "USER", 2)
        );

        // 2. 导出到内存字节数组
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, originalList, EnumAndDictUserVO.class);
        byte[] excelBytes = out.toByteArray();
        assertTrue(excelBytes.length > 0, "导出生成的 Excel 二进制内容不应为空");

        // 3. 使用 POI 严格核验单元格呈现（确认输出全部为人类可读的中文文本）
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertNotNull(sheet, "应存在 Sheet0");

            // 表头校验
            Row headRow = sheet.getRow(0);
            assertNotNull(headRow);
            assertEquals("用户ID", headRow.getCell(0).getStringCellValue());
            assertEquals("用户名称", headRow.getCell(1).getStringCellValue());
            assertEquals("用户性别", headRow.getCell(2).getStringCellValue());
            assertEquals("用户状态", headRow.getCell(3).getStringCellValue());
            assertEquals("性别编码", headRow.getCell(4).getStringCellValue());
            assertEquals("系统角色", headRow.getCell(5).getStringCellValue());
            assertEquals("会员等级", headRow.getCell(6).getStringCellValue());

            // 数据第 1 行 (张三)
            Row row1 = sheet.getRow(1);
            assertNotNull(row1);
            assertEquals("张三", row1.getCell(1).getStringCellValue());
            assertEquals("男", row1.getCell(2).getStringCellValue());
            assertEquals("已启用", row1.getCell(3).getStringCellValue());
            assertEquals("男", row1.getCell(4).getStringCellValue());
            assertEquals("超级管理员", row1.getCell(5).getStringCellValue());
            assertEquals("黄金会员", row1.getCell(6).getStringCellValue());

            // 数据第 2 行 (李四)
            Row row2 = sheet.getRow(2);
            assertNotNull(row2);
            assertEquals("李四", row2.getCell(1).getStringCellValue());
            assertEquals("女", row2.getCell(2).getStringCellValue());
            assertEquals("已禁用", row2.getCell(3).getStringCellValue());
            assertEquals("女", row2.getCell(4).getStringCellValue());
            assertEquals("普通用户", row2.getCell(5).getStringCellValue());
            assertEquals("钻石会员", row2.getCell(6).getStringCellValue());
        }

        // 4. 反向读取解析回 Java 实体，验证逆向映射还原能力
        List<EnumAndDictUserVO> readList = ExcelUtil.read(new ByteArrayInputStream(excelBytes), EnumAndDictUserVO.class);
        assertNotNull(readList);
        assertEquals(2, readList.size());

        EnumAndDictUserVO u1 = readList.get(0);
        assertEquals(1001L, u1.getId());
        assertEquals("张三", u1.getUsername());
        assertEquals(UserGenderEnum.MALE, u1.getGender(), "枚举类型字段应无损反向解析还原为 MALE");
        assertEquals(UserStatusEnum.ENABLE, u1.getStatus(), "枚举类型字段应无损反向解析还原为 ENABLE");
        assertEquals(1, u1.getGenderCode(), "数字编码字段应反查映射还原为原始 code 1");
        assertEquals("ADMIN", u1.getRole(), "字典字符串字段应通过 DictConverter 反向解析还原为 ADMIN");
        assertEquals(1, u1.getLevel(), "字典字段应通过 DictConverter 反向解析为数字 1");

        EnumAndDictUserVO u2 = readList.get(1);
        assertEquals(1002L, u2.getId());
        assertEquals("李四", u2.getUsername());
        assertEquals(UserGenderEnum.FEMALE, u2.getGender(), "枚举类型字段应无损反向解析还原为 FEMALE");
        assertEquals(UserStatusEnum.DISABLE, u2.getStatus(), "枚举类型字段应无损反向解析还原为 DISABLE");
        assertEquals(2, u2.getGenderCode(), "数字编码字段应反查映射还原为原始 code 2");
        assertEquals("USER", u2.getRole(), "字典字符串字段应通过 DictConverter 反向解析还原为 USER");
        assertEquals(2, u2.getLevel(), "字典字段应通过 DictConverter 反向解析为数字 2");
    }

    @Test
    @DisplayName("验证流式分批读取时枚举与字典的映射能力")
    void testBatchReadEnumAndDict() {
        List<EnumAndDictUserVO> originalList = List.of(
                new EnumAndDictUserVO(2001L, "王五", UserGenderEnum.MALE, UserStatusEnum.ENABLE, 1, "ADMIN", 1),
                new EnumAndDictUserVO(2002L, "赵六", UserGenderEnum.FEMALE, UserStatusEnum.DISABLE, 2, "USER", 2)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, originalList, EnumAndDictUserVO.class);
        byte[] excelBytes = out.toByteArray();

        List<EnumAndDictUserVO> batchCollected = new ArrayList<>();
        AtomicInteger batchCount = new AtomicInteger();

        ExcelUtil.readBatch(new ByteArrayInputStream(excelBytes), EnumAndDictUserVO.class, 1, batch -> {
            batchCount.incrementAndGet();
            batchCollected.addAll(batch);
        });

        assertEquals(2, batchCount.get(), "按批次大小 1 读取，应触发 2 次消费回调");
        assertEquals(2, batchCollected.size());
        assertEquals(UserGenderEnum.MALE, batchCollected.get(0).getGender());
        assertEquals(UserStatusEnum.DISABLE, batchCollected.get(1).getStatus());
        assertEquals("ADMIN", batchCollected.get(0).getRole());
        assertEquals(1, batchCollected.get(0).getLevel());
    }

    @Test
    @DisplayName("验证未匹配字典项与未配置 DictProvider 时的安全降级与原值保留")
    void testUnmappedAndNoProviderFallback() {
        // 清空 DictProvider 模拟未配置场景
        DictProviderHolder.setProvider(null);

        String label = DictProviderHolder.toLabel("sys_user_role", "UNKNOWN_ROLE", true);
        assertEquals("UNKNOWN_ROLE", label, "未配置 Provider 时应回退保留原值");

        Object code = DictProviderHolder.toCode("sys_user_level", "99", Integer.class, true);
        assertEquals(99, code, "未匹配时应原样转为目标数值类型");
    }

    @Test
    @DisplayName("验证 ignoreUnmapped 为 false 时未匹配项抛出转换异常")
    void testStrictEnumMappingException() {
        var converter = new io.github.atengk.boot.excel.converter.ExcelEnumConverter(StrictGenderEnum.class, null);
        assertThrows(IllegalArgumentException.class, () -> {
            converter.convertToJavaData(new org.apache.fesod.sheet.metadata.data.ReadCellData<>("未知性别"), null, null);
        }, "未匹配的枚举文本在严格模式下应抛出异常");

        assertThrows(IllegalArgumentException.class, () -> {
            converter.convertToExcelData("UNKNOWN_CODE", null, null);
        }, "未匹配的枚举代码在严格模式下应抛出异常");
    }

    @io.github.atengk.boot.excel.annotation.ExcelEnum(ignoreUnmapped = false)
    enum StrictGenderEnum {
        MALE(1, "男");
        private final int code;
        private final String text;
        StrictGenderEnum(int code, String text) {
            this.code = code;
            this.text = text;
        }
        public int getCode() { return code; }
        public String getText() { return text; }
    }
}
