package io.github.atengk.boot.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 泛型主键占位软删持久层实体抽象基类
 * 在 BaseGenericAuditEntity 基础上实现 LogicDeletable 契约，追加与主键类型对齐的自身主键占位逻辑删除标记，
 * 兼容联合唯一索引多次删除重建场景且不强绑乐观锁版本号
 *
 * @param <ID> 主键及逻辑删除占位类型
 * @author Ateng
 * @since 2026-11-20
 */
@Getter
@Setter
@ToString(callSuper = true)
public abstract class BaseGenericLogicEntity<ID extends Serializable> extends BaseGenericAuditEntity<ID> implements LogicDeletable<ID> {

    private static final long serialVersionUID = 1L;

    /**
     * 逻辑删除标识（未删除为 0 / "0"，删除后更新为自身主键 ID；标配 @JsonIgnore 防对外接口泄露）
     */
    @TableLogic(value = "0", delval = "id")
    @JsonIgnore
    private ID deleted;
}
