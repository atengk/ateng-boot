package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * EL 表达式在线预校验请求参数
 *
 * @author Ateng
 * @since 2026-11-06
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicElValidateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待预校验的 LiteFlow EL 规则表达式
     */
    @NotBlank(message = "EL 规则表达式 (el) 不能为空")
    private String el;
}
