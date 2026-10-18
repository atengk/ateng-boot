package io.github.atengk.boot.mybatisplus.util;

import org.apache.ibatis.cursor.Cursor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 受管游标流式查询模板与防 OOM 执行门面
 * 封装基于 MyBatis 原生 {@link Cursor} 的流式查询生命周期，针对百万级大表导出与大规模批处理等场景，
 * 内部自动通过 {@link TransactionTemplate} 开启只读事务（readOnly = true）守护并自适应加入外层既有事务，
 * 借助 try-with-resources 机制 100% 确定性关闭游标并释放底层 JDBC 物理连接，
 * 提供逐条流式（{@code forEach}）与分批切片（{@code forEachBatch}）双消费契约，从根本上杜绝内存溢出与连接悬挂泄漏。
 *
 * @author Ateng
 * @since 2026-10-18
 */
public final class StreamingQueryTemplate {

    /**
     * 默认分批切片批次大小（1000）
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;

    /**
     * 默认 JDBC FetchSize 建议值（1000）
     */
    public static final int DEFAULT_FETCH_SIZE = 1000;

    private final TransactionTemplate transactionTemplate;

    /**
     * 基于平台事务管理器构造流式查询模板
     * 默认强制配置只读事务（readOnly = true）与传播行为 PROPAGATION_REQUIRED（自适应加入外层事务）
     *
     * @param transactionManager 平台事务管理器，不能为 null
     */
    public StreamingQueryTemplate(PlatformTransactionManager transactionManager) {
        Objects.requireNonNull(transactionManager, "PlatformTransactionManager must not be null");
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        this.transactionTemplate = template;
    }

    /**
     * 基于自定义 TransactionTemplate 构造流式查询模板
     *
     * @param transactionTemplate 自定义事务模板，不能为 null
     */
    public StreamingQueryTemplate(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "TransactionTemplate must not be null");
    }

    /**
     * 获取内部受管的 TransactionTemplate 实例
     *
     * @return 事务模板实例
     */
    public TransactionTemplate getTransactionTemplate() {
        return this.transactionTemplate;
    }

    /**
     * 逐条流式消费游标记录
     * 在受管只读事务中开启 MyBatis 游标，并在遍历结束或异常中断时通过 try-with-resources 确定性闭合游标与释放数据库连接
     *
     * @param cursorSupplier 游标提供者（如 {@code () -> mapper.selectCursor(queryWrapper)}），不能为 null
     * @param consumer       单条记录消费器，不能为 null
     * @param <T>            记录实体类型
     */
    public <T> void forEach(Supplier<Cursor<T>> cursorSupplier, Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "Consumer must not be null");
        execute(cursorSupplier, cursor -> {
            for (T item : cursor) {
                consumer.accept(item);
            }
            return null;
        });
    }

    /**
     * 分批切片流式消费游标记录（采用默认切片大小 1000）
     *
     * @param cursorSupplier 游标提供者，不能为 null
     * @param batchConsumer  分批记录消费器（入参为不可变切片 List），不能为 null
     * @param <T>            记录实体类型
     */
    public <T> void forEachBatch(Supplier<Cursor<T>> cursorSupplier, Consumer<List<T>> batchConsumer) {
        forEachBatch(cursorSupplier, DEFAULT_BATCH_SIZE, batchConsumer);
    }

    /**
     * 分批切片流式消费游标记录（支持自定义切片批次大小）
     * 在受管只读事务中开启游标并缓冲指定批次大小后触发批量消费，消费结束后清空缓冲区以防御 JVM 堆内存 OOM
     *
     * @param cursorSupplier 游标提供者，不能为 null
     * @param batchSize      分批切片批次大小（必须大于 0）
     * @param batchConsumer  分批记录消费器（入参为不可变切片 List），不能为 null
     * @param <T>            记录实体类型
     */
    public <T> void forEachBatch(Supplier<Cursor<T>> cursorSupplier, int batchSize, Consumer<List<T>> batchConsumer) {
        Objects.requireNonNull(batchConsumer, "Batch consumer must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be greater than 0, given: " + batchSize);
        }
        execute(cursorSupplier, cursor -> {
            List<T> buffer = new ArrayList<>(batchSize);
            for (T item : cursor) {
                buffer.add(item);
                if (buffer.size() >= batchSize) {
                    batchConsumer.accept(Collections.unmodifiableList(buffer));
                    buffer = new ArrayList<>(batchSize);
                }
            }
            if (!buffer.isEmpty()) {
                batchConsumer.accept(Collections.unmodifiableList(buffer));
            }
            return null;
        });
    }

    /**
     * 在受管只读事务中执行自定义游标操作并返回结果
     *
     * @param cursorSupplier 游标提供者，不能为 null
     * @param action         游标执行函数，不能为 null
     * @param <T>            游标数据类型
     * @param <R>            返回值类型
     * @return 执行函数计算结果，若游标提供者返回 null 则返回 null
     */
    public <T, R> R execute(Supplier<Cursor<T>> cursorSupplier, Function<Cursor<T>, R> action) {
        Objects.requireNonNull(cursorSupplier, "Cursor supplier must not be null");
        Objects.requireNonNull(action, "Cursor action function must not be null");

        return this.transactionTemplate.execute(status -> {
            try (Cursor<T> cursor = cursorSupplier.get()) {
                if (cursor == null) {
                    return null;
                }
                return action.apply(cursor);
            } catch (RuntimeException ex) {
                status.setRollbackOnly();
                throw ex;
            } catch (Exception ex) {
                status.setRollbackOnly();
                throw new IllegalStateException("Streaming query execution failed: " + ex.getMessage(), ex);
            }
        });
    }
}
