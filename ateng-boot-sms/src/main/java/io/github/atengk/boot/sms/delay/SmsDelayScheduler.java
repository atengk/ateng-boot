package io.github.atengk.boot.sms.delay;

import io.github.atengk.boot.sms.model.BatchSmsRequest;
import io.github.atengk.boot.sms.model.SmsRequest;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * 受管可取消延迟与定时短信调度器 SPI
 * 严格遵循两阶段语义：提交入队阶段仅捕获请求与上下文快照且不预先扣减预算/配额，到点触发时通过绑定分发器执行完整实时发信链
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface SmsDelayScheduler {

    /**
     * 绑定到期实际执行单条与批量发信的分发器回调（消除与 SmsTemplate 之间的构造循环依赖）
     *
     * @param singleDispatcher 单条短信到期分发器
     * @param batchDispatcher  批量短信到期分发器
     */
    void bindDispatchers(Consumer<SmsRequest> singleDispatcher, Consumer<BatchSmsRequest> batchDispatcher);

    /**
     * 调度一条延迟或定时单条短信任务
     *
     * @param request     单条发信请求
     * @param triggerAt   预定触发时刻
     * @param channelHint 已解析的目标渠道提示名
     * @return 可取消的任务回执句柄
     */
    SmsScheduledReceipt scheduleSingle(SmsRequest request, Instant triggerAt, String channelHint);

    /**
     * 调度一条延迟或定时批量短信任务
     *
     * @param request     批量发信请求
     * @param triggerAt   预定触发时刻
     * @param channelHint 已解析的目标渠道提示名
     * @return 可取消的任务回执句柄
     */
    SmsScheduledReceipt scheduleBatch(BatchSmsRequest request, Instant triggerAt, String channelHint);

    /**
     * 根据任务 ID 撤销取消尚未执行的调度任务
     *
     * @param taskId 调度任务 ID
     * @return true 表示成功取消；若任务不存在、已完成或已撤销则返回 false
     */
    boolean cancel(String taskId);

    /**
     * 判断指定任务是否已撤销取消
     *
     * @param taskId 调度任务 ID
     * @return true 表示已取消
     */
    boolean isCancelled(String taskId);

    /**
     * 判断指定任务是否已结束（已完成、已取消或异常终止）
     *
     * @param taskId 调度任务 ID
     * @return true 表示已结束
     */
    boolean isDone(String taskId);

    /**
     * 查询指定任务的当前生命周期状态
     *
     * @param taskId 调度任务 ID
     * @return 当前状态，未知或已超历史窗口回收时返回 null
     */
    SmsScheduledStatus getStatus(String taskId);
}
