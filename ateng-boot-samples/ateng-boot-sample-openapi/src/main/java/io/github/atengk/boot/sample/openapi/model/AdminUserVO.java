package io.github.atengk.boot.sample.openapi.model;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 管理端用户视图响应对象
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Schema(description = "管理端用户视图对象")
public class AdminUserVO {

    @Schema(description = "用户唯一 ID", example = "10001")
    private Long id;

    @Schema(description = "用户登录账号", example = "admin_dev")
    private String username;

    @Schema(description = "用户真实姓名", example = "张三")
    private String realName;

    @Schema(description = "工作电子邮箱", example = "zhangsan@example.com")
    private String email;

    @Schema(description = "账号状态 (1 正常，0 停用)", example = "1")
    private Integer status;

    @Schema(description = "创建时间", example = "2026-10-22T10:00:00")
    private LocalDateTime createTime;

    public AdminUserVO() {
    }

    public AdminUserVO(Long id, String username, String realName, String email, Integer status, LocalDateTime createTime) {
        this.id = id;
        this.username = username;
        this.realName = realName;
        this.email = email;
        this.status = status;
        this.createTime = createTime;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRealName() {
        return realName;
    }

    public void setRealName(String realName) {
        this.realName = realName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
