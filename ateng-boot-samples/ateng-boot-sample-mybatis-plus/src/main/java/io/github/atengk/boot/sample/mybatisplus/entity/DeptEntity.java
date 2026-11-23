package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseLogicEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 系统部门实体对象
 * 继承 BaseLogicEntity（包含主键 ID、时间戳、操作人及 64 位 ID 占位软删除，无需乐观锁）
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_dept")
public class DeptEntity extends BaseLogicEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 部门名称
     */
    private String deptName;

    /**
     * 部门状态
     */
    private String status;

    public DeptEntity(String deptName, String status) {
        this.deptName = deptName;
        this.status = status;
    }
}
