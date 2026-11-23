package io.github.atengk.boot.ai.sanitizer;

/**
 * 原生 JSON Schema 请求作用域上下文。
 * <p>
 * 用于在 {@code strict-native-json-schema=true} 时，将本次结构化输出调用的 Schema 名称与 JSON 定义
 * 透传至 OkHttp 请求拦截器，并在作用域结束时确定性清理 {@link ThreadLocal}，避免线程复用串扰。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class NativeJsonSchemaContext {

    private static final ThreadLocal<SchemaSpec> CONTEXT = new ThreadLocal<>();

    private NativeJsonSchemaContext() {
    }

    /**
     * 打开原生 JSON Schema 请求作用域，必须在 {@code try-with-resources} 中使用。
     *
     * @param schemaName Schema 名称，空值时回退为 {@code ateng_structured_output}
     * @param schemaJson 严格 JSON Schema 字符串，不可为空
     * @return 可自动恢复前序上下文的作用域句柄
     */
    public static Scope openScope(String schemaName, String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            throw new IllegalArgumentException("schemaJson 不能为空");
        }
        String effectiveName = sanitizeSchemaName(schemaName);
        SchemaSpec previous = CONTEXT.get();
        CONTEXT.set(new SchemaSpec(effectiveName, schemaJson));
        return new Scope(previous);
    }

    /**
     * 获取当前线程绑定的原生 JSON Schema 规格。
     *
     * @return 当前 Schema 规格；未开启作用域时返回 {@code null}
     */
    public static SchemaSpec get() {
        return CONTEXT.get();
    }

    private static String sanitizeSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return "ateng_structured_output";
        }
        String sanitized = schemaName.replaceAll("[^a-zA-Z0-9_-]", "_");
        return sanitized.isBlank() ? "ateng_structured_output" : sanitized;
    }

    /**
     * 原生 JSON Schema 规格描述符。
     *
     * @param name       Schema 名称（符合 OpenAI {@code [a-zA-Z0-9_-]} 约束）
     * @param schemaJson 严格 JSON Schema 定义
     */
    public record SchemaSpec(String name, String schemaJson) {
    }

    /**
     * 作用域生命周期句柄。
     */
    public static final class Scope implements AutoCloseable {

        private final SchemaSpec previous;

        private Scope(SchemaSpec previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CONTEXT.remove();
            } else {
                CONTEXT.set(previous);
            }
        }
    }
}
