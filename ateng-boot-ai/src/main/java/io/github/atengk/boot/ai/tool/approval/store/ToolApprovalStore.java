package io.github.atengk.boot.ai.tool.approval.store;

import io.github.atengk.boot.ai.tool.approval.ToolApprovalStatus;
import io.github.atengk.boot.ai.tool.approval.ToolApprovalTicket;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 高危工具审批工单持久化存储 SPI 顶层接口
 *
 * @author Ateng
 * @since 2026-11-06
 */
public interface ToolApprovalStore {

    /**
     * 持久化保存工单快照并挂载生存时间 TTL
     *
     * @param ticket 工单快照实体
     * @param ttl    有效生存时间
     */
    void save(ToolApprovalTicket ticket, Duration ttl);

    /**
     * 根据工单标识查找工单快照
     *
     * @param ticketId 工单标识
     * @return 匹配的工单快照（若不存在或已过期则返回空）
     */
    Optional<ToolApprovalTicket> findById(String ticketId);

    /**
     * 原子更新工单状态及审核结果（CAS 机制防并发竞争）
     *
     * @param ticketId             工单标识
     * @param expectedStatus       期望的原状态（如 PENDING）
     * @param newStatus            拟跃迁的新状态
     * @param comment              审核批注意见
     * @param executionResultJson  执行结果 JSON
     * @param errorMessage         异常错误信息
     * @return 更新是否成功
     */
    boolean updateStatus(String ticketId,
                         ToolApprovalStatus expectedStatus,
                         ToolApprovalStatus newStatus,
                         String comment,
                         String executionResultJson,
                         String errorMessage);

    /**
     * 物理删除或移除指定工单
     *
     * @param ticketId 工单标识
     * @return 是否成功移除
     */
    boolean delete(String ticketId);

    /**
     * 查询当前所有处于 PENDING 待审批状态的有效工单列表
     *
     * @return 待审批工单列表
     */
    List<ToolApprovalTicket> listPending();
}
