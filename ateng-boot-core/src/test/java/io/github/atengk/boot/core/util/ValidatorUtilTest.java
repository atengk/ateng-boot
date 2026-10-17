package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidatorUtil 业务格式校验单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("ValidatorUtil 业务格式校验单元测试")
class ValidatorUtilTest {

    @Test
    @DisplayName("测试手机号合法与非法校验")
    void testIsMobile() {
        assertThat(ValidatorUtil.isMobile("13812345678")).isTrue();
        assertThat(ValidatorUtil.isMobile("18888888888")).isTrue();
        assertThat(ValidatorUtil.isMobile("19912345678")).isTrue();

        assertThat(ValidatorUtil.isMobile("12812345678")).isFalse();
        assertThat(ValidatorUtil.isMobile("1381234567")).isFalse();
        assertThat(ValidatorUtil.isMobile("138123456789")).isFalse();
        assertThat(ValidatorUtil.isMobile("abc12345678")).isFalse();
        assertThat(ValidatorUtil.isMobile(null)).isFalse();
        assertThat(ValidatorUtil.isMobile("")).isFalse();
    }

    @Test
    @DisplayName("测试固定电话与综合电话校验")
    void testIsTelAndPhone() {
        assertThat(ValidatorUtil.isTel("010-12345678")).isTrue();
        assertThat(ValidatorUtil.isTel("0755-88889999")).isTrue();
        assertThat(ValidatorUtil.isTel("075588889999")).isTrue();
        assertThat(ValidatorUtil.isTel("1234567")).isTrue();

        assertThat(ValidatorUtil.isPhone("13812345678")).isTrue();
        assertThat(ValidatorUtil.isPhone("010-88888888")).isTrue();
        assertThat(ValidatorUtil.isPhone("invalid-phone")).isFalse();
    }

    @Test
    @DisplayName("测试电子邮箱校验")
    void testIsEmail() {
        assertThat(ValidatorUtil.isEmail("user@example.com")).isTrue();
        assertThat(ValidatorUtil.isEmail("admin.service+dev@sub.company.org")).isTrue();

        assertThat(ValidatorUtil.isEmail("plainaddress")).isFalse();
        assertThat(ValidatorUtil.isEmail("@missingusername.com")).isFalse();
        assertThat(ValidatorUtil.isEmail("user@.com")).isFalse();
        assertThat(ValidatorUtil.isEmail(null)).isFalse();
    }

