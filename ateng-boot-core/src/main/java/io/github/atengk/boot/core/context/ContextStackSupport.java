package io.github.atengk.boot.core.context;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 线程上下文栈内部共用原语
 * 统一封装惰性 ThreadLocal 栈式压栈、覆盖、只读无残留探测与快照恢复，消除 User/Tenant 双维上下文重复实现
 *
 * @author Ateng
 * @since 2026-11-12
 */
final class ContextStackSupport {

    private ContextStackSupport() {
    }

    static String peekExplicit(ThreadLocal<Deque<String>> holder) {
        Deque<String> stack = holder.get();
        if (stack == null) {
            holder.remove();
            return null;
        }
        return stack.isEmpty() ? null : stack.peek();
    }

    static void setTop(ThreadLocal<Deque<String>> holder, String value) {
        Deque<String> stack = holder.get();
        if (stack == null || stack.isEmpty()) {
            if (value != null) {
                Deque<String> newStack = new LinkedList<>();
                newStack.push(value);
                holder.set(newStack);
            } else {
                holder.remove();
            }
            return;
        }
        stack.pop();
        if (value != null || !stack.isEmpty()) {
            stack.push(value);
        } else {
            holder.remove();
        }
    }

    static <T> T pushAndRun(ThreadLocal<Deque<String>> holder, String value, Supplier<T> supplier) {
        Objects.requireNonNull(supplier, "supplier 不能为空");
        Deque<String> stack = holder.get();
        if (stack == null) {
            stack = new LinkedList<>();
            holder.set(stack);
        }
        stack.push(value);
        try {
            return supplier.get();
        } finally {
            if (!stack.isEmpty()) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                holder.remove();
            }
        }
    }

    static Deque<String> copyStack(ThreadLocal<Deque<String>> holder) {
        Deque<String> stack = holder.get();
        if (stack == null) {
            holder.remove();
            return new LinkedList<>();
        }
        return stack.isEmpty() ? new LinkedList<>() : new LinkedList<>(stack);
    }

    static void restoreStack(ThreadLocal<Deque<String>> holder, Deque<String> snapshotStack) {
        if (snapshotStack == null || snapshotStack.isEmpty()) {
            holder.remove();
        } else {
            holder.set(new LinkedList<>(snapshotStack));
        }
    }

    static boolean hasNonNullValue(ThreadLocal<?> holder) {
        Object value = holder.get();
        if (value == null) {
            holder.remove();
            return false;
        }
        return true;
    }
}
