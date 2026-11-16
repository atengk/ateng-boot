package io.github.atengk.boot.sample.mail.dto;

import io.github.atengk.boot.core.validation.ValidList;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 动态会话批量邮件发送请求数据传输对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicBatchMailRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 动态发信账号逻辑名称
     */
    @NotBlank(message = "动态账号标识不能为空")
    private String account;

    /**
     * 动态发件人电子邮箱（可选，为空时沿用默认发件人）
     */
    @Email(message = "动态发件人电子邮箱格式不合法")
    private String from;

    /**
     * 动态发件人展示昵称（可选）
     */
    private String fromName;

    /**
     * 批量邮件条目列表
     */
    @Valid
    @NotEmpty(message = "批量发信列表不能为空")
    private ValidList<SimpleTextMailRequest> items;
}
