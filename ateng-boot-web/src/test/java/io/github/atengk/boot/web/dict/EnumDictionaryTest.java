package io.github.atengk.boot.web.dict;

import io.github.atengk.boot.core.annotation.EnumAlias;
import io.github.atengk.boot.core.enums.IBaseEnum;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.web.dict.autoconfigure.AtengEnumDictionaryAutoConfiguration;
import io.github.atengk.boot.web.enums.EnumNamingStrategy;
import io.github.atengk.boot.web.handler.GlobalExceptionHandler;
import io.github.atengk.boot.web.properties.EnumDictionaryProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 枚举字典注册中心、命名策略、HTTP 控制器与自动装配综合测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("枚举字典功能综合单元测试")
class EnumDictionaryTest {

    @Getter
    @RequiredArgsConstructor
    public enum SampleStatusEnum implements IBaseEnum<Integer> {
        NORMAL(1, "正常"),
        LOCKED(2, "锁定");

        private final Integer code;
        private final String label;
    }

    @Getter
    @RequiredArgsConstructor
    @EnumAlias("userRole")
    public enum SampleRoleEnum implements IBaseEnum<String> {
        ADMIN("admin", "管理员"),
        USER("user", "普通用户");

        private final String code;
        private final String label;
    }

    @Getter
    @RequiredArgsConstructor
    public enum CollisionStatusEnum implements IBaseEnum<Integer> {
        ONE(1, "一");

        private final Integer code;
        private final String label;
    }

    @Test
    @DisplayName("测试 EnumNamingStrategy 别名转换策略")
    void testNamingStrategy() {
        // 1. LOWER_CAMEL_CASE
        assertThat(EnumNamingStrategy.LOWER_CAMEL_CASE.resolve(SampleStatusEnum.class)).isEqualTo("sampleStatus");

        // 2. SNAKE_CASE
        assertThat(EnumNamingStrategy.SNAKE_CASE.resolve(SampleStatusEnum.class)).isEqualTo("sample_status");

        // 3. ORIGINAL
        assertThat(EnumNamingStrategy.ORIGINAL.resolve(SampleStatusEnum.class)).isEqualTo("SampleStatusEnum");
    }

    @Test
    @DisplayName("测试 EnumDictionaryRegistry 注册与查询能力")
    void testRegistryQueryAndManualRegister() {
        EnumDictionaryProperties properties = new EnumDictionaryProperties();
        EnumDictionaryRegistry registry = new EnumDictionaryRegistry(properties);

        // 手动注册
        registry.register(SampleStatusEnum.class);
        registry.register(SampleRoleEnum.class);

        // 校验注册结果
        assertThat(registry.contains("sampleStatus")).isTrue();
        assertThat(registry.contains("userRole")).isTrue(); // 注解覆盖别名生效
        assertThat(registry.contains("unknown")).isFalse();

        // 选项查询
        List<OptionItem<Object>> statusOptions = registry.getOptions("sampleStatus");
        assertThat(statusOptions).hasSize(2);
        assertThat(statusOptions.get(0).getCode()).isEqualTo(1);
        assertThat(statusOptions.get(0).getLabel()).isEqualTo("正常");

        // 批量查询
        Map<String, List<OptionItem<Object>>> batch = registry.getBatchOptions(List.of("sampleStatus", "userRole", "nonExistent"));
        assertThat(batch).containsOnlyKeys("sampleStatus", "userRole");

        // 全量查询
        Map<String, List<OptionItem<Object>>> all = registry.getAllOptions();
        assertThat(all).containsKeys("sampleStatus", "userRole");

        // 元数据
        assertThat(registry.getEnumClass("sampleStatus")).isEqualTo(SampleStatusEnum.class);
        assertThat(registry.getEnumClass("userRole")).isEqualTo(SampleRoleEnum.class);
        assertThat(registry.getAliases()).contains("sampleStatus", "userRole");
    }

    @Getter
    @RequiredArgsConstructor
    @EnumAlias("sampleStatus")
    public enum ConflictingEnum implements IBaseEnum<Integer> {
        ONE(1, "冲突");

        private final Integer code;
        private final String label;
    }

    @Test
    @DisplayName("测试 EnumDictionaryRegistry 别名冲突启动期 Fail-Fast 阻断")
    void testCollisionFailFast() {
        EnumDictionaryProperties properties = new EnumDictionaryProperties();
        EnumDictionaryRegistry registry = new EnumDictionaryRegistry(properties);

        registry.register(SampleStatusEnum.class);

        // 注册两个解析出相同别名 'sampleStatus' 的枚举类，预期抛出 IllegalStateException
        assertThatThrownBy(() -> registry.register(ConflictingEnum.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检测到枚举字典别名碰撞冲突！别名 [sampleStatus]");
    }

    @Test
    @DisplayName("测试 EnumDictionaryController 各端点 HTTP 交互与统一 Result 契约")
    void testControllerEndpoints() throws Exception {
        EnumDictionaryProperties properties = new EnumDictionaryProperties();
        EnumDictionaryRegistry registry = new EnumDictionaryRegistry(properties);
        registry.register(SampleStatusEnum.class);
        registry.register(SampleRoleEnum.class);

        EnumDictionaryController controller = new EnumDictionaryController(registry);
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        // 1. 全量目录查询 GET /api/system/enums
        mockMvc.perform(get("/api/system/enums"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sampleStatus").isArray())
                .andExpect(jsonPath("$.data.userRole").isArray());

        // 2. 单个查询 GET /api/system/enums/{name}
        mockMvc.perform(get("/api/system/enums/sampleStatus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].code").value(1))
                .andExpect(jsonPath("$.data[0].label").value("正常"));

        // 3. 未找到别名返回 404
        mockMvc.perform(get("/api/system/enums/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("未找到名称为 [unknown] 的枚举字典"));

        // 4. 批量查询 POST /api/system/enums/batch
        mockMvc.perform(post("/api/system/enums/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"sampleStatus\", \"userRole\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sampleStatus").isArray())
                .andExpect(jsonPath("$.data.userRole").isArray());

        // 5. 批量查询 GET /api/system/enums/batch?names=sampleStatus,userRole
        mockMvc.perform(get("/api/system/enums/batch").param("names", "sampleStatus", "userRole"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sampleStatus").isArray())
                .andExpect(jsonPath("$.data.userRole").isArray());
    }

    @Test
    @DisplayName("测试 AtengEnumDictionaryAutoConfiguration 自动装配开闭与条件守卫")
    void testAutoConfiguration() {
        WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengEnumDictionaryAutoConfiguration.class));

        // 1. 默认情况下 (ateng.boot.web.enum.enabled=false) 不装配
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(EnumDictionaryRegistry.class);
            assertThat(context).doesNotHaveBean(EnumDictionaryController.class);
        });

        // 2. 配置 ateng.boot.web.enum.enabled=true 时自动装配
        runner.withPropertyValues("ateng.boot.web.enum.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(EnumDictionaryRegistry.class);
                    assertThat(context).hasSingleBean(EnumDictionaryController.class);
                });
    }
}
