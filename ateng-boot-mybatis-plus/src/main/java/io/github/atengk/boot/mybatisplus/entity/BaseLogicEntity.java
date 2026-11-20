package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 常规软删业务持久层实体抽象基类
 * 在 BaseEntity 基础上实现 LogicDeletable 契约，采用 64 位长整型主键占位逻辑删除标记（delval = "id"），
 * 消除组合唯一索引多次软删除物理冲突且不强绑乐观锁版本号
 *
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseLogicEntity extends BaseEntity implements LogicDeletable<Long> {

    private static final long serialVersionUID = 1L;

    /**
     * 逻辑删除标识（0: 未删除, 删除后动态更新为自身雪花主键 ID 值；标配 @JsonIgnore 防对外接口泄露）
     */
    @TableLogic(value = "0", delval = "id")
    @JsonIgnore
    private Long deleted;
}
