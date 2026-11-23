package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 多 Key 别名动态路由与密钥轮换场景服务
 * 演示 ENC[keyId](...) 别名路由定向解密、动态注册/轮换命名密钥以及多密钥注册表检索
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
@RequiredArgsConstructor
public class MultiKeyRoutingScenarioService {

    private final MultiKeyRegistry multiKeyRegistry;
    private final EncryptTemplate encryptTemplate;

    /**
     * 使用指定多 Key 路由标记解密密文字符串（如 ENC[redis-cluster](...)）
     *
     * @param token 携带 [keyId] 路由标签的密文标记
     * @return 解密还原后的真实明文
     */
    public String decryptRoutedToken(String token) {
        return encryptTemplate.decrypt(token);
    }

    /**
     * 动态注册或轮换命名密钥
     *
     * @param keyId 密钥别名标识
     * @param newPassword 新密钥明文
     */
    public void rotateKey(String keyId, String newPassword) {
        multiKeyRegistry.registerKey(keyId, newPassword);
    }

    /**
     * 判断指定密钥别名是否已注册
     *
     * @param keyId 密钥别名标识
     * @return 若已注册返回 true
     */
    public boolean containsKey(String keyId) {
        return multiKeyRegistry.hasKey(keyId);
    }

    /**
     * 获取指定别名的密钥明文（不存在时回退至全局默认主密钥）
     *
     * @param keyId 密钥别名标识
     * @return 密钥明文字符串
     */
    public String getKeyOrDefault(String keyId) {
        return multiKeyRegistry.getKeyOrDefault(keyId);
    }
}
