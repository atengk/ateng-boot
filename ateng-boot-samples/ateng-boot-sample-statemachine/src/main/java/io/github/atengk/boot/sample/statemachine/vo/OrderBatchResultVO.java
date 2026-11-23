package io.github.atengk.boot.sample.statemachine.vo;

import io.github.atengk.boot.sample.statemachine.constant.OrderState;
import io.github.atengk.boot.sample.statemachine.entity.OrderDO;
import io.github.atengk.boot.statemachine.model.BatchTransitionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量切片状态机执行结果展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderBatchResultVO {

    /**
     * 待处理总数
     */
    private int totalCount;

    /**
     * 流转成功总数
     */
    private int successCount;

    /**
     * 流转失败总数
     */
    private int failureCount;

    /**
     * 成功订单 ID 列表
     */
    private List<Long> successOrderIds;

    /**
     * 失败订单 ID 与错误原因映射
     */
    private Map<Long, String> failures;

    /**
     * 是否全部流转成功
     */
    private boolean allSuccess;

    /**
     * 从底层 BatchTransitionResult 转换为 VO
     *
     * @param result 批量执行结果
     * @return 批量展示视图对象
     */
    public static OrderBatchResultVO fromResult(BatchTransitionResult<OrderState, OrderDO> result) {
        if (result == null) {
            return OrderBatchResultVO.builder()
                    .totalCount(0)
                    .successCount(0)
                    .failureCount(0)
                    .successOrderIds(new ArrayList<>())
                    .failures(new LinkedHashMap<>())
                    .allSuccess(true)
                    .build();
        }

        List<Long> successIds = new ArrayList<>();
        if (result.getSuccessEntities() != null) {
            for (OrderDO order : result.getSuccessEntities()) {
                if (order != null && order.getId() != null) {
                    successIds.add(order.getId());
                }
            }
        }

        Map<Long, String> failMap = new LinkedHashMap<>();
        if (result.getFailures() != null) {
            result.getFailures().forEach((entity, ex) -> {
                Long id = (entity != null) ? entity.getId() : null;
                failMap.put(id, ex != null ? ex.getMessage() : "Unknown error");
            });
        }

        return OrderBatchResultVO.builder()
                .totalCount(result.getTotalCount())
                .successCount(result.getSuccessCount())
                .failureCount(result.getFailureCount())
                .successOrderIds(successIds)
                .failures(failMap)
                .allSuccess(result.isAllSuccess())
                .build();
    }
}
