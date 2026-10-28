package io.github.atengk.boot.sample.mybatisplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseFullEntity;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 系统部门实体对象
 * 继承 BaseFullEntity（包含主键 ID、时间戳、操作人、乐观锁与逻辑删除）
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@TableName("sys_dept")
public class DeptEntity extends BaseFullEntity {

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
