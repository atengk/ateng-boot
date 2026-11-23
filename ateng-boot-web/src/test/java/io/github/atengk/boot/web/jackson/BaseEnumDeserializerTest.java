package io.github.atengk.boot.web.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.atengk.boot.core.enums.IBaseEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BaseEnumDeserializer 单元测试
 * 覆盖数值 code、字符串 code、枚举常量名（大小写不敏感）、非法值拦截、空值处理及带常量体枚举
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("BaseEnumDeserializer 强类型枚举反序列化测试")
class BaseEnumDeserializerTest {

    public enum StatusEnum implements IBaseEnum<Integer> {
        ACTIVE(1, "启用"),
        INACTIVE(2, "已停用");

        private final Integer code;
        private final String label;

        StatusEnum(Integer code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getLabel() {
            return label;
        }
    }

    public enum RoleEnum implements IBaseEnum<String> {
        ADMIN("admin", "管理员"),
        USER("user", "普通用户"),
        GUEST("guest", "访客");

        private final String code;
        private final String label;

        RoleEnum(String code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public String getCode() {
            return code;
        }

        @Override
        public String getLabel() {
            return label;
        }
    }

    public enum LevelEnum implements IBaseEnum<Long> {
        NORMAL(100L, "普通会员"),
        VIP(1001L, "VIP会员");

        private final Long code;
        private final String label;

        LevelEnum(Long code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public Long getCode() {
            return code;
        }

        @Override
        public String getLabel() {
            return label;
        }
    }

    public enum ActionEnum implements IBaseEnum<Integer> {
        CREATE(1, "创建") {
            @Override
            public String actionType() {
                return "C";
            }
        },
        DELETE(2, "删除") {
            @Override
            public String actionType() {
                return "D";
            }
        };

        private final Integer code;
        private final String label;

        ActionEnum(Integer code, String label) {
            this.code = code;
            this.label = label;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getLabel() {
            return label;
        }

        public abstract String actionType();
    }

    static class SampleRequest {
        private StatusEnum status;
        private RoleEnum role;
        private LevelEnum level;
        private ActionEnum action;

        @JsonDeserialize(using = BaseEnumDeserializer.class)
        private StatusEnum annotatedStatus;

        public StatusEnum getStatus() { return status; }
        public void setStatus(StatusEnum status) { this.status = status; }
        public RoleEnum getRole() { return role; }
        public void setRole(RoleEnum role) { this.role = role; }
        public LevelEnum getLevel() { return level; }
        public void setLevel(LevelEnum level) { this.level = level; }
        public ActionEnum getAction() { return action; }
        public void setAction(ActionEnum action) { this.action = action; }
        public StatusEnum getAnnotatedStatus() { return annotatedStatus; }
        public void setAnnotatedStatus(StatusEnum annotatedStatus) { this.annotatedStatus = annotatedStatus; }
    }

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(StatusEnum.class, new BaseEnumDeserializer<>(StatusEnum.class));
        module.addDeserializer(RoleEnum.class, new BaseEnumDeserializer<>(RoleEnum.class));
        module.addDeserializer(LevelEnum.class, new BaseEnumDeserializer<>(LevelEnum.class));
        module.addDeserializer(ActionEnum.class, new BaseEnumDeserializer<>(ActionEnum.class));
        objectMapper.registerModule(module);
    }

    @Test
    @DisplayName("数值型 code 正确反序列化为枚举实例")
    void shouldDeserializeFromNumericCode() throws Exception {
        String json = "{\"status\": 1, \"role\": \"admin\", \"level\": 1001, \"action\": 2}";
        SampleRequest request = objectMapper.readValue(json, SampleRequest.class);

        assertThat(request.getStatus()).isEqualTo(StatusEnum.ACTIVE);
        assertThat(request.getRole()).isEqualTo(RoleEnum.ADMIN);
        assertThat(request.getLevel()).isEqualTo(LevelEnum.VIP);
        assertThat(request.getAction()).isEqualTo(ActionEnum.DELETE);
    }

    @Test
    @DisplayName("字符串形态 code 正确反序列化为枚举实例（数字与长整型转字符串）")
    void shouldDeserializeFromStringCode() throws Exception {
        String json = "{\"status\": \"1\", \"role\": \"user\", \"level\": \"1001\", \"action\": \"1\"}";
        SampleRequest request = objectMapper.readValue(json, SampleRequest.class);

        assertThat(request.getStatus()).isEqualTo(StatusEnum.ACTIVE);
        assertThat(request.getRole()).isEqualTo(RoleEnum.USER);
        assertThat(request.getLevel()).isEqualTo(LevelEnum.VIP);
        assertThat(request.getAction()).isEqualTo(ActionEnum.CREATE);
    }

