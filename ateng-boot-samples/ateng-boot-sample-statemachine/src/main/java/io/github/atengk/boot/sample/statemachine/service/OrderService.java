package io.github.atengk.boot.sample.statemachine.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sample.statemachine.constant.OrderEvent;
import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.context.OrderContext;
import io.github.atengk.boot.sample.statemachine.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTrackingRequest;
import io.github.atengk.boot.sample.statemachine.dto.OrderTransitionRequest;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.sample.statemachine.mapper.OrderMapper;
import io.github.atengk.boot.sample.statemachine.vo.OrderVO;
import io.github.atengk.boot.statemachine.core.StateMachine;
import io.github.atengk.boot.statemachine.model.TransitionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单生命周期业务管理服务
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderMapper orderMapper;
    private final StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine;

    public OrderService(OrderMapper orderMapper, StateMachine<OrderState, OrderEvent, OrderContext> orderStateMachine) {
        this.orderMapper = orderMapper;
        this.orderStateMachine = orderStateMachine;
    }

    /**
     * 创建初始订单（默认状态为 WAIT_PAY 待支付）
     *
     * @param request 创建请求
     * @return 订单 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(OrderCreateRequest request) {
        Long count = orderMapper.selectCount(new LambdaQueryWrapper<OrderDO>()
                .eq(OrderDO::getOrderSn, request.getOrderSn()));
        if (count != null && count > 0) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST, "订单编号已存在: " + request.getOrderSn());
        }

        OrderDO order = OrderDO.builder()
                .orderSn(request.getOrderSn())
                .status(OrderState.WAIT_PAY)
                .amount(request.getAmount())
                .version(0)
                .createdTime(LocalDateTime.now())
                .updatedTime(LocalDateTime.now())
                .build();
        orderMapper.insert(order);
        return convertToVO(order);
    }

    /**
     * 查询订单当前详情
     *
     * @param orderId 订单主键
     * @return 订单 VO
     */
    public OrderVO getOrder(Long orderId) {
        OrderDO order = getOrderOrThrow(orderId);
        return convertToVO(order);
    }

    /**
     * 执行订单状态机驱动跃迁（结合 CAS 乐观锁防重与本地事务原子性）
     *
     * @param orderId 订单 ID
     * @param request 跃迁请求
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> processTransition(Long orderId, OrderTransitionRequest request) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .amount(order.getAmount())
                .riskScore(request.getRiskScore() != null ? request.getRiskScore() : order.getRiskScore())
                .trackingNo(request.getTrackingNo() != null ? request.getTrackingNo() : order.getTrackingNo())
                .trackingRemark(request.getTrackingRemark())
                .operator(request.getOperator() != null ? request.getOperator() : "SYSTEM")
                .failTrigger(Boolean.TRUE.equals(request.getFailTrigger()))
                .build();

        // 实体状态 CAS 乐观锁防重流转门面（Fail-Fast 模式）
        OrderState targetState = orderStateMachine.fireEventWithCas(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                request.getEvent(),
                context,
                (ent, from, to) -> orderMapper.updateStatusWithCas(ent.getId(), from.name(), to.name(), ent.getVersion())
        );

        // CAS 更新成功后，同步内存实体的版本号
        order.setVersion(order.getVersion() + 1);

        // 状态更新成功后，若有附加字段（如风控评分或发货单号）一并同步持久化
        boolean needExtraUpdate = false;
        if (request.getRiskScore() != null) {
            order.setRiskScore(request.getRiskScore());
            needExtraUpdate = true;
        }
        if (request.getTrackingNo() != null) {
            order.setTrackingNo(request.getTrackingNo());
            needExtraUpdate = true;
        }
        if (needExtraUpdate) {
            orderMapper.updateById(order);
        }

        return TransitionResult.success(sourceState, targetState, request.getEvent());
    }

    /**
     * 执行内部自跃迁：追加物流追踪动态（状态保持 DELIVERING 不变，仅更新跟踪信息）
     *
     * @param orderId 订单 ID
     * @param request 物流追踪追加请求
     * @return 跃迁结果
     */
    @Transactional(rollbackFor = Exception.class)
    public TransitionResult<OrderState> addTrackingInfo(Long orderId, OrderTrackingRequest request) {
        OrderDO order = getOrderOrThrow(orderId);
        OrderState sourceState = order.getStatus();

        OrderContext context = OrderContext.builder()
                .orderId(order.getId())
                .orderSn(order.getOrderSn())
                .trackingNo(request.getTrackingNo())
                .trackingRemark(request.getTrackingRemark())
                .build();

        // 触发内部自跃迁
        OrderState targetState = orderStateMachine.fireEvent(
                order,
                OrderDO::getStatus,
                OrderDO::setStatus,
                OrderEvent.ADD_TRACKING,
                context
        );

        orderMapper.updateTracking(orderId, request.getTrackingNo(), request.getTrackingRemark());
        return TransitionResult.success(sourceState, targetState, OrderEvent.ADD_TRACKING);
    }

    /**
     * 导出当前机台 Mermaid 状态图
     *
     * @return Mermaid 图例语法文本
     */
    public String exportMermaidDiagram() {
        return orderStateMachine.exportMermaidDiagram();
    }

    private OrderDO getOrderOrThrow(Long orderId) {
        OrderDO order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在: id=" + orderId);
        }
        return order;
    }

    private OrderVO convertToVO(OrderDO order) {
        return OrderVO.builder()
                .id(order.getId())
                .orderSn(order.getOrderSn())
                .status(order.getStatus())
                .statusDesc(order.getStatus() != null ? order.getStatus().getDescription() : null)
                .amount(order.getAmount())
                .trackingNo(order.getTrackingNo())
                .trackingRemark(order.getTrackingRemark())
                .riskScore(order.getRiskScore())
                .version(order.getVersion())
                .updatedTime(order.getUpdatedTime())
                .build();
    }
}
