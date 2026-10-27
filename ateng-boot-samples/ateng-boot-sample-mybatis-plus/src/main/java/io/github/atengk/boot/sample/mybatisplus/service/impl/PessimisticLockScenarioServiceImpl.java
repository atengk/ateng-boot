package io.github.atengk.boot.sample.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.atengk.boot.mybatisplus.enums.LockMode;
import io.github.atengk.boot.mybatisplus.service.impl.AtengServiceImpl;
import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;
import io.github.atengk.boot.sample.mybatisplus.mapper.TaskQueueMapper;
import io.github.atengk.boot.sample.mybatisplus.service.PessimisticLockScenarioService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 悲观并发锁业务场景服务实现类
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Service
public class PessimisticLockScenarioServiceImpl extends AtengServiceImpl<TaskQueueMapper, TaskQueueEntity> implements PessimisticLockScenarioService {

    @Transactional(rollbackFor = Exception.class)
    @Override
    public List<TaskQueueEntity> pollAndLockTasks(int limit) {
        LambdaQueryWrapper<TaskQueueEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(TaskQueueEntity::getStatus, "PENDING")
                .orderByAsc(TaskQueueEntity::getId)
                .last(limit > 0 ? "LIMIT " + limit : "");

        List<TaskQueueEntity> tasks = listForUpdate(wrapper, LockMode.SKIP_LOCKED);
        for (TaskQueueEntity task : tasks) {
            task.setStatus("PROCESSING");
            updateById(task);
        }
        return tasks;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public TaskQueueEntity processTaskWithLock(Long taskId) {
        TaskQueueEntity task = getByIdForUpdate(taskId);
        if (task != null) {
            task.setStatus("COMPLETED");
            updateById(task);
        }
        return task;
    }
}
