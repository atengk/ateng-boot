package io.github.atengk.boot.sample.liteflow.context;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 订单履约与反向补偿业务上下文
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class OrderFulfillmentContext implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 业务订单标识
     */
    private String orderId;

    /**
     * 目标商品 SKU 编号
     */
    private String skuId;

    /**
     * 履约扣减数量
     */
    private Integer quantity;

    /**
     * 是否模拟异常阻断
     */
    private boolean simulateFailure;

    /**
     * 仓储库存是否已锁定扣减
     */
    private boolean inventoryDeducted;

    /**
     * 物流运单是否已生成
     */
    private boolean warehouseOrderCreated;

    /**
     * 出库发货通知是否已发送
     */
    private boolean shippingNoticeSent;

    /**
     * 最终履约状态
     */
    private String status = "INITIAL";

    /**
     * 错误原因描述
     */
    private String errorSummary;

    /**
     * 逆序回滚补偿动作审计日志（并发安全）
     */
    private List<String> compensationLogs = new CopyOnWriteArrayList<>();

    /**
     * 记录一条补偿操作日志
     *
     * @param log 补偿描述
     */
    public void addCompensationLog(String log) {
        compensationLogs.add(log);
    }
}
