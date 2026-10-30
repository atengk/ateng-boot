package io.github.atengk.boot.sample.openapi.model;

import io.github.atengk.boot.core.annotation.FieldSensitive;
import io.github.atengk.boot.core.enums.SensitiveType;
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

    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    @Schema(description = "联系手机号码")
    private String phone;

    @Schema(description = "账号状态")
    private UserStatusEnum status;

    @Schema(description = "创建时间", example = "2026-10-22T10:00:00")
    private LocalDateTime createTime;

    public AdminUserVO() {
    }

    public AdminUserVO(Long id, String username, String realName, String email, String phone, UserStatusEnum status, LocalDateTime createTime) {
        this.id = id;
        this.username = username;
        this.realName = realName;
        this.email = email;
        this.phone = phone;
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

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public UserStatusEnum getStatus() {
        return status;
    }

    public void setStatus(UserStatusEnum status) {
        this.status = status;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
