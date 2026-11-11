package io.github.atengk.boot.sample.tcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 主动 SPI 治理链（过滤器、相关性提取器、函数式处理器）实时探针请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpiProbeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 探针触发模式（AUDIT_RPC 验证关联提取与函数式处理器；FILTER_BLOCK 验证非法特征报文拦截）
     */
    @NotBlank(message = "探针模式 mode 不能为空")
    @Pattern(regexp = "AUDIT_RPC|FILTER_BLOCK", message = "mode 仅支持 AUDIT_RPC 或 FILTER_BLOCK")
    @Builder.Default
    private String mode = "AUDIT_RPC";

    /**
     * 附带探针摘要内容
     */
    @NotBlank(message = "探针摘要 note 不能为空")
    @Builder.Default
    private String note = "SAMPLE_SPI_PROBE";

    /**
     * 可选指定请求关联流水号
     */
    private String requestId;
}
