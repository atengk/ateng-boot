package io.github.atengk.boot.web.dict;

import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.web.properties.EnumDictionaryProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 枚举字典 i18n 国际化多语言与优雅回退单元测试
 * 覆盖 MessageSource 注入、Locale 动态感知、约定 key 提取与缺失平滑回退
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("枚举字典 i18n 多语言与优雅回退测试")
class EnumDictionaryI18nTest {

    public enum SampleStatusEnum implements IBaseEnum<Integer> {
        ACTIVE(1, "启用"),
        INACTIVE(2, "已停用");

        private final Integer code;
        private final String label;

        SampleStatusEnum(Integer code, String label) {
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

    public enum SampleRoleEnum implements IBaseEnum<String> {
        ADMIN("admin", "管理员"),
        USER("user", "普通用户");

        private final String code;
        private final String label;

        SampleRoleEnum(String code, String label) {
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

    private StaticMessageSource messageSource;
    private EnumDictionaryRegistry registry;

    @BeforeEach
    void setUp() {
        messageSource = new StaticMessageSource();
        // 注册多语言文案
        messageSource.addMessage("enum.SampleStatusEnum.ACTIVE", Locale.US, "Active");
        messageSource.addMessage("enum.SampleStatusEnum.ACTIVE", Locale.SIMPLIFIED_CHINESE, "已激活");
        messageSource.addMessage("enum.SampleRoleEnum.ADMIN", Locale.US, "Administrator");

        EnumDictionaryProperties properties = new EnumDictionaryProperties();
        registry = new EnumDictionaryRegistry(properties, messageSource);
        registry.register(SampleStatusEnum.class);
        registry.register(SampleRoleEnum.class);
    }

    @AfterEach
    void tearDown() {
        // 恪守单测环境上下文零污染铁律
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("显式指定 Locale 时按约定 key 读取多语言文案")
    void shouldResolveLocalizedLabelsWithExplicitLocale() {
        List<OptionItem<Object>> usOptions = registry.getOptions("sampleStatus", Locale.US);
        assertThat(usOptions).isNotNull().hasSize(2);

        // ACTIVE 命中 US 国际化文案
        assertThat(usOptions.get(0).getCode()).isEqualTo(1);
        assertThat(usOptions.get(0).getLabel()).isEqualTo("Active");

        // INACTIVE 未配置 US 国际化文案，平滑回退默认 label
        assertThat(usOptions.get(1).getCode()).isEqualTo(2);
        assertThat(usOptions.get(1).getLabel()).isEqualTo("已停用");

        // 中文 Locale 测试
        List<OptionItem<Object>> zhOptions = registry.getOptions("sampleStatus", Locale.SIMPLIFIED_CHINESE);
        assertThat(zhOptions.get(0).getLabel()).isEqualTo("已激活");
        assertThat(zhOptions.get(1).getLabel()).isEqualTo("已停用");
    }

    @Test
    @DisplayName("通过 LocaleContextHolder 上下文透明感知当前请求多语言设置")
    void shouldResolveLocalizedLabelsViaLocaleContextHolder() {
        LocaleContextHolder.setLocale(Locale.US);

        List<OptionItem<Object>> options = registry.getOptions("sampleStatus");
        assertThat(options).isNotNull().hasSize(2);
        assertThat(options.get(0).getLabel()).isEqualTo("Active");
        assertThat(options.get(1).getLabel()).isEqualTo("已停用");

        // 切换为中文环境
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        List<OptionItem<Object>> zhOptions = registry.getOptions("sampleStatus");
        assertThat(zhOptions.get(0).getLabel()).isEqualTo("已激活");
    }

    @Test
    @DisplayName("批量查询与全量查询端点感知多语言环境")
    void shouldResolveBatchAndAllOptionsWithLocalization() {
        LocaleContextHolder.setLocale(Locale.US);

        // 批量查询
        Map<String, List<OptionItem<Object>>> batch = registry.getBatchOptions(List.of("sampleStatus", "sampleRole"));
        assertThat(batch).containsKeys("sampleStatus", "sampleRole");
        assertThat(batch.get("sampleStatus").get(0).getLabel()).isEqualTo("Active");
        assertThat(batch.get("sampleRole").get(0).getLabel()).isEqualTo("Administrator");
        assertThat(batch.get("sampleRole").get(1).getLabel()).isEqualTo("普通用户"); // 缺失回退

        // 全量查询
        Map<String, List<OptionItem<Object>>> all = registry.getAllOptions();
        assertThat(all).containsKeys("sampleStatus", "sampleRole");
        assertThat(all.get("sampleStatus").get(0).getLabel()).isEqualTo("Active");
    }

    @Test
    @DisplayName("未提供 MessageSource 时平滑回退为枚举硬编码默认标签")
    void shouldFallbackToDefaultLabelWhenNoMessageSource() {
        EnumDictionaryProperties properties = new EnumDictionaryProperties();
        EnumDictionaryRegistry plainRegistry = new EnumDictionaryRegistry(properties);
        plainRegistry.register(SampleStatusEnum.class);

        LocaleContextHolder.setLocale(Locale.US);
        List<OptionItem<Object>> options = plainRegistry.getOptions("sampleStatus");

        assertThat(options).isNotNull().hasSize(2);
        assertThat(options.get(0).getLabel()).isEqualTo("启用");
        assertThat(options.get(1).getLabel()).isEqualTo("已停用");
    }
}
