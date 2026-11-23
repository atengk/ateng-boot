package io.github.atengk.boot.datasource.guard;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Spring 声明式事务启动阶段的瞬态路由上下文栈
 * <p>
 * 解决 {@code DataSourceTransactionManager.doBegin()} 在 {@code prepareSynchronization()} 之前获取连接时，
 * {@code TransactionSynchronizationManager.isActualTransactionActive()} 与 {@code isCurrentTransactionReadOnly()}
 * 尚未就绪导致无法识别真实写事务/只读事务及绑定初始数据源键的问题。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public final class TransactionRoutingContext {

    private static final ThreadLocal<Deque<TransactionStartFrame>> FRAME_STACK = new ThreadLocal<>();

    private TransactionRoutingContext() {
    }

    /**
     * 在事务 {@code beforeBegin} 阶段压入正在启动的事务状态帧
     *
     * @param actualTransaction 是否为真实物理事务
     * @param readOnly          事务是否声明为只读
     */
    public static void pushStartingTransaction(boolean actualTransaction, boolean readOnly) {
        if (!actualTransaction) {
            return;
        }
        Deque<TransactionStartFrame> stack = FRAME_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>(4);
            FRAME_STACK.set(stack);
        }
        stack.push(new TransactionStartFrame(readOnly));
    }

    /**
     * 判断当前线程是否正处于事务 {@code doBegin} 启动窗口内
     *
     * @return true 表示正在启动真实事务
     */
    public static boolean isStartingActualTransaction() {
        Deque<TransactionStartFrame> stack = FRAME_STACK.get();
        return stack != null && !stack.isEmpty();
    }

    /**
     * 获取当前正在启动事务的只读标志
     *
     * @return true 表示只读事务；false 表示非只读写事务或未处于启动窗口
     */
    public static boolean isStartingReadOnly() {
        Deque<TransactionStartFrame> stack = FRAME_STACK.get();
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.peek().readOnly;
    }

    /**
     * 记录当前事务启动阶段首次路由解析出的真实数据源标识
     *
     * @param dsKey 实际解析的数据源标识
     */
    public static void recordResolvedDataSourceKey(String dsKey) {
        if (dsKey == null || dsKey.isBlank()) {
            return;
        }
        Deque<TransactionStartFrame> stack = FRAME_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            TransactionStartFrame frame = stack.peek();
            if (frame.resolvedDataSourceKey == null) {
                frame.resolvedDataSourceKey = dsKey;
            }
        }
    }

    /**
     * 在事务 {@code afterBegin} 阶段弹出当前启动状态帧，并在栈空时清理 ThreadLocal
     *
     * @return 启动阶段解析出的数据源标识；若无则返回 null
     */
    public static String popStartingTransaction() {
        Deque<TransactionStartFrame> stack = FRAME_STACK.get();
        if (stack == null || stack.isEmpty()) {
            FRAME_STACK.remove();
            return null;
        }
        TransactionStartFrame frame = stack.pop();
        if (stack.isEmpty()) {
            FRAME_STACK.remove();
        }
        return frame.resolvedDataSourceKey;
    }

    /**
     * 清理当前线程遗留的启动上下文栈
     */
    public static void clear() {
        FRAME_STACK.remove();
    }

    private static final class TransactionStartFrame {
        private final boolean readOnly;
        private String resolvedDataSourceKey;

        private TransactionStartFrame(boolean readOnly) {
            this.readOnly = readOnly;
        }
    }
}
