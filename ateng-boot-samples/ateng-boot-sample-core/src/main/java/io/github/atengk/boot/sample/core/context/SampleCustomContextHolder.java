package io.github.atengk.boot.sample.core.context;

/**
 * 样例业务自定义上下文持有者（演示第五维业务上下文跨受管线程池透传）
 *
 * @author Ateng
 * @since 2026-11-07
 */
public final class SampleCustomContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private SampleCustomContextHolder() {
    }

    public static String get() {
        return CONTEXT.get();
    }

    public static void set(String data) {
        if (data == null || data.isBlank()) {
            CONTEXT.remove();
        } else {
            CONTEXT.set(data);
        }
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