    @Test
    @DisplayName("测试 IPv4 与 IPv6 校验")
    void testIsIp() {
        assertThat(ValidatorUtil.isIpv4("192.168.1.1")).isTrue();
        assertThat(ValidatorUtil.isIpv4("127.0.0.1")).isTrue();
        assertThat(ValidatorUtil.isIpv4("255.255.255.255")).isTrue();
        assertThat(ValidatorUtil.isIpv4("256.1.1.1")).isFalse();
        assertThat(ValidatorUtil.isIpv4("192.168.1")).isFalse();

        assertThat(ValidatorUtil.isIpv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334")).isTrue();
        assertThat(ValidatorUtil.isIpv6("::1")).isTrue();

        assertThat(ValidatorUtil.isIp("10.0.0.1")).isTrue();
        assertThat(ValidatorUtil.isIp("::1")).isTrue();
        assertThat(ValidatorUtil.isIp("not-an-ip")).isFalse();
    }

    @Test
    @DisplayName("测试中国车牌号校验")
    void testIsCarLicense() {
        assertThat(ValidatorUtil.isCarLicense("京A88888")).isTrue();
        assertThat(ValidatorUtil.isCarLicense("粤B12345D")).isTrue(); // 新能源绿牌
        assertThat(ValidatorUtil.isCarLicense("沪AD12345")).isTrue();

        assertThat(ValidatorUtil.isCarLicense("美A88888")).isFalse();
        assertThat(ValidatorUtil.isCarLicense("京12345")).isFalse();
    }

    @Test
    @DisplayName("测试纯中文字符校验")
    void testIsChinese() {
        assertThat(ValidatorUtil.isChinese("张三")).isTrue();
        assertThat(ValidatorUtil.isChinese("中华人民共和国")).isTrue();

        assertThat(ValidatorUtil.isChinese("张三John")).isFalse();
        assertThat(ValidatorUtil.isChinese("123")).isFalse();
        assertThat(ValidatorUtil.isChinese("")).isFalse();
    }

    @Test
    @DisplayName("测试统一社会信用代码校验")
    void testIsSocialCreditCode() {
        assertThat(ValidatorUtil.isSocialCreditCode("91110000000000001X")).isTrue();
        assertThat(ValidatorUtil.isSocialCreditCode("92310115MA1H8ABCDE")).isTrue();

        // 包含不允许字符 (I, O, Z, S, V) 或长度错误
        assertThat(ValidatorUtil.isSocialCreditCode("91110000000000001I")).isFalse();
        assertThat(ValidatorUtil.isSocialCreditCode("123456789")).isFalse();
    }

    @Test
    @DisplayName("测试标准 URL 校验")
    void testIsUrl() {
        assertThat(ValidatorUtil.isUrl("https://github.com/atengk/ateng-boot")).isTrue();
        assertThat(ValidatorUtil.isUrl("http://localhost:8080/api/v1/users?page=1")).isTrue();

        assertThat(ValidatorUtil.isUrl("ftp://files.example.com")).isFalse();
        assertThat(ValidatorUtil.isUrl("just-a-string")).isFalse();
    }

    @Test
    @DisplayName("测试身份证加权校验码与出生日期验证")
    void testIsIdCard() {
        // 构造合法的 18 位身份证 (北京东城区 1990-03-07，通过加权计算合法性)
        // 110101 19900307 2345:
        // sum = 1*7 + 1*9 + 0*10 + 1*5 + 0*8 + 1*4 + 1*2 + 9*1 + 9*6 + 0*3 + 0*7 + 3*9 + 0*10 + 7*5 + 2*8 + 3*4 + 4*2
        // 计算校验码对应位:
        // 这里用一个真实合法的已知测试身份证或直接根据算法验证
        // 例如：11010519491231002X (110105 19491231 002X)
        // 前17位: 1,1,0,1,0,5,1,9,4,9,1,2,3,1,0,0,2
        // sum = 1*7 + 1*9 + 0*10 + 1*5 + 0*8 + 5*4 + 1*2 + 9*1 + 4*6 + 9*3 + 1*7 + 2*9 + 3*10 + 1*5 + 0*8 + 0*4 + 2*2
        // sum = 7 + 9 + 0 + 5 + 0 + 20 + 2 + 9 + 24 + 27 + 7 + 18 + 30 + 5 + 0 + 0 + 4 = 167
        // 167 % 11 = 2 -> ID_CARD_CHECK_CODES[2] = 'X' -> 合法！
        assertThat(ValidatorUtil.isIdCard("11010519491231002X")).isTrue();
        assertThat(ValidatorUtil.isIdCard("11010519491231002x")).isTrue(); // 小写 x 容错

        // 校验码不匹配 (末位应为 X，篡改为 0)
        assertThat(ValidatorUtil.isIdCard("110105194912310020")).isFalse();

        // 出生日期非法 (不存在的 2 月 30 日)
        assertThat(ValidatorUtil.isIdCard("11010519900230002X")).isFalse();

        // 长度不足或非数字
        assertThat(ValidatorUtil.isIdCard("123456")).isFalse();
        assertThat(ValidatorUtil.isIdCard("abcdefghijklmnopqr")).isFalse();
        assertThat(ValidatorUtil.isIdCard(null)).isFalse();
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    static class UserDTO {
        @jakarta.validation.constraints.NotNull(groups = io.github.atengk.boot.core.validation.ValidationGroups.Update.class, message = "修改时 ID 不能为空")
        private Long id;

        @jakarta.validation.constraints.NotBlank(message = "用户名不能为空")
        private String username;

        @jakarta.validation.constraints.Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        private String mobile;
    }

    @Test
    @DisplayName("测试 POJO 编程式校验：合法实体")
    void testValidateSuccess() {
        UserDTO user = new UserDTO(null, "Ateng", "13800138000");
        ValidatorUtil.validate(user);
        assertThat(ValidatorUtil.validateResult(user)).isEmpty();
    }

    @Test
    @DisplayName("测试 POJO 编程式校验：字段违规快速失败")
    void testValidateFailure() {
        UserDTO user = new UserDTO(null, "", "invalid-mobile");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ValidatorUtil.validate(user))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessageMatching(".*(用户名不能为空|手机号格式不正确).*");

        assertThat(ValidatorUtil.validateResult(user)).hasSize(2);
    }

    @Test
    @DisplayName("测试 POJO 编程式校验：指定校验分组 ValidationGroups.Update")
    void testValidateWithGroup() {
        // 未传 id，但在 Update 分组下要求 id 必填
        UserDTO user = new UserDTO(null, "Ateng", "13800138000");

        // 默认分组不报错
        ValidatorUtil.validate(user);

        // Update 分组报错
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ValidatorUtil.validate(user, io.github.atengk.boot.core.validation.ValidationGroups.Update.class))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessage("修改时 ID 不能为空");
    }

    @Test
    @DisplayName("测试单属性编程式校验 validateProperty")
    void testValidateProperty() {
        UserDTO user = new UserDTO(null, "Ateng", "bad-phone");

        // 校验合法的 username 属性通过
        ValidatorUtil.validateProperty(user, "username");

        // 校验非法的 mobile 属性抛出异常
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ValidatorUtil.validateProperty(user, "mobile"))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessage("手机号格式不正确");
    }

    @Test
    @DisplayName("测试 POJO 校验 null 防御")
    void testValidateNullProtection() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ValidatorUtil.validate(null))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessage("待校验对象不能为 null");

        assertThat(ValidatorUtil.validateResult(null)).isEmpty();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ValidatorUtil.validateProperty(null, "username"))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessage("待校验对象不能为 null");
    }
}
