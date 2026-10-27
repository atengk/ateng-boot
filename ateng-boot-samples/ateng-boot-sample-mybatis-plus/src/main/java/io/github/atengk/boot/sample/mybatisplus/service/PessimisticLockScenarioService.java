package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;

import java.util.List;

/**
 * 悲观并发锁业务场景服务契约
 * 演示行排他锁（getByIdForUpdate）与高并发任务队列排队跳锁抢占消费（listForUpdate with SKIP_LOCKED）
 *
 * @author Ateng
 * @since 2026-10-27
 */
public interface PessimisticLockScenarioService extends AtengBaseService<TaskQueueEntity> {

    /**
     * 并发任务抢占消费（基于 SKIP LOCKED 跳过其他事务持有的锁定行）
     *
     * @param limit 单次拉取消费上限
     * @return 当前事务成功锁定并抢占到的任务列表
     */
    List<TaskQueueEntity> pollAndLockTasks(int limit);

    /**
     * 单任务悲观排他锁定并完成业务处理
     *
     * @param taskId 任务 ID
     * @return 锁定并更新后的实体对象
     */
    TaskQueueEntity processTaskWithLock(Long taskId);
}
