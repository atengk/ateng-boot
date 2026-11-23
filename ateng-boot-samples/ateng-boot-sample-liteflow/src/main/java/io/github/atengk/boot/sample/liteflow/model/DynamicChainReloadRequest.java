package io.github.atengk.boot.sample.liteflow.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 动态规则链热重载请求参数
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Data
public class DynamicChainReloadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 规则链唯一标识
     */
    @NotBlank(message = "规则链标识 (chainId) 不能为空")
    private String chainId;

    /**
     * 最新的 LiteFlow EL 规则表达式
     */
    @NotBlank(message = "EL 规则表达式 (el) 不能为空")
    private String el;
}
