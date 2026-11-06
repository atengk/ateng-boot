package io.github.atengk.boot.ai.context;

/**
 * 智能体长效情景记忆调用上下文持有者
 * <p>
 * 用于在当前方法调用线程中暂存召回的用户事实画像，并注入大模型系统提示词。
 * 遵循确定性生命周期与资源清理铁律，在调用结束后自动清理 ThreadLocal。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-06
 */
public final class EpisodicMemoryContext {

    private static final ThreadLocal<String> INJECTED_PROMPT_HOLDER = new ThreadLocal<>();

    private EpisodicMemoryContext() {
    }

    /**
     * 获取当前调用线程中注入的情景记忆画像提示词
     *
     * @return 注入的提示词文本，若未注入则返回 null
     */
    public static String getInjectedPrompt() {
        return INJECTED_PROMPT_HOLDER.get();
    }

    /**
     * 设置当前调用线程中待注入的情景记忆画像提示词
     *
     * @param prompt 待注入的提示词文本
     */
    public static void setInjectedPrompt(String prompt) {
        if (prompt != null && !prompt.isEmpty()) {
            INJECTED_PROMPT_HOLDER.set(prompt);
        } else {
            INJECTED_PROMPT_HOLDER.remove();
        }
    }

    /**
     * 清理当前线程上下文，防止内存泄露与线程复用脏读
     */
    public static void clear() {
        INJECTED_PROMPT_HOLDER.remove();
    }
}
