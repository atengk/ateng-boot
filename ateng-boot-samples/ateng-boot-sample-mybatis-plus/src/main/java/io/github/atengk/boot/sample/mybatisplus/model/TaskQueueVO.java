package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.sample.mybatisplus.entity.TaskQueueEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 悲观排队锁任务队列视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskQueueVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 任务主键 ID
     */
    private Long id;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 任务状态（PENDING / PROCESSING / COMPLETED）
     */
    private String status;

    /**
     * 乐观锁版本号
     */
    private Integer version;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 更新人
     */
    private String updateBy;

    public static TaskQueueVO fromEntity(TaskQueueEntity entity) {
        if (entity == null) {
            return null;
        }
        TaskQueueVO vo = new TaskQueueVO();
        vo.setId(entity.getId());
        vo.setTaskName(entity.getTaskName());
        vo.setStatus(entity.getStatus());
        vo.setVersion(entity.getVersion());
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setCreateBy(entity.getCreateBy());
        vo.setUpdateBy(entity.getUpdateBy());
        return vo;
    }
}
