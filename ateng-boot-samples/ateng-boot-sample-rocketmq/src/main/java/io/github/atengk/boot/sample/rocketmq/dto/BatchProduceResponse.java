package io.github.atengk.boot.sample.rocketmq.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 批量消息发送统一响应 VO
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchProduceResponse {

    /**
     * 请求批量总数
     */
    private int total;

    /**
     * 成功投递数量
     */
    private int successCount;

    /**
     * 包含的订单号列表
     */
    private List<String> orderIds;

    /**
     * 生成的物理消息 ID 列表
     */
    private List<String> messageIds;
}
