package io.github.atengk.boot.sample.ai.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.sample.ai.model.InventoryStatus;
import io.github.atengk.boot.sample.ai.model.OrderInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 订单与仓储履约受管工具组件
 * <p>
 * 遵循 Spring 受管规范（标注 {@link Component}），由智能体在 `@AiAgent(tools = {OrderTools.class})` 中挂载调用，
 * 并由框架底座 {@code ToolExecutionSandbox} 统一提供异常自愈转译与耗时统计。
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    /**
     * 根据订单号查询订单详情
     *
     * @param orderId 待查询的订单号
     * @return 订单详情信息
     */
    @Tool("根据订单号查询订单详情信息，包含买家标识、商品编号、购买数量与实付金额")
    public OrderInfo queryOrder(@P("待查询的订单业务主键，例如 ORD_20261103001") String orderId) {
        log.info("[TOOL-INVOKE] 正在查询订单履约详情: {}", orderId);

        // 模拟异常分支，用于触发大模型异常自愈反思
        if ("ORD_ERROR".equalsIgnoreCase(orderId)) {
            throw new BusinessException(CommonResultCode.NOT_FOUND, "订单不存在或已被物理注销: " + orderId);
        }

        // 模拟真实查询结果
        return new OrderInfo(
                orderId,
                "buyer_u10086",
                "SKU_LAPTOP_AI_PRO",
                1,
                new BigDecimal("9999.00"),
                "PAID"
        );
    }

    /**
     * 核验指定商品与数量的仓储库存
     *
     * @param skuId    商品编号
     * @param quantity 购买数量
     * @return 仓储可用性与锁定状态
     */
    @Tool("核验指定商品编号与购买数量的仓储库存充足性与锁定状态")
    public InventoryStatus verifyInventory(
            @P("商品编号SKU，例如 SKU_LAPTOP_AI_PRO") String skuId,
            @P("拟核验的购买件数") Integer quantity) {
        log.info("[TOOL-INVOKE] 正在核验仓储库存: skuId={}, quantity={}", skuId, quantity);

        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("核验数量必须为正整数，实际传入: " + quantity);
        }

        return new InventoryStatus(skuId, true, 500, "华东一号自动化智能仓");
    }
}
