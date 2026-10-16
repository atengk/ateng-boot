package io.github.atengk.boot.mybatisplus.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.mybatisplus.annotation.FieldSensitive;
import io.github.atengk.boot.mybatisplus.enums.SensitiveType;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视图层敏感数据动态脱敏序列化器单测
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("视图层敏感数据动态脱敏序列化器单测")
class SensitiveJsonSerializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Getter
    @Setter
    static class SensitiveUserDTO {
        @FieldSensitive(SensitiveType.CHINESE_NAME)
        private String chineseName;

        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String mobilePhone;

        @FieldSensitive(SensitiveType.FIXED_PHONE)
        private String fixedPhone;

        @FieldSensitive(SensitiveType.ID_CARD)
        private String idCard;

        @FieldSensitive(SensitiveType.EMAIL)
        private String email;

        @FieldSensitive(SensitiveType.BANK_CARD)
        private String bankCard;

        @FieldSensitive(SensitiveType.PASSWORD)
        private String password;

        @FieldSensitive(value = SensitiveType.CUSTOM, startInclude = 2, endExclude = 6, maskChar = '#')
        private String customMask;

        @FieldSensitive(SensitiveType.ADDRESS)
        private String address;

        @FieldSensitive(SensitiveType.CAR_LICENSE)
        private String carLicense;

        @FieldSensitive(SensitiveType.IPV4)
        private String ipv4;

        @FieldSensitive(SensitiveType.IPV6)
        private String ipv6;

        @FieldSensitive(SensitiveType.SOCIAL_CREDIT_CODE)
        private String socialCreditCode;

        @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 2, keepTail = 2)
        private String keepHeadTail;

        private String normalField;
    }

    @Test
    @DisplayName("动态脱敏与内存明文不变量：输出 JSON 时动态掩码，但 Java 内存对象字段保持 100% 原始真实明文")
    void shouldMaskInJsonWhileKeepingInMemoryPlaintext() throws Exception {
        SensitiveUserDTO dto = new SensitiveUserDTO();
        dto.setChineseName("诸葛孔明");
        dto.setMobilePhone("13812345678");
        dto.setFixedPhone("010-12345678");
        dto.setIdCard("110101199003072345");
        dto.setEmail("zhangsan@example.com");
        dto.setBankCard("6222021234567890123");
        dto.setPassword("mySecretPwd123");
        dto.setCustomMask("ABCDEFGHIJ");
        dto.setAddress("北京市海淀区中关村南大街1号院");
        dto.setCarLicense("京A12388");
        dto.setIpv4("192.168.1.100");
        dto.setIpv6("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        dto.setSocialCreditCode("91110000MA001ABC12");
        dto.setKeepHeadTail("1234567890");
        dto.setNormalField("unmasked");

        String json = objectMapper.writeValueAsString(dto);

        // 断言序列化出的 JSON 呈现掩码
        assertThat(json).contains("\"chineseName\":\"诸**明\"");
        assertThat(json).contains("\"mobilePhone\":\"138****5678\"");
        assertThat(json).contains("\"fixedPhone\":\"010-******78\"");
        assertThat(json).contains("\"idCard\":\"110101********2345\"");
        assertThat(json).contains("\"email\":\"z******n@example.com\"");
        assertThat(json).contains("\"bankCard\":\"622202*********0123\"");
        assertThat(json).contains("\"password\":\"******\"");
        assertThat(json).contains("\"customMask\":\"AB####GHIJ\"");
        assertThat(json).contains("\"address\":\"北京市海淀区*********\"");
        assertThat(json).contains("\"carLicense\":\"京A***88\"");
        assertThat(json).contains("\"ipv4\":\"192.168.1.*\"");
        assertThat(json).contains("\"ipv6\":\"2001:****:7334\"");
        assertThat(json).contains("\"socialCreditCode\":\"91************BC12\"");
        assertThat(json).contains("\"keepHeadTail\":\"12******90\"");
        assertThat(json).contains("\"normalField\":\"unmasked\"");

        // 核心断言：内存中实体字段值绝对保持 100% 原始真明文，未被污染！
        assertThat(dto.getChineseName()).isEqualTo("诸葛孔明");
        assertThat(dto.getMobilePhone()).isEqualTo("13812345678");
        assertThat(dto.getFixedPhone()).isEqualTo("010-12345678");
        assertThat(dto.getIdCard()).isEqualTo("110101199003072345");
        assertThat(dto.getEmail()).isEqualTo("zhangsan@example.com");
        assertThat(dto.getBankCard()).isEqualTo("6222021234567890123");
        assertThat(dto.getPassword()).isEqualTo("mySecretPwd123");
        assertThat(dto.getCustomMask()).isEqualTo("ABCDEFGHIJ");
        assertThat(dto.getAddress()).isEqualTo("北京市海淀区中关村南大街1号院");
        assertThat(dto.getCarLicense()).isEqualTo("京A12388");
        assertThat(dto.getIpv4()).isEqualTo("192.168.1.100");
        assertThat(dto.getIpv6()).isEqualTo("2001:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertThat(dto.getSocialCreditCode()).isEqualTo("91110000MA001ABC12");
        assertThat(dto.getKeepHeadTail()).isEqualTo("1234567890");
        assertThat(dto.getNormalField()).isEqualTo("unmasked");
    }

    @Test
    @DisplayName("null 值与空串安全：null 序列化为 null，空串序列化为空串")
    void shouldHandleNullAndEmptyCorrectly() throws Exception {
        SensitiveUserDTO dto = new SensitiveUserDTO();
        dto.setMobilePhone(null);
        dto.setEmail("");

        String json = objectMapper.writeValueAsString(dto);

        assertThat(json).contains("\"mobilePhone\":null");
        assertThat(json).contains("\"email\":\"\"");
    }
}
