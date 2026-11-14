package io.github.atengk.boot.sample.http.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * getOptional 空安全查询结果视图对象 (404 自动转 Optional.empty())
 *
 * @author Ateng
 * @since 2026-11-14
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OptionalLookupVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 查询资源标识
     */
    private Long id;

    /**
     * 是否命中目标资源
     */
    private boolean found;

    /**
     * 空安全查询语义状态描述 (FOUND / NOT_FOUND_EMPTY)
     */
    private String statusSummary;

    /**
     * 命中时返回的用户信息（未命中时为 null）
     */
    private UserResponse user;
}
