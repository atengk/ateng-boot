package io.github.atengk.boot.encrypt.tool;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;

/**
 * 配置文件整文件就地加解密回写器（InPlaceRewriter 的语义别名）
 * 严格保持原有注释、缩进与空白格式不变，并自动生成 .bak 备份文件
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptFileRewriter extends InPlaceRewriter {

    public EncryptFileRewriter(MultiKeyRegistry keyRegistry, EncryptAlgorithm defaultAlgorithm,
                               String sensitiveKeyPattern, boolean autoDetectSensitiveKeys) {
        super(keyRegistry, defaultAlgorithm, sensitiveKeyPattern, autoDetectSensitiveKeys);
    }

    public EncryptFileRewriter(String defaultPassword, EncryptAlgorithm defaultAlgorithm) {
        super(defaultPassword, defaultAlgorithm);
    }

    public EncryptFileRewriter(String defaultPassword) {
        super(defaultPassword);
    }
}
