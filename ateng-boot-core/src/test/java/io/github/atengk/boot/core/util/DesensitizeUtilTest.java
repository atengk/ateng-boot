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

    @Test
    @DisplayName("国内地址脱敏验证")
    void shouldDesensitizeAddress() {
        assertNull(DesensitizeUtil.address(null));
        assertEquals("", DesensitizeUtil.address(""));
        assertEquals("北京", DesensitizeUtil.address("北京"));
        assertEquals("北京****", DesensitizeUtil.address("北京市朝阳区"));
        assertEquals("北京市海淀区*********", DesensitizeUtil.address("北京市海淀区中关村南大街1号院"));
        assertEquals("广东省深圳市*************", DesensitizeUtil.address("广东省深圳市南山区高新南一道科技园大厦"));
    }

    @Test
    @DisplayName("车牌号码脱敏验证（保留前2后2，兼容新能源与间隔符）")
    void shouldDesensitizeCarLicense() {
        assertNull(DesensitizeUtil.carLicense(null));
        assertEquals("", DesensitizeUtil.carLicense(""));
        assertEquals("京A", DesensitizeUtil.carLicense("京A"));
        // 7位传统燃油车
        assertEquals("京A***88", DesensitizeUtil.carLicense("京A12388"));
        // 8位新能源车牌
        assertEquals("粤B****45", DesensitizeUtil.carLicense("粤BD12345"));
        // 带点号或间隔符场景
        assertEquals("京A·***88", DesensitizeUtil.carLicense("京A·12388"));
        assertEquals("苏E-***99", DesensitizeUtil.carLicense("苏E-88899"));
    }

    @Test
    @DisplayName("IPv4 地址脱敏验证（支持末位段与末两段掩码）")
    void shouldDesensitizeIpv4() {
        assertNull(DesensitizeUtil.ipv4(null));
        assertEquals("", DesensitizeUtil.ipv4(""));
        // 默认掩码末位段
        assertEquals("192.168.1.*", DesensitizeUtil.ipv4("192.168.1.100"));
        assertEquals("10.0.0.*", DesensitizeUtil.ipv4("10.0.0.1"));
        // 掩码末两段
        assertEquals("192.168.*.*", DesensitizeUtil.ipv4("192.168.1.100", true));
        assertEquals("10.0.*.*", DesensitizeUtil.ipv4("10.0.0.1", true));
        // 非标 IP 防御
        assertEquals("invalid-ip", DesensitizeUtil.ipv4("invalid-ip"));
        assertEquals("192.168", DesensitizeUtil.ipv4("192.168"));
    }

    @Test
    @DisplayName("IPv6 地址脱敏验证（压缩保留首尾段，中间 ****）")
    void shouldDesensitizeIpv6() {
        assertNull(DesensitizeUtil.ipv6(null));
        assertEquals("", DesensitizeUtil.ipv6(""));
        assertEquals("2001:****:7334", DesensitizeUtil.ipv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334"));
        assertEquals("fe80:****:1", DesensitizeUtil.ipv6("fe80::1"));
        assertEquals("::1", DesensitizeUtil.ipv6("::1"));
        assertEquals("no-colon-ip", DesensitizeUtil.ipv6("no-colon-ip"));
    }

    @Test
    @DisplayName("统一社会信用代码脱敏验证（18位保留前2后4）")
    void shouldDesensitizeSocialCreditCode() {
        assertNull(DesensitizeUtil.socialCreditCode(null));
        assertEquals("", DesensitizeUtil.socialCreditCode(""));
        assertEquals("91************BC12", DesensitizeUtil.socialCreditCode("91110000MA001ABC12"));
        // 短代码/非18位防御
        assertEquals("12345", DesensitizeUtil.socialCreditCode("12345"));
        assertEquals("91*********1234", DesensitizeUtil.socialCreditCode("910000000001234"));
    }

    @Test
    @DisplayName("首尾保留掩码验证（maskKeepHeadTail / maskKeepHead / maskKeepTail）")
    void shouldDesensitizeKeepHeadTail() {
        assertNull(DesensitizeUtil.maskKeepHeadTail(null, 2, 2));
        assertEquals("", DesensitizeUtil.maskKeepHeadTail("", 2, 2));
        assertEquals("12******90", DesensitizeUtil.maskKeepHeadTail("1234567890", 2, 2));
        assertEquals("12######90", DesensitizeUtil.maskKeepHeadTail("1234567890", 2, 2, '#'));
        assertEquals("123*******", DesensitizeUtil.maskKeepHead("1234567890", 3));
        assertEquals("*******890", DesensitizeUtil.maskKeepTail("1234567890", 3));
        // keepHead + keepTail >= length 超长防御：原样安全返回，严禁越界或异常
        assertEquals("12345", DesensitizeUtil.maskKeepHeadTail("12345", 3, 3));
        assertEquals("12345", DesensitizeUtil.maskKeepHeadTail("12345", 2, 3));
        assertEquals("12345", DesensitizeUtil.maskKeepHead("12345", 6));
        assertEquals("12345", DesensitizeUtil.maskKeepTail("12345", 5));
        // 负数保留防御
        assertEquals("*****", DesensitizeUtil.maskKeepHeadTail("12345", -1, -2));
    }
}
