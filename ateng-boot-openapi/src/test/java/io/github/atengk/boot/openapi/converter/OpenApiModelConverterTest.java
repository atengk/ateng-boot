package io.github.atengk.boot.openapi.converter;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.enums.SensitiveType;
import io.github.atengk.boot.core.validation.ValidList;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverterContextImpl;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.models.media.ArraySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenApiModelConverter 接口契约模型治理增强器单元测试
 *
 * @author Ateng
 * @since 2026-10-30
 */
@DisplayName("OpenApiModelConverter 接口契约模型治理增强器单元测试")
class OpenApiModelConverterTest {

    private OpenApiModelConverter converter;

    /**
     * 待测整数型通用枚举
     */
    enum TestStatusEnum implements IBaseEnum<Integer> {
        ENABLE(1, "启用状态"),
        DISABLE(0, "禁用状态");

        private final Integer code;
        private final String label;

        TestStatusEnum(Integer code, String label) {
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

    /**
     * 待测字符串型通用枚举
     */
    enum TestGenderEnum implements IBaseEnum<String> {
        MALE("M", "男性"),
        FEMALE("F", "女性");

        private final String code;
        private final String label;

        TestGenderEnum(String code, String label) {
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

    /**
     * 普通 Java 枚举（未实现 IBaseEnum）
     */
    enum NormalEnum {
        RED,
        GREEN,
        BLUE
    }

    /**
     * 待测复合实体对象
     */
    static class UserTestVO implements Serializable {
        @Schema(description = "用户唯一ID")
        private Long id;

        @Schema(description = "联系手机号码")
        @FieldSensitive(SensitiveType.MOBILE_PHONE)
        private String phone;

        @Schema(description = "身份证号码")
        @FieldSensitive(SensitiveType.ID_CARD)
        private String idCard;

        @Schema(description = "用户账号状态")
        private TestStatusEnum status;

        @Schema(description = "用户性别")
        private TestGenderEnum gender;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public String getIdCard() {
            return idCard;
        }

        public void setIdCard(String idCard) {
            this.idCard = idCard;
        }

        public TestStatusEnum getStatus() {
            return status;
        }

        public void setStatus(TestStatusEnum status) {
            this.status = status;
        }

        public TestGenderEnum getGender() {
            return gender;
        }

        public void setGender(TestGenderEnum gender) {
            this.gender = gender;
        }

        @Schema(description = "普通颜色枚举")
        private NormalEnum color;

        @Schema(description = "自定义首尾保留卡号")
        @FieldSensitive(value = SensitiveType.KEEP_HEAD_TAIL, keepHead = 4, keepTail = 4)
        private String customCard;

        public String getCustomCard() {
            return customCard;
        }

        public void setCustomCard(String customCard) {
            this.customCard = customCard;
        }

        public NormalEnum getColor() {
            return color;
        }

        public void setColor(NormalEnum color) {
            this.color = color;
        }
    }

    @BeforeEach
    void setUp() {
        converter = new OpenApiModelConverter();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> getEnumValues(io.swagger.v3.oas.models.media.Schema<?> schema) {
        return (List<Object>) (List<?>) schema.getEnum();
    }

    @Test
    @DisplayName("IBaseEnum 整数枚举解析：映射为 integer 强类型 Schema，注入真实 code 与 Markdown 字典")
    void testIntegerBaseEnumResolution() {
        AnnotatedType type = new AnnotatedType(TestStatusEnum.class);
        ModelConverterContextImpl context = new ModelConverterContextImpl(Collections.singletonList(converter));

        io.swagger.v3.oas.models.media.Schema<?> schema = converter.resolve(type, context, Collections.emptyIterator());

        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo("integer");
        assertThat(getEnumValues(schema)).containsExactly(1, 0);
        assertThat(schema.getExample()).isEqualTo(1);
        assertThat(schema.getDescription())
                .contains("**枚举选项**：")
                .contains("- `1`: 启用状态 (ENABLE)")
                .contains("- `0`: 禁用状态 (DISABLE)");
    }

    @Test
    @DisplayName("IBaseEnum 字符串枚举解析：映射为 string 强类型 Schema，注入真实 code 与 Markdown 字典")
    void testStringBaseEnumResolution() {
        AnnotatedType type = new AnnotatedType(TestGenderEnum.class);
        ModelConverterContextImpl context = new ModelConverterContextImpl(Collections.singletonList(converter));

        io.swagger.v3.oas.models.media.Schema<?> schema = converter.resolve(type, context, Collections.emptyIterator());

        assertThat(schema).isNotNull();
        assertThat(schema.getType()).isEqualTo("string");
        assertThat(getEnumValues(schema)).containsExactly("M", "F");
        assertThat(schema.getExample()).isEqualTo("M");
        assertThat(schema.getDescription())
                .contains("**枚举选项**：")
                .contains("- `M`: 男性 (MALE)")
                .contains("- `F`: 女性 (FEMALE)");
    }

    @Test
    @DisplayName("ValidList 批量入参解析：转换为原生 ArraySchema，消除对象 list 包装")
    void testValidListResolution() throws NoSuchFieldException {
        // 模拟 ValidList<UserTestVO> 属性类型
        class BatchContainer {
            public ValidList<UserTestVO> users;
        }

        java.lang.reflect.Type validListType = BatchContainer.class.getField("users").getGenericType();
        AnnotatedType type = new AnnotatedType(validListType);
        ModelConverterContextImpl context = new ModelConverterContextImpl(ModelConverters.getInstance().getConverters());

        io.swagger.v3.oas.models.media.Schema<?> schema = converter.resolve(type, context, Collections.emptyIterator());

        assertThat(schema).isInstanceOf(ArraySchema.class);
        ArraySchema arraySchema = (ArraySchema) schema;
        assertThat(arraySchema.getItems()).isNotNull();
    }

    @Test
    @DisplayName("复合实体属性解析：自动为 @FieldSensitive 追加脱敏警示，为 IBaseEnum 追加代码与字典")
    void testCompositeEntityResolution() {
        Map<String, io.swagger.v3.oas.models.media.Schema> models = ModelConverters.getInstance().read(UserTestVO.class);

        assertThat(models).containsKey("UserTestVO");
        io.swagger.v3.oas.models.media.Schema<?> userModel = models.get("UserTestVO");
        Map<String, io.swagger.v3.oas.models.media.Schema> properties = userModel.getProperties();
        assertThat(properties).isNotNull();

        // 1. 验证 @FieldSensitive 手机号
        io.swagger.v3.oas.models.media.Schema<?> phoneSchema = properties.get("phone");
        assertThat(phoneSchema).isNotNull();
        assertThat(phoneSchema.getDescription())
                .contains("[脱敏字段: 手机号码掩码]")
                .contains("联系手机号码");
        assertThat(phoneSchema.getExample()).isEqualTo("138****5678");

        // 2. 验证 @FieldSensitive 身份证
        io.swagger.v3.oas.models.media.Schema<?> idCardSchema = properties.get("idCard");
        assertThat(idCardSchema).isNotNull();
        assertThat(idCardSchema.getDescription())
                .contains("[脱敏字段: 身份证号掩码]")
                .contains("身份证号码");
        assertThat(idCardSchema.getExample()).isEqualTo("110101********2345");

        // 3. 验证 IBaseEnum status
        io.swagger.v3.oas.models.media.Schema<?> statusSchema = properties.get("status");
        assertThat(statusSchema).isNotNull();
        assertThat(statusSchema.getType()).isEqualTo("integer");
        assertThat(getEnumValues(statusSchema)).containsExactly(1, 0);
        assertThat(statusSchema.getDescription()).contains("**枚举选项**：");

        // 4. 验证 IBaseEnum gender
        io.swagger.v3.oas.models.media.Schema<?> genderSchema = properties.get("gender");
        assertThat(genderSchema).isNotNull();
        assertThat(getEnumValues(genderSchema)).containsExactly("M", "F");
        assertThat(genderSchema.getDescription()).contains("**枚举选项**：");

        // 5. 验证 KEEP_HEAD_TAIL 自定义脱敏描述
        io.swagger.v3.oas.models.media.Schema<?> customCardSchema = properties.get("customCard");
        assertThat(customCardSchema).isNotNull();
        assertThat(customCardSchema.getDescription())
                .contains("[脱敏字段: 保留首尾(前4后4)掩码]")
                .contains("自定义首尾保留卡号");
    }

    @Test
    @DisplayName("普通 Java 枚举解析：正常委派默认链处理，不追加 IBaseEnum 标记")
    void testNormalEnumPassThrough() {
        Map<String, io.swagger.v3.oas.models.media.Schema> models = ModelConverters.getInstance().read(UserTestVO.class);
        assertThat(models).containsKey("UserTestVO");
        io.swagger.v3.oas.models.media.Schema<?> userModel = models.get("UserTestVO");
        io.swagger.v3.oas.models.media.Schema<?> colorSchema = (io.swagger.v3.oas.models.media.Schema<?>) userModel.getProperties().get("color");
        assertThat(colorSchema).isNotNull();
        assertThat(colorSchema.getDescription()).isEqualTo("普通颜色枚举");
        assertThat(colorSchema.getDescription()).doesNotContain("**枚举选项**：");
        assertThat(getEnumValues(colorSchema)).containsExactly("RED", "GREEN", "BLUE");
    }

    @Test
    @DisplayName("DisposableBean 生命周期测试：destroy() 对称注销全局转换器")
    void testDisposableBeanLifecycle() {
        OpenApiModelConverter testConverter = new OpenApiModelConverter();
        assertThat(ModelConverters.getInstance().getConverters()).contains(testConverter);

        testConverter.destroy();
        assertThat(ModelConverters.getInstance().getConverters()).doesNotContain(testConverter);
    }
}
