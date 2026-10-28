package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.sample.web.enums.OrderTypeEnum;
import io.github.atengk.boot.sample.web.enums.UserStatusEnum;

import java.io.Serializable;

/**
 * 演示枚举响应传输 VO
 *
 * @author Ateng
 * @since 2026-10-28
 */
public record WebEnumUserResponse(
        String username,
        Integer statusCode,
        UserStatusEnum status,
        OrderTypeEnum orderType
) implements Serializable {
}
