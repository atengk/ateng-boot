package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.tool.InPlaceRewriter;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 配置文件就地加解密回写场景服务
 * 演示通过 InPlaceRewriter 对 YAML (.yml/.yaml) 与 Properties (.properties) 配置文件执行无损格式就地加密与逆向还原
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
public class InPlaceRewriteScenarioService {

    /**
     * 对指定配置文件执行就地加密，自动生成 .bak 备份并保留原始注释与缩进排版
     *
     * @param configFile 待加密的 YAML 或 Properties 物理文件路径
     * @param password 加密主密钥
     * @param algorithm 加密算法
     * @return 实际被加密替换的敏感字段行数
     * @throws IOException 文件读写异常
     */
    public int rewriteEncrypt(Path configFile, String password, EncryptAlgorithm algorithm) throws IOException {
        InPlaceRewriter rewriter = new InPlaceRewriter(password, algorithm);
        return rewriter.rewriteEncrypt(configFile);
    }

    /**
     * 对已加密的配置文件执行就地解密还原，恢复原始真实明文
     *
     * @param configFile 待解密的文件路径
     * @param password 解密主密钥
     * @param algorithm 解密算法
     * @return 实际被还原的密文行数
     * @throws IOException 文件读写异常
     */
    public int rewriteDecrypt(Path configFile, String password, EncryptAlgorithm algorithm) throws IOException {
        InPlaceRewriter rewriter = new InPlaceRewriter(password, algorithm);
        return rewriter.rewriteDecrypt(configFile);
    }

    /**
     * 针对 YAML 配置文件采用默认 AES-GCM 算法执行就地加密
     *
     * @param yamlFile YAML 文件路径
     * @param password 加密主密钥
     * @return 加密字段数
     * @throws IOException 文件异常
     */
    public int rewriteEncryptYaml(Path yamlFile, String password) throws IOException {
        return rewriteEncrypt(yamlFile, password, EncryptAlgorithm.AES_GCM);
    }

    /**
     * 针对 YAML 配置文件采用默认 AES-GCM 算法执行就地解密
     *
     * @param yamlFile YAML 文件路径
     * @param password 解密主密钥
     * @return 还原字段数
     * @throws IOException 文件异常
     */
    public int rewriteDecryptYaml(Path yamlFile, String password) throws IOException {
        return rewriteDecrypt(yamlFile, password, EncryptAlgorithm.AES_GCM);
    }

    /**
     * 针对 Properties 配置文件采用默认 AES-GCM 算法执行就地加密
     *
     * @param propFile Properties 文件路径
     * @param password 加密主密钥
     * @return 加密字段数
     * @throws IOException 文件异常
     */
    public int rewriteEncryptProperties(Path propFile, String password) throws IOException {
        return rewriteEncrypt(propFile, password, EncryptAlgorithm.AES_GCM);
    }

    /**
     * 针对 Properties 配置文件采用默认 AES-GCM 算法执行就地解密
     *
     * @param propFile Properties 文件路径
     * @param password 解密主密钥
     * @return 还原字段数
     * @throws IOException 文件异常
     */
    public int rewriteDecryptProperties(Path propFile, String password) throws IOException {
        return rewriteDecrypt(propFile, password, EncryptAlgorithm.AES_GCM);
    }
}
