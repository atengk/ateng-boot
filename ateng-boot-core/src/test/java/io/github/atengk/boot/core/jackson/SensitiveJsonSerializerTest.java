package io.github.atengk.boot.core.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.util.JsonUtil;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 视图层敏感数据动态脱敏序列化器单测
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("视图层敏感数据动态脱敏序列化器单测")
class SensitiveJsonSerializerTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonUtil.getObjectMapper();
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
        assertTrue(json.contains("\"chineseName\":\"诸**明\""));
        assertTrue(json.contains("\"mobilePhone\":\"138****5678\""));
        assertTrue(json.contains("\"fixedPhone\":\"010-******78\""));
        assertTrue(json.contains("\"idCard\":\"110101********2345\""));
        assertTrue(json.contains("\"email\":\"z******n@example.com\""));
        assertTrue(json.contains("\"bankCard\":\"622202*********0123\""));
        assertTrue(json.contains("\"password\":\"******\""));
        assertTrue(json.contains("\"customMask\":\"AB####GHIJ\""));
        assertTrue(json.contains("\"address\":\"北京市海淀区*********\""));
        assertTrue(json.contains("\"carLicense\":\"京A***88\""));
        assertTrue(json.contains("\"ipv4\":\"192.168.1.*\""));
        assertTrue(json.contains("\"ipv6\":\"2001:****:7334\""));
        assertTrue(json.contains("\"socialCreditCode\":\"91************BC12\""));
        assertTrue(json.contains("\"keepHeadTail\":\"12******90\""));
        assertTrue(json.contains("\"normalField\":\"unmasked\""));

        // 核心断言：内存中实体字段值绝对保持 100% 原始真明文，未被污染！
        assertEquals("诸葛孔明", dto.getChineseName());
        assertEquals("13812345678", dto.getMobilePhone());
        assertEquals("010-12345678", dto.getFixedPhone());
        assertEquals("110101199003072345", dto.getIdCard());
        assertEquals("zhangsan@example.com", dto.getEmail());
        assertEquals("6222021234567890123", dto.getBankCard());
        assertEquals("mySecretPwd123", dto.getPassword());
        assertEquals("ABCDEFGHIJ", dto.getCustomMask());
        assertEquals("北京市海淀区中关村南大街1号院", dto.getAddress());
        assertEquals("京A12388", dto.getCarLicense());
        assertEquals("192.168.1.100", dto.getIpv4());
        assertEquals("2001:0db8:85a3:0000:0000:8a2e:0370:7334", dto.getIpv6());
        assertEquals("91110000MA001ABC12", dto.getSocialCreditCode());
        assertEquals("1234567890", dto.getKeepHeadTail());
        assertEquals("unmasked", dto.getNormalField());
    }

    @Test
    @DisplayName("null 值与空串安全：null 序列化为 null，空串序列化为空串")
    void shouldHandleNullAndEmptyCorrectly() throws Exception {
        SensitiveUserDTO dto = new SensitiveUserDTO();
        dto.setMobilePhone(null);
        dto.setEmail("");

        String json = objectMapper.writeValueAsString(dto);

        assertTrue(json.contains("\"mobilePhone\":null"));
        assertTrue(json.contains("\"email\":\"\""));
    }
}
