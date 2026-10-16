package io.github.atengk.boot.sample.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.github.atengk.boot.mybatisplus.entity.BaseIdEntity;

import java.time.LocalDateTime;

/**
 * 业务文档测试实体（用于数据权限 @DataScope 细粒度过滤验证）
 *
 * @author Ateng
 * @since 2026-10-16
 */
@TableName("sys_document")
public class SysDocumentEntity extends BaseIdEntity {

    private String title;

    private Long deptId;

    private Long userId;

    private LocalDateTime createTime;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getDeptId() {
        return deptId;
    }

    public void setDeptId(Long deptId) {
        this.deptId = deptId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
