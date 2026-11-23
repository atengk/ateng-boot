package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 上下文路径规范化响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class ContextPathNormalizeVO {

    /**
     * 原始输入路径
     */
    private String rawPath;

    /**
     * 规范化收敛后的标准路径
     */
    private String normalizedPath;
}
