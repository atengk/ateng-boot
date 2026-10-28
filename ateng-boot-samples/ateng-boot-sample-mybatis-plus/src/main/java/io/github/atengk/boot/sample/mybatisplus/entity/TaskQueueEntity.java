package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.github.atengk.boot.mybatisplus.entity.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 悲观排队锁任务队列实体
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@TableName("task_queue")
public class TaskQueueEntity extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 任务名称
     */
    private String taskName;

    /**
     * 任务状态（PENDING: 待处理, PROCESSING: 处理中, COMPLETED: 已完成）
     */
    private String status;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;
}
