package io.github.atengk.boot.ai;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.prompt.PromptTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 提示词模板参数插值单元测试
 *
 * @author Ateng
 * @since 2026-11-03
 */
class PromptTemplateTest {

    @Test
    @DisplayName("基础单参数与多参数插值替换")
    void testBasicInterpolation() {
        PromptTemplate template = PromptTemplate.of("你好 {{name}}，你的身份是 {{ role }}，编号为 {{user.id}}。");

        assertThat(template.getVariableNames()).containsExactlyInAnyOrder("name", "role", "user.id");

        Map<String, Object> vars = Map.of(
                "name", "张三",
                "role", "管理员",
                "user.id", 10086
        );

        String result = template.render(vars);
        assertThat(result).isEqualTo("你好 张三，你的身份是 管理员，编号为 10086。");
    }

    @Test
    @DisplayName("变长参数键值对渲染语法糖")
    void testVarargsRender() {
        PromptTemplate template = PromptTemplate.of("请将下述内容翻译为 {{targetLang}}：{{content}}");

        String result = template.render("targetLang", "英语", "content", "企业级全能中间件");
        assertThat(result).isEqualTo("请将下述内容翻译为 英语：企业级全能中间件");
    }

    @Test
    @DisplayName("部分未提供变量保持原样保留")
    void testUnresolvedVariablesRetained() {
        PromptTemplate template = PromptTemplate.of("系统参数：{{configured}}，未配置参数：{{missing}}");

        String result = template.render(Map.of("configured", "OK"));
        assertThat(result).isEqualTo("系统参数：OK，未配置参数：{{missing}}");
    }

    @Test
    @DisplayName("显式传入 null 值替换为空白字符")
    void testNullVariableRender() {
        PromptTemplate template = PromptTemplate.of("前缀-{{nullVar}}-后缀");

        Map<String, Object> vars = new HashMap<>();
        vars.put("nullVar", null);

        String result = template.render(vars);
        assertThat(result).isEqualTo("前缀--后缀");
    }

    @Test
    @DisplayName("传入 null 或空 Map 保持原模板不变")
    void testEmptyVariables() {
        PromptTemplate template = PromptTemplate.of("原始模板内容 {{var}}");

        assertThat(template.render((Map<String, ?>) null)).isEqualTo("原始模板内容 {{var}}");
        assertThat(template.render(Collections.emptyMap())).isEqualTo("原始模板内容 {{var}}");
        assertThat(template.render(new Object[0])).isEqualTo("原始模板内容 {{var}}");
    }

    @Test
    @DisplayName("对抗性测试：变长参数传入奇数个参数抛出 AI_INVALID_PAYLOAD")
    void testOddVarargsThrows() {
        PromptTemplate template = PromptTemplate.of("测试 {{key}}");

        assertThatThrownBy(() -> template.render("key"))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_INVALID_PAYLOAD));
    }

    @Test
    @DisplayName("对抗性测试：模板为空或空白抛出 AI_CONFIG_ERROR")
    void testEmptyTemplateThrows() {
        assertThatThrownBy(() -> PromptTemplate.of(null))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));

        assertThatThrownBy(() -> PromptTemplate.of("   "))
                .isInstanceOf(AiException.class)
                .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_CONFIG_ERROR));
    }
}
