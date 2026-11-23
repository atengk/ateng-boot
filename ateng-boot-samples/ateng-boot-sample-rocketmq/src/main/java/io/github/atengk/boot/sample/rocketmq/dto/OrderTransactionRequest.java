package io.github.atengk.boot.sample.rocketmq.dto;

import io.github.atengk.boot.sample.rocketmq.model.OrderMessage;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 事务订单发送请求 DTO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderTransactionRequest {

    private static final String DEFAULT_STATUS = "TX_PENDING";

    /**
     * 业务订单号
     */
    @NotBlank(message = "订单ID不能为空")
    private String orderId;

    /**
     * 订单金额
     */
    @NotNull(message = "订单金额不能为空")
    @PositiveOrZero(message = "订单金额不能为负数")
    private Double amount;

    /**
     * 客户姓名
     */
    private String customerName;

    /**
     * 订单状态
     */
    private String status;

    /**
     * 业务消息标签（可选，默认为 create）
     */
    private String tag;

    /**
     * SQL92 自定义过滤扩展属性（可选）
     */
    private Map<String, String> properties;

    /**
     * 本地事务执行决断（true: COMMIT / 提交成功; false: ROLLBACK / 触发回滚）
     */
    private Boolean localSuccess;

    /**
     * 转换为标准订单领域消息对象
     *
     * @return 订单消息实体
     */
    public OrderMessage toOrderMessage() {
        return OrderMessage.builder()
                .orderId(orderId)
                .amount(amount)
                .customerName(customerName)
                .status(status != null ? status : DEFAULT_STATUS)
                .build();
    }

    /**
     * 解析并校验批量事务请求中的统一业务标签（若各条目传入不同非空标签则 Fail-Fast）
     *
     * @param requests 批量事务请求列表
     * @return 统一的业务标签（未指定时返回 null）
     */
    public static String resolveBatchTag(List<OrderTransactionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        String resolvedTag = null;
        for (OrderTransactionRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("批量事务请求列表中不能包含 null 元素");
            }
            if (request.getTag() == null || request.getTag().isBlank()) {
                continue;
            }
            String trimmedTag = request.getTag().trim();
            if (resolvedTag == null) {
                resolvedTag = trimmedTag;
            } else if (!Objects.equals(resolvedTag, trimmedTag)) {
                throw new IllegalArgumentException("批量事务消息标签存在冲突取值，请保持批次内标签一致");
            }
        }
        return resolvedTag;
    }

    /**
     * 聚合并校验批量事务请求中的 SQL92 扩展属性（含 null 值在内任一键取值冲突则 Fail-Fast）
     *
     * @param requests 批量事务请求列表
     * @return 聚合后的扩展属性映射（无属性时返回 null）
     */
    public static Map<String, String> resolveBatchProperties(List<OrderTransactionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        for (OrderTransactionRequest request : requests) {
            if (request == null) {
                throw new IllegalArgumentException("批量事务请求列表中不能包含 null 元素");
            }
            Map<String, String> props = request.getProperties();
            if (props == null || props.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String> entry : props.entrySet()) {
                String key = entry.getKey();
                if (merged.containsKey(key)) {
                    if (!Objects.equals(merged.get(key), entry.getValue())) {
                        throw new IllegalArgumentException(
                                "批量事务消息属性键 [" + key + "] 存在冲突取值，请保持批次内属性一致");
                    }
                } else {
                    merged.put(key, entry.getValue());
                }
            }
        }
        return merged.isEmpty() ? null : merged;
    }
}
