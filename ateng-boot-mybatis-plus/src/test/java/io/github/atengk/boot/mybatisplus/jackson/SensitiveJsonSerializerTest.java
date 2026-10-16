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
