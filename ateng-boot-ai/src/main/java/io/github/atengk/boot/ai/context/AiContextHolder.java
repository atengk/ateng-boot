package io.github.atengk.boot.ai.context;

import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * AI 模型上下文线程级路由器
 * 用于在当前线程中动态切换活跃大模型，并在 try-with-resources 或 finally 块中确定性清理
 *
 * @author Ateng
 * @since 2026-11-03
 */
public final class AiContextHolder {

    private static final ThreadLocal<Deque<String>> CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<String>> EMBEDDING_CONTEXT = ThreadLocal.withInitial(ArrayDeque::new);

    private AiContextHolder() {
    }

    /**
     * 在当前线程压入要激活的模型名称
     *
     * @param modelName 模型名称或别名
     */
    public static void setModelName(String modelName) {
        if (StringUtils.hasText(modelName)) {
            CONTEXT.get().push(modelName.trim());
        }
    }

    /**
     * 获取当前线程最顶层的活跃模型名称
     *
     * @return 当前模型名称，若栈为空则返回 null
     */
    public static String getModelName() {
        Deque<String> stack = CONTEXT.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 弹出当前线程栈顶的模型，若栈已清空则彻底释放 ThreadLocal
     */
    public static void clear() {
        Deque<String> stack = CONTEXT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            CONTEXT.remove();
        }
    }

    /**
     * 彻底清理当前线程绑定的所有模型与向量上下文
     */
    public static void clearAll() {
        CONTEXT.remove();
        EMBEDDING_CONTEXT.remove();
    }

    /**
     * 在当前线程压入要激活的向量化模型名称
     *
     * @param modelName 向量模型名称或别名
     */
    public static void setEmbeddingModelName(String modelName) {
        if (StringUtils.hasText(modelName)) {
            EMBEDDING_CONTEXT.get().push(modelName.trim());
        }
    }

    /**
     * 获取当前线程最顶层的活跃向量化模型名称
     *
     * @return 当前向量模型名称，若栈为空则返回 null
     */
    public static String getEmbeddingModelName() {
        Deque<String> stack = EMBEDDING_CONTEXT.get();
        return stack.isEmpty() ? null : stack.peek();
    }

    /**
     * 弹出当前线程栈顶的向量化模型，若栈已清空则彻底释放 ThreadLocal
     */
    public static void clearEmbedding() {
        Deque<String> stack = EMBEDDING_CONTEXT.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            EMBEDDING_CONTEXT.remove();
        }
    }

    /**
     * 声明式语法糖：临时切换活跃向量化模型并返回 AutoCloseable 资源句柄
     *
     * <pre>{@code
     * try (AiContextHolder.AiModelScope ignored = AiContextHolder.useEmbeddingModel("bge-large")) {
     *     // 此代码块内的向量化计算均自动路由至 bge-large 模型
     *     embeddingTemplate.embed("待向量化文本");
     * }
     * }</pre>
     *
     * @param modelName 要切换的目标向量模型名称
     * @return 自动释放句柄
     */
    public static AiModelScope useEmbeddingModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return () -> {};
        }
        setEmbeddingModelName(modelName);
        return AiContextHolder::clearEmbedding;
    }

    /**
     * 声明式语法糖：临时切换活跃模型并返回 AutoCloseable 资源句柄
     *
     * <pre>{@code
     * try (AiContextHolder.AiModelScope ignored = AiContextHolder.useModel("deepseek-r1")) {
     *     // 此代码块内的 AI 调用均自动路由至 deepseek-r1 模型
     *     aiTemplate.chat("请开始深度思考推理...");
     * }
     * }</pre>
     *
     * @param modelName 要切换的目标模型名称
     * @return 自动释放句柄
     */
    public static AiModelScope useModel(String modelName) {
        if (!StringUtils.hasText(modelName)) {
            return () -> {};
        }
        setModelName(modelName);
        return AiContextHolder::clear;
    }

    /**
     * 模型上下文生命周期作用域接口
     */
    @FunctionalInterface
    public interface AiModelScope extends AutoCloseable {
        @Override
        void close();
    }
}
