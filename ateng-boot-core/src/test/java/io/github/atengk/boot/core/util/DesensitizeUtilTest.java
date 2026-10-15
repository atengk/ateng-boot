package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 敏感数据脱敏工具单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("敏感数据脱敏工具单测")
class DesensitizeUtilTest {

    @Test
    @DisplayName("通用 mask 算法边界与正常场景验证")
    void shouldMaskCorrectly() {
        assertNull(DesensitizeUtil.mask(null, 0, 5));
        assertEquals("", DesensitizeUtil.mask("", 0, 5));
        assertEquals("123****89", DesensitizeUtil.mask("123456789", 3, 7));
        assertEquals("123####89", DesensitizeUtil.mask("123456789", 3, 7, '#'));
        // 起始越界防御
        assertEquals("***45", DesensitizeUtil.mask("12345", -2, 3));
        // 结束越界防御
        assertEquals("12***", DesensitizeUtil.mask("12345", 2, 10));
        // start >= end 无打码
        assertEquals("12345", DesensitizeUtil.mask("12345", 3, 2));
        assertEquals("12345", DesensitizeUtil.mask("12345", 5, 8));
    }

    @Test
    @DisplayName("中文姓名脱敏验证")
    void shouldDesensitizeChineseName() {
        assertNull(DesensitizeUtil.chineseName(null));
        assertEquals("", DesensitizeUtil.chineseName(""));
        assertEquals("张", DesensitizeUtil.chineseName("张"));
        assertEquals("张*", DesensitizeUtil.chineseName("张三"));
        assertEquals("李*五", DesensitizeUtil.chineseName("李四五"));
        assertEquals("诸**明", DesensitizeUtil.chineseName("诸葛孔明"));
        assertEquals("买*****都", DesensitizeUtil.chineseName("买买提·阿不都"));
    }

    @Test
    @DisplayName("手机号脱敏验证（前3后4）")
    void shouldDesensitizeMobilePhone() {
        assertNull(DesensitizeUtil.mobilePhone(null));
        assertEquals("", DesensitizeUtil.mobilePhone(""));
        assertEquals("138****5678", DesensitizeUtil.mobilePhone("13812345678"));
        assertEquals("189****1111", DesensitizeUtil.mobilePhone("18900001111"));
        // 短号码防御
        assertEquals("1***5", DesensitizeUtil.mobilePhone("12345"));
    }

    @Test
    @DisplayName("固定电话脱敏验证")
    void shouldDesensitizeFixedPhone() {
        assertNull(DesensitizeUtil.fixedPhone(null));
        assertEquals("", DesensitizeUtil.fixedPhone(""));
        assertEquals("010-******78", DesensitizeUtil.fixedPhone("010-12345678"));
        assertEquals("0755-******99", DesensitizeUtil.fixedPhone("0755-88889999"));
    }

    @Test
    @DisplayName("身份证脱敏验证（前6后4）")
    void shouldDesensitizeIdCard() {
        assertNull(DesensitizeUtil.idCard(null));
        assertEquals("", DesensitizeUtil.idCard(""));
        assertEquals("110101********2345", DesensitizeUtil.idCard("110101199003072345"));
        assertEquals("110101*****7234", DesensitizeUtil.idCard("110101900307234"));
    }

    @Test
    @DisplayName("电子邮箱脱敏验证")
    void shouldDesensitizeEmail() {
        assertNull(DesensitizeUtil.email(null));
        assertEquals("", DesensitizeUtil.email(""));
        assertEquals("z******n@example.com", DesensitizeUtil.email("zhangsan@example.com"));
        assertEquals("a*@example.com", DesensitizeUtil.email("ab@example.com"));
        assertEquals("a@example.com", DesensitizeUtil.email("a@example.com"));
        assertEquals("invalid-email", DesensitizeUtil.email("invalid-email"));
    }

    @Test
    @DisplayName("银行卡号脱敏验证（前6后4）")
    void shouldDesensitizeBankCard() {
        assertNull(DesensitizeUtil.bankCard(null));
        assertEquals("", DesensitizeUtil.bankCard(""));
        assertEquals("622202*********0123", DesensitizeUtil.bankCard("6222021234567890123"));
        assertEquals("622588******6789", DesensitizeUtil.bankCard("6225880123456789"));
    }

    @Test
    @DisplayName("密码脱敏验证（全掩码且固定长度，支持默认与自定义）")
    void shouldDesensitizePassword() {
        assertNull(DesensitizeUtil.password(null));
        assertEquals("", DesensitizeUtil.password(""));
        assertEquals("******", DesensitizeUtil.password("mySecret123"));
        assertEquals("******", DesensitizeUtil.password("veryLongComplexPassword!@#$"));

        // 自定义掩码测试
        assertNull(DesensitizeUtil.password(null, "###"));
        assertEquals("", DesensitizeUtil.password("", "###"));
        assertEquals("########", DesensitizeUtil.password("mySecret123", "########"));
        assertEquals("******", DesensitizeUtil.password("mySecret123", null));
    }
}