    @Test
    @DisplayName("枚举常量名称（大小写不敏感）正确反序列化为枚举实例")
    void shouldDeserializeFromConstantName() throws Exception {
        String json = "{\"status\": \"ACTIVE\", \"role\": \"ADMIN\", \"level\": \"VIP\", \"action\": \"CREATE\"}";
        SampleRequest request = objectMapper.readValue(json, SampleRequest.class);

        assertThat(request.getStatus()).isEqualTo(StatusEnum.ACTIVE);
        assertThat(request.getRole()).isEqualTo(RoleEnum.ADMIN);
        assertThat(request.getLevel()).isEqualTo(LevelEnum.VIP);
        assertThat(request.getAction()).isEqualTo(ActionEnum.CREATE);

        // 小写常量名测试
        String lowerJson = "{\"status\": \"active\", \"role\": \"guest\", \"level\": \"normal\", \"action\": \"delete\"}";
        SampleRequest lowerRequest = objectMapper.readValue(lowerJson, SampleRequest.class);

        assertThat(lowerRequest.getStatus()).isEqualTo(StatusEnum.ACTIVE);
        assertThat(lowerRequest.getRole()).isEqualTo(RoleEnum.GUEST);
        assertThat(lowerRequest.getLevel()).isEqualTo(LevelEnum.NORMAL);
        assertThat(lowerRequest.getAction()).isEqualTo(ActionEnum.DELETE);
    }

    @Test
    @DisplayName("null 与空字符串以及空白字符安全反序列化为 null")
    void shouldDeserializeNullAndEmptyStringToNull() throws Exception {
        String json = "{\"status\": null, \"role\": \"\", \"level\": \"   \"}";
        SampleRequest request = objectMapper.readValue(json, SampleRequest.class);

        assertThat(request.getStatus()).isNull();
        assertThat(request.getRole()).isNull();
        assertThat(request.getLevel()).isNull();
    }

    @Test
    @DisplayName("无效数值 code 抛出 InvalidFormatException 异常并包含有效候选列表")
    void shouldThrowInvalidFormatExceptionOnInvalidNumber() {
        String json = "{\"status\": 999}";
        assertThatThrownBy(() -> objectMapper.readValue(json, SampleRequest.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("999")
                .hasMessageContaining("StatusEnum");
    }

    @Test
    @DisplayName("无效字符串 code 或常量名抛出 InvalidFormatException 异常并包含有效候选列表")
    void shouldThrowInvalidFormatExceptionOnInvalidString() {
        String json = "{\"role\": \"super_admin\"}";
        assertThatThrownBy(() -> objectMapper.readValue(json, SampleRequest.class))
                .isInstanceOf(InvalidFormatException.class)
                .hasMessageContaining("super_admin")
                .hasMessageContaining("RoleEnum");
    }

    @Test
    @DisplayName("非预期 JSON 对象/数组类型抛出 MismatchedInputException 异常")
    void shouldThrowExceptionOnUnexpectedToken() {
        String objectJson = "{\"status\": {}}";
        assertThatThrownBy(() -> objectMapper.readValue(objectJson, SampleRequest.class))
                .isInstanceOf(MismatchedInputException.class);

        String arrayJson = "{\"status\": []}";
        assertThatThrownBy(() -> objectMapper.readValue(arrayJson, SampleRequest.class))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    @DisplayName("通过 @JsonDeserialize 注解声明的反序列化器正常工作")
    void shouldWorkWithContextualAnnotation() throws Exception {
        ObjectMapper plainMapper = new ObjectMapper();
        String json = "{\"annotatedStatus\": 2}";
        SampleRequest request = plainMapper.readValue(json, SampleRequest.class);

        assertThat(request.getAnnotatedStatus()).isEqualTo(StatusEnum.INACTIVE);

        String nameJson = "{\"annotatedStatus\": \"ACTIVE\"}";
        SampleRequest nameRequest = plainMapper.readValue(nameJson, SampleRequest.class);

        assertThat(nameRequest.getAnnotatedStatus()).isEqualTo(StatusEnum.ACTIVE);
    }
}
