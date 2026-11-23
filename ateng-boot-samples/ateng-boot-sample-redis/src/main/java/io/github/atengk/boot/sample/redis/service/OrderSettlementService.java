package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.sample.redis.model.lock.OrderSettleRequest;
import io.github.atengk.boot.sample.redis.model.lock.OrderSettleVO;
import io.github.atengk.boot.sample.redis.model.lock.StockDeductRequest;
import io.github.atengk.boot.sample.redis.model.lock.StockDeductResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 订单结算场景服务
 * 演示跨 Bean 调用触发的真实 LockMode.REENTRANT 重入锁流转：
 * OrderSettlementService#settleOrder 获得 product:{id} 分布式锁，
 * 随后在同一调用链和线程内调用 StockDeductScenarioService#deductStock，后者同样声明锁定 product:{id}。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderSettlementService {

    private final StockDeductScenarioService stockDeductScenarioService;

    /**
     * 订单结算并扣减商品库存（跨服务重入演练）
     *
     * @param request 订单结算请求
     * @return 结算结果 VO
     */
    @DistributedLock(key = "'product:' + #request.productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public OrderSettleVO settleOrder(OrderSettleRequest request) {
        log.info("[OrderSettle] 外层服务获取商品锁成功，进入订单结算流程: orderId={}, productId={}",
                request.getOrderId(), request.getProductId());

        // 跨 Bean 调用同样挂载同一锁键的库存服务方法，验证同线程无死锁平滑重入
        StockDeductResultVO deductResult = stockDeductScenarioService.deductStock(
                StockDeductRequest.builder()
                        .productId(request.getProductId())
                        .count(request.getQuantity())
                        .build()
        );

        log.info("[OrderSettle] 跨 Bean 重入扣减库存成功: orderId={}, remainingStock={}",
                request.getOrderId(), deductResult.getRemainingStock());

        return OrderSettleVO.builder()
                .orderId(request.getOrderId())
                .productId(request.getProductId())
                .remainingStock(deductResult.getRemainingStock())
                .status("SETTLED")
                .build();
    }
}
