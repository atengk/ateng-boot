package io.github.atengk.boot.excel;

import io.github.atengk.boot.excel.model.SensitiveUser;
import io.github.atengk.boot.excel.util.ExcelUtil;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Excel 导出动态脱敏拦截测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class ExcelSensitiveTest {

    @Data
    public static class SensitiveResultVO {
        @ExcelProperty("用户姓名")
        private String name;

        @ExcelProperty("手机号")
        private String mobile;

        @ExcelProperty("身份证号")
        private String idCard;

        @ExcelProperty("电子邮箱")
        private String email;

        @ExcelProperty("银行卡号")
        private String bankCard;

        @ExcelProperty("自定义掩码")
        private String custom;

        @ExcelProperty("首尾保留掩码")
        private String keepHeadTail;

        @ExcelProperty("明文字段")
        private String plaintext;
    }

    @Test
    @DisplayName("测试导出动态脱敏：导出的 Excel 内容被打码，内存中原对象 100% 保持真明文")
    void testExportSensitiveMaskingAndInMemoryPlaintextInvariant() {
        SensitiveUser user = SensitiveUser.builder()
                .name("张小三")
                .mobile("13812345678")
                .idCard("110101199001011234")
                .email("zhangsan@example.com")
                .bankCard("6222021234567890123")
                .custom("abcdef")
                .keepHeadTail("12345678")
                .plaintext("绝密明文信息")
                .build();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // 1. 执行导出
        ExcelUtil.export(out, List.of(user), SensitiveUser.class);

        // 2. 内存真明文不变量断言（ADR-0012 架构守卫）：源对象绝不能被修改
        assertEquals("张小三", user.getName(), "源对象姓名必须保持真明文");
        assertEquals("13812345678", user.getMobile(), "源对象手机号必须保持真明文");
        assertEquals("110101199001011234", user.getIdCard(), "源对象身份证必须保持真明文");
        assertEquals("zhangsan@example.com", user.getEmail(), "源对象邮箱必须保持真明文");
        assertEquals("6222021234567890123", user.getBankCard(), "源对象银行卡必须保持真明文");
        assertEquals("abcdef", user.getCustom(), "源对象自定义字段必须保持真明文");
        assertEquals("12345678", user.getKeepHeadTail(), "源对象首尾保留字段必须保持真明文");
        assertEquals("绝密明文信息", user.getPlaintext(), "源对象明文字段必须保持真明文");

        // 3. 反向解析导出的 Excel 内容，断言单元格已被动态脱敏掩码
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<SensitiveResultVO> parsed = ExcelUtil.read(in, SensitiveResultVO.class);

        assertNotNull(parsed);
        assertEquals(1, parsed.size());
        SensitiveResultVO result = parsed.get(0);

        assertEquals("张*三", result.getName(), "导出的 Excel 姓名应被掩码");
        assertEquals("138****5678", result.getMobile(), "导出的 Excel 手机号应被掩码");
        assertEquals("110101********1234", result.getIdCard(), "导出的 Excel 身份证应被掩码");
        assertEquals("z******n@example.com", result.getEmail(), "导出的 Excel 邮箱应被掩码");
        assertEquals("622202*********0123", result.getBankCard(), "导出的 Excel 银行卡应被掩码");
        assertEquals("ab###f", result.getCustom(), "导出的 Excel 自定义字段应被掩码");
        assertEquals("12####78", result.getKeepHeadTail(), "导出的 Excel 首尾保留字段应被掩码");
        assertEquals("绝密明文信息", result.getPlaintext(), "未标记脱敏的字段应保持明文");
    }

    @Data
    public static class CoreFieldSensitiveUser {
        @ExcelProperty("真实姓名")
        @io.github.atengk.boot.core.annotation.FieldSensitive(io.github.atengk.boot.core.enums.SensitiveType.CHINESE_NAME)
        private String realName;

        @ExcelProperty("手机号")
        @io.github.atengk.boot.core.annotation.FieldSensitive(io.github.atengk.boot.core.enums.SensitiveType.MOBILE_PHONE)
        private String mobile;

        @ExcelProperty("身份证号")
        @io.github.atengk.boot.core.annotation.FieldSensitive(io.github.atengk.boot.core.enums.SensitiveType.ID_CARD)
        private String idCard;
    }

    @Test
    @DisplayName("测试兼容识别 Core 通用 @FieldSensitive 注解：导出时自动脱敏并保持源对象内存真明文")
    void testFieldSensitiveAnnotationCompatibility() {
        CoreFieldSensitiveUser user = new CoreFieldSensitiveUser();
        user.setRealName("李四五");
        user.setMobile("13987654321");
        user.setIdCard("440101198506012345");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ExcelUtil.export(out, List.of(user), CoreFieldSensitiveUser.class);

        // 内存真明文断言
        assertEquals("李四五", user.getRealName());
        assertEquals("13987654321", user.getMobile());
        assertEquals("440101198506012345", user.getIdCard());

        // 读取导出内容
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        List<CoreFieldSensitiveUser> readBack = ExcelUtil.read(in, CoreFieldSensitiveUser.class);
        assertNotNull(readBack);
        assertEquals(1, readBack.size());

        CoreFieldSensitiveUser exported = readBack.get(0);
        assertEquals("李*五", exported.getRealName(), "真实姓名应被掩码");
        assertEquals("139****4321", exported.getMobile(), "手机号应被掩码");
        assertEquals("440101********2345", exported.getIdCard(), "身份证号应被掩码");
    }
}
