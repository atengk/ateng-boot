package io.github.atengk.boot.sample.encrypt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 环境属性变更与解密缓存刷新请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyRefreshRequest {

    /**
     * 变更的属性键名集合（为 null 或空时代表全量刷新）
     */
    private Set<String> changedKeys;
}
