package io.github.atengk.boot.sample.statemachine.service;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.AsyncUtil;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.sample.statemachine.vo.OrderConcurrentSimulationVO;
import io.github.atengk.boot.statemachine.cas.CasUpdater;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import io.github.atengk.boot.statemachine.exception.StateMachineException;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 第三方支付异步回调场景服务
 * <p>演示 CAS 乐观锁防重执行、声明式幂等放行 (IdempotentStrategy.ACCEPT) 与 Spring 本地事务提交后 (performAfterCommit) 外部通知</p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class PaymentCallbackScenarioService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackScenarioService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;
    private final CasUpdater<OrderDO, OrderState> orderCasUpdater;
    private final Executor executor;

    public PaymentCallbackScenarioService(OrderMapper orderMapper,
                                          StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this(orderMapper, orderStateMachine, null);
    }

    @Autowired
    public PaymentCallbackScenarioService(OrderMapper orderMapper,
                                          StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine,
                                          @Qualifier("applicationTaskExecutor") ObjectProvider<Executor> taskExecutorProvider) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
        this.orderCasUpdater = (ent, from, to) ->
                orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion());
        this.executor = (taskExecutorProvider != null)
                ? taskExecutorProvider.getIfAvailable(AsyncUtil::getExecutor)
                : AsyncUtil.getExecutor();
    }

    /**
     * 处理外部渠道支付异步回调通知
     * <p>结合数据库原子 CAS 乐观锁防并发重复执行，若重复回调已处于 PAID 目标态则幂等放行，并在本地事务提交后同步触发外部通知</p>
     *
     * @param orderId     订单 ID
     * @param paidAmount  支付实付金额
     * @param channelTxNo 渠道交易流水号
     * @return 跃迁处理结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> handlePaymentCallback(Long orderId, BigDecimal paidAmount, String channelTxNo) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(paidAmount != null ? paidAmount : order.getAmount())
                .operator("PAY_CALLBACK:" + (channelTxNo != null ? channelTxNo : "UNKNOWN"))
                .build();

        log.info("[PaymentCallback] 收到支付异步回调: orderId={}, orderSn={}, currentStatus={}, paidAmount={}",
                orderId, order.getOrderSn(), sourceState, paidAmount);

        // CAS 驱动跃迁（内置声明式幂等探测与 performAfterCommit 调度）
        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.PAY,
                context,
                this.orderCasUpdater
        );

        if (sourceState != targetState) {
            order.setVersion(order.getVersion() + 1);
            log.info("[PaymentCallback] 支付状态更新成功: orderId={}, status={} -> {}, version={}",
                orderId, sourceState, targetState, order.getVersion());
            return TransitionResult.success(sourceState, targetState, OrderEvent.PAY);
        } else {
            log.info("[PaymentCallback] 支付回调幂等放行: orderId={}, 维持状态={}", orderId, targetState);
            return TransitionResult.idempotent(targetState, OrderEvent.PAY);
        }
    }

    /**
     * 静默模式处理支付回调（异常不抛出，包装为结构化 TransitionResult 返回）
     *
     * @param orderId     订单 ID
     * @param paidAmount  支付实付金额
     * @param channelTxNo 渠道交易流水号
     * @return 结构化跃迁结果
     */
    public TransitionResult<OrderState> handlePaymentCallbackQuietly(Long orderId, BigDecimal paidAmount, String channelTxNo) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(paidAmount != null ? paidAmount : order.getAmount())
                .operator("PAY_CALLBACK_QUIETLY:" + (channelTxNo != null ? channelTxNo : "UNKNOWN"))
                .build();

        return orderStateMachine.fireEventWithCasQuietly(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.PAY,
                context,
                this.orderCasUpdater
        );
    }

    /**
     * 模拟多线程高并发重复支付回调冲击（验证 CAS-First 原子冲突阻断机制与受管并发调度）
     *
     * @param orderId 待测试订单 ID
     * @param threads 并发线程数
     * @return 强类型并发模拟执行结果视图对象
     */
    public OrderConcurrentSimulationVO simulateConcurrentCallbacks(Long orderId, int threads) {
        int concurrency = Math.max(2, Math.min(threads, 20));
        log.info("[PaymentCallback] 模拟高并发回调冲击: orderId={}, 并发数={}", orderId, concurrency);

        OrderDO initialOrder = getOrderOrThrow(orderId);
        CountDownLatch readyLatch = new CountDownLatch(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger idempotentCount = new AtomicInteger(0);
        AtomicInteger casConflictCount = new AtomicInteger(0);
        AtomicInteger otherFailures = new AtomicInteger(0);

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            executor.execute(() -> {
                readyLatch.countDown();
                try {
                    boolean started = startLatch.await(5, TimeUnit.SECONDS);
                    if (!started) {
                        otherFailures.incrementAndGet();
                        return;
                    }
                    TransitionResult<OrderState> res = handlePaymentCallback(
                            orderId,
                            initialOrder.getAmount(),
                            "SIM_TX_" + idx + "_" + UUID.randomUUID().toString().substring(0, 8)
                    );
                    if (res != null && res.isAccepted()) {
                        if (res.isIdempotent()) {
                            idempotentCount.incrementAndGet();
                        } else {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (StateMachineException ex) {
                    if (ex.getResultCode() == StateMachineResultCode.CONCURRENT_CAS_CONFLICT) {
                        casConflictCount.incrementAndGet();
                    } else {
                        otherFailures.incrementAndGet();
                    }
                } catch (Exception ex) {
                    otherFailures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        try {
            boolean allReady = readyLatch.await(5, TimeUnit.SECONDS);
            if (!allReady) {
                log.warn("[PaymentCallback] 并发模拟未在指定时间内全部就绪: orderId={}", orderId);
            }
            startLatch.countDown();
            boolean finished = doneLatch.await(10, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[PaymentCallback] 并发模拟未在指定时间内全部完成: orderId={}", orderId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "并发模拟执行被中断", e);
        }

        OrderDO finalOrder = getOrderOrThrow(orderId);

        String verdict = (successCount.get() == 1 || idempotentCount.get() > 0)
                ? "CAS并发防护生效：严格防止重复支付与虚假副作用"
                : "并发流转完成";

        return OrderConcurrentSimulationVO.builder()
                .orderId(orderId)
                .concurrency(concurrency)
                .firstSuccessCount(successCount.get())
                .idempotentAcceptedCount(idempotentCount.get())
                .casConflictBlockedCount(casConflictCount.get())
                .otherFailures(otherFailures.get())
                .finalStatus(finalOrder.getStatus())
                .finalVersion(finalOrder.getVersion())
                .verdict(verdict)
                .build();
    }

    private OrderDO getOrderOrThrow(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在: id=" + orderId);
        }
        return order;
    }
}
