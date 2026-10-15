package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 雪花算法长整型主键极简持久层实体抽象基类
 * 仅声明雪花长整型 id，适用于关联映射表、流水日志表等无审计时间字段的轻量表
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseIdEntity extends BaseGenericEntity<Long> {

    private static final long serialVersionUID = 1L;

    /**
     * 雪花算法全局唯一主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
}
