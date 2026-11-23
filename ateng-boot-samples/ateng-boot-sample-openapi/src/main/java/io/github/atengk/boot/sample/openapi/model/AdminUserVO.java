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

    @FieldSensitive(SensitiveType.EMAIL)
    @Schema(description = "工作电子邮箱", example = "zhangsan@example.com")
    private String email;

    @FieldSensitive(SensitiveType.MOBILE_PHONE)
    @Schema(description = "联系手机号码")
    private String phone;

    @FieldSensitive(SensitiveType.ID_CARD)
    @Schema(description = "身份证号码")
    private String idCard;

    @FieldSensitive(SensitiveType.IPV4)
    @Schema(description = "最近登录 IPv4")
    private String lastLoginIp;

    @Schema(description = "账号状态")
    private UserStatusEnum status;

    @Schema(description = "创建时间", example = "2026-10-22T10:00:00")
    private LocalDateTime createTime;

    public AdminUserVO() {
    }

    public AdminUserVO(Long id, String username, String realName, String email, String phone, UserStatusEnum status, LocalDateTime createTime) {
        this(id, username, realName, email, phone, "110101199001012345", "192.168.1.100", status, createTime);
    }

    public AdminUserVO(Long id, String username, String realName, String email, String phone,
                       String idCard, String lastLoginIp, UserStatusEnum status, LocalDateTime createTime) {
        this.id = id;
        this.username = username;
        this.realName = realName;
        this.email = email;
        this.phone = phone;
        this.idCard = idCard;
        this.lastLoginIp = lastLoginIp;
        this.status = status;
        this.createTime = createTime;
    }

    public static AdminUserVO of(Long id, String username, String realName) {
        return of(id, username, realName, username + "@example.com", "13800138000");
    }

    public static AdminUserVO of(Long id, String username, String realName, String email, String phone) {
        return new AdminUserVO(id, username, realName, email, phone, UserStatusEnum.ENABLE, LocalDateTime.now());
    }

    public static AdminUserVO of(Long id, String username, String realName, String email, String phone, UserStatusEnum status) {
        return new AdminUserVO(id, username, realName, email, phone, status, LocalDateTime.now());
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

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getLastLoginIp() {
        return lastLoginIp;
    }

    public void setLastLoginIp(String lastLoginIp) {
        this.lastLoginIp = lastLoginIp;
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
