package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.core.annotation.InEnum;
import io.github.atengk.boot.sample.web.enums.OrderTypeEnum;
import io.github.atengk.boot.sample.web.enums.UserStatusEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.io.Serializable;

/**
 * 演示枚举传输请求 DTO
 * 结合 @InEnum 格式校验第一道防线与 Jackson 强类型枚举反序列化
 *
 * @author Ateng
 * @since 2026-10-28
 */
public class WebEnumUserRequest implements Serializable {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotNull(message = "状态编码不能为空")
    @InEnum(value = UserStatusEnum.class, message = "用户状态编码不合法")
    private Integer statusCode;

    private UserStatusEnum status;

    private OrderTypeEnum orderType;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public UserStatusEnum getStatus() {
        return status;
    }

    public void setStatus(UserStatusEnum status) {
        this.status = status;
    }

    public OrderTypeEnum getOrderType() {
        return orderType;
    }

    public void setOrderType(OrderTypeEnum orderType) {
        this.orderType = orderType;
    }
}
