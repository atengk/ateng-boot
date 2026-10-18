package io.github.atengk.boot.storage.properties;

import lombok.Data;

import java.util.Collections;
import java.util.Set;

/**
 * 存储安全防卫与魔数特征嗅探配置属性
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Data
public class StorageGuardProperties {

    /**
     * 是否开启文件安全防卫与魔数嗅探拦截（默认 false）
     */
    private boolean enabled = false;

    /**
     * 是否开启严格魔数强校验（默认 false）
     * 开启时：所有已知二进制格式其内容特征必须与扩展名绝对吻合；
     * 未开启时：依然强制阻断可执行文件或脚本伪装成文档/图片的恶意上传
     */
    private boolean strictMagicCheck = false;

    /**
     * 允许上传的文件扩展名白名单集合（空集合表示不限制白名单）
     */
    private Set<String> allowedExtensions = Collections.emptySet();
}
