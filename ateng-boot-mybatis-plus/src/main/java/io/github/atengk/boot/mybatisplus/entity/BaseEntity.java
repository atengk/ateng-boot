package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 持久层通用实体抽象基类
 * 统一定义雪花算法分布式主键、创建与修改时间、字符型操作人审计、乐观锁版本号，以及在 JSON 序列化中自动脱敏忽略的逻辑删除标记
 *
 * @author Ateng
 * @since 2026-10-15
 */
@Getter
@Setter
@ToString
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 雪花算法全局唯一主键 ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 创建时间（插入时自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间（插入与更新时自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 创建人唯一标识（插入时自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /**
     * 修改人唯一标识（插入与更新时自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /**
     * 乐观锁版本号
     */
    @Version
    private Integer version;

    /**
     * 逻辑删除标识（0: 未删除, 1: 已删除；标配 @JsonIgnore 防对外接口泄露）
     */
    @TableLogic
    @JsonIgnore
    private Integer deleted;
}
