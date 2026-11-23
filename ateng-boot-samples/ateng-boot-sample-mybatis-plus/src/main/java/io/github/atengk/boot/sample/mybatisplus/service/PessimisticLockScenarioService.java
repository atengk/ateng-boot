package io.github.atengk.boot.sample.mybatisplus.service;

import io.github.atengk.boot.mybatisplus.service.AtengBaseService;
import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;
import io.github.atengk.boot.sample.mybatisplus.model.TaskQueueVO;

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

    /**
     * 单任务条件悲观排他锁定并投影为强类型 VO
     *
     * @param taskName 任务名称
     * @return 锁定获取到的任务视图对象
     */
    TaskQueueVO getOneTaskForUpdate(String taskName);

    /**
     * 根据主键 ID 获取悲观排他锁（FOR UPDATE）并直接投影为强类型 VO
     *
     * @param taskId 任务 ID
     * @return 任务视图对象，未命中时返回 null
     */
    TaskQueueVO getTaskByIdForUpdate(Long taskId);

    /**
     * 按状态执行悲观跳锁（SKIP_LOCKED）列表锁定检索并投影为强类型 VO 列表
     *
     * @param status 任务状态（可选）
     * @param limit  单次拉取上限
     * @return 任务视图对象列表
     */
    List<TaskQueueVO> listTasksForUpdate(String status, int limit);
}
