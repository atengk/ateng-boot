package io.github.atengk.boot.sample.encrypt.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * 多 Key 命名注册表状态展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiKeyStatusVO {

    /**
     * 是否已配置全局主解密密钥
     */
    private boolean defaultKeyConfigured;

    /**
     * 已注册的命名密钥别名集合
     */
    private Set<String> registeredKeyIds;

    /**
     * 密钥总数
     */
    private int totalKeys;
}
