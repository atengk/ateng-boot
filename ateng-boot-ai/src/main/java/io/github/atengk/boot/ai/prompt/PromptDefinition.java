package io.github.atengk.boot.ai.prompt;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 不可变动态提示词资产定义契约
 * <p>
 * 封装提示词唯一编码、版本号、发布标签（如 {@code prod} / {@code canary}）、模板正文、声明式必填变量与描述信息。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-22
 */
public final class PromptDefinition {

    public static final String DEFAULT_VERSION = "default";

    private final String code;
    private final String version;
    private final String label;
    private final String content;
    private final Set<String> requiredVariables;
    private final String description;

    private PromptDefinition(Builder builder) {
        if (!StringUtils.hasText(builder.code)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "提示词定义 code 不能为空");
        }
        if (!StringUtils.hasText(builder.content)) {
            throw new AiException(AiResultCode.AI_CONFIG_ERROR, "提示词定义 content 不能为空 [code=" + builder.code + "]");
        }
        this.code = builder.code.trim();
        this.version = StringUtils.hasText(builder.version) ? builder.version.trim() : DEFAULT_VERSION;
        this.label = StringUtils.hasText(builder.label) ? builder.label.trim() : null;
        this.content = builder.content;
        this.requiredVariables = builder.requiredVariables == null || builder.requiredVariables.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(builder.requiredVariables));
        this.description = StringUtils.hasText(builder.description) ? builder.description.trim() : null;
    }

    public static PromptDefinition of(String code, String content) {
        return builder().code(code).content(content).build();
    }

    public static PromptDefinition of(String code, String version, String label, String content) {
        return builder().code(code).version(version).label(label).content(content).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCode() {
        return code;
    }

    public String getVersion() {
        return version;
    }

    public String getLabel() {
        return label;
    }

    public String getContent() {
        return content;
    }

    public Set<String> getRequiredVariables() {
        return requiredVariables;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 将当前提示词正文转换为可渲染 {@link PromptTemplate} 实例
     *
     * @return PromptTemplate
     */
    public PromptTemplate toTemplate() {
        return PromptTemplate.of(content);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PromptDefinition that)) {
            return false;
        }
        return Objects.equals(code, that.code)
                && Objects.equals(version, that.version)
                && Objects.equals(label, that.label)
                && Objects.equals(content, that.content)
                && Objects.equals(requiredVariables, that.requiredVariables)
                && Objects.equals(description, that.description);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, version, label, content, requiredVariables, description);
    }

    @Override
    public String toString() {
        return "PromptDefinition{" +
                "code='" + code + '\'' +
                ", version='" + version + '\'' +
                ", label='" + label + '\'' +
                ", requiredVariables=" + requiredVariables +
                '}';
    }

    public static final class Builder {

        private String code;
        private String version;
        private String label;
        private String content;
        private Set<String> requiredVariables;
        private String description;

        private Builder() {
        }

        public Builder code(String code) {
            this.code = code;
            return this;
        }

        public Builder version(String version) {
            this.version = version;
            return this;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder requiredVariables(Collection<String> requiredVariables) {
            if (requiredVariables == null || requiredVariables.isEmpty()) {
                this.requiredVariables = Collections.emptySet();
            } else {
                Set<String> cleaned = new LinkedHashSet<>();
                for (String item : requiredVariables) {
                    if (StringUtils.hasText(item)) {
                        cleaned.add(item.trim());
                    }
                }
                this.requiredVariables = cleaned;
            }
            return this;
        }

        public Builder requiredVariables(String... requiredVariables) {
            if (requiredVariables == null || requiredVariables.length == 0) {
                this.requiredVariables = Collections.emptySet();
                return this;
            }
            return requiredVariables(Arrays.asList(requiredVariables));
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public PromptDefinition build() {
            return new PromptDefinition(this);
        }
    }
}
