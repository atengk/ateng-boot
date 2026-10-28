package io.github.atengk.boot.core.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * 异常分析与堆栈序列化工具类
 * 提供多层嵌套根本原因（Root Cause）因果回溯、安全堆栈序列化与有界长度防御截断
 *
 * @author Ateng
 * @since 2026-10-26
 */
public final class ExceptionUtil {

    private static final String TRUNCATED_MARK = "\n... [truncated]";

    private ExceptionUtil() {
    }

    /**
     * 提取嵌套异常链的最深层根因异常（Root Cause）
     * 内置循环因果链检测，杜绝自引用导致的无限递归死循环
     *
     * @param throwable 待回溯的异常对象
     * @return 根因异常对象；若入参为 null 则返回 null
     */
    public static Throwable getRootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        visited.add(current);

        while (current.getCause() != null) {
            Throwable cause = current.getCause();
            if (!visited.add(cause)) {
                // 检测到循环引用因果链，立即中断并返回当前节点
                break;
            }
            current = cause;
        }
        return current;
    }

    /**
     * 获取异常的自解释错误提示信息
     * 若异常未设置 message，则安全回退返回其全限定类名，杜绝返回空文本
     *
     * @param throwable 异常对象
     * @return 异常消息描述；若入参为 null 返回空串
     */
    public static String getMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String msg = throwable.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : throwable.getClass().getName();
    }

    /**
     * 将异常堆栈完整序列化为字符串
     *
     * @param throwable 异常对象
     * @return 异常堆栈文本；若入参为 null 返回空串
     */
    public static String stackTraceToString(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            throwable.printStackTrace(pw);
            return sw.toString();
        }
    }

    /**
     * 将异常堆栈序列化为字符串并限制最大字符长度
     * 超过指定长度时自动执行优雅截断并附加截断标记，防止堆栈过大撑爆日志采集或持久化数据库
     *
     * @param throwable 异常对象
     * @param maxLen    最大保留字符数（必须大于 0）
     * @return 有界堆栈字符串
     */
    public static String stackTraceToString(Throwable throwable, int maxLen) {
        if (throwable == null) {
            return "";
        }
        if (maxLen <= 0) {
            return "";
        }

        String fullTrace = stackTraceToString(throwable);
        if (fullTrace.length() <= maxLen) {
            return fullTrace;
        }

        int truncateAt = Math.max(0, maxLen - TRUNCATED_MARK.length());
        return fullTrace.substring(0, truncateAt) + TRUNCATED_MARK;
    }
}
