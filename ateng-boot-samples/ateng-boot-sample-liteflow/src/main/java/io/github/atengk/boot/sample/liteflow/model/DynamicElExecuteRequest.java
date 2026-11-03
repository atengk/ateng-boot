package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 即时 EL 表达式执行请求参数
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class DynamicElExecuteRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 即时执行的 LiteFlow EL 规则表达式
     */
    @NotBlank(message = "EL 规则表达式 (el) 不能为空")
    private String el;

    /**
     * 业务上下文自定义传参（可选）
     */
    private String payload;
}
