package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseDeleteIdEntity;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 联合唯一键软删除业务演示实体
 * 继承 BaseDeleteIdEntity，通过 (code, deleted) 联合唯一键消除多次软删除物理冲突
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@TableName("soft_delete_item")
public class SoftDeleteItemEntity extends BaseDeleteIdEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 业务唯一编码
     */
    private String code;

    /**
     * 物品名称
     */
    private String name;
}
