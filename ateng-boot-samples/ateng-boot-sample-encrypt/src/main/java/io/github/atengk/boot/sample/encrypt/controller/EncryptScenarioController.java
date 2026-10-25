package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import io.github.atengk.boot.sample.encrypt.model.DecryptRequest;
import io.github.atengk.boot.sample.encrypt.model.EncryptRequest;
import io.github.atengk.boot.sample.encrypt.model.SampleSecretVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配置加解密场景演示与运维接口 Controller
 * 演示运行时环境透明解密呈现、编程式加解密门面调用与多 Key 别名解析
 *
 * @author Ateng
 * @since 2026-10-25
 */
@RestController
@RequestMapping("/api/encrypt")
@RequiredArgsConstructor
public class EncryptScenarioController {

    private final SampleSecretProperties secretProperties;
    private final CiphertextParser ciphertextParser;
    private final MultiKeyRegistry multiKeyRegistry;

    /**
     * 查看应用当前透明解密绑定的敏感配置明文
     *
     * @return 敏感配置 VO
     */
    @GetMapping("/secrets")
    public Result<SampleSecretVO> getDecryptedSecrets() {
        SampleSecretVO vo = SampleSecretVO.builder()
                .databasePassword(secretProperties.getDatabasePassword())
                .redisPassword(secretProperties.getRedisPassword())
                .thirdPartyToken(secretProperties.getThirdPartyToken())
                .build();
        return Result.success(vo);
    }

    /**
     * 编程式加密门面测试端点
     *
     * @param request 加密请求入参
     * @return ENC(...) 格式密文字符串
     */
    @PostMapping("/tool/encrypt")
    public Result<String> encryptText(@Valid @RequestBody EncryptRequest request) {
        EncryptAlgorithm algo = request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM;
        String ciphertext = ConfigEncryptor.encrypt(request.getPlaintext(), request.getPassword(), algo);
        String encToken = CiphertextParser.formatToken(request.getKeyId(), algo, ciphertext);
        return Result.success(encToken);
    }

    /**
     * 编程式解密门面测试端点
     *
     * @param request 解密请求入参
     * @return 还原后的明文字符串
     */
    @PostMapping("/tool/decrypt")
    public Result<String> decryptText(@Valid @RequestBody DecryptRequest request) {
        String cipher = request.getCiphertext().trim();
        EncryptAlgorithm algo = request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM;

        if (ciphertextParser.hasEncrypted(cipher)) {
            String plain = ciphertextParser.replaceTokens(cipher, token -> {
                EncryptAlgorithm effectiveAlgo = token.getAlgorithm() != null ? token.getAlgorithm() : algo;
                ConfigDecryptor decryptor = getDecryptor(effectiveAlgo);
                try {
                    return decryptor.decrypt(token.getCiphertext(), request.getPassword());
                } catch (Exception e) {
                    throw new IllegalStateException("解密失败: " + e.getMessage(), e);
                }
            });
            return Result.success(plain);
        }

        ConfigDecryptor decryptor = getDecryptor(algo);
        try {
            String plain = decryptor.decrypt(cipher, request.getPassword());
            return Result.success(plain);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败: " + e.getMessage(), e);
        }
    }

    private ConfigDecryptor getDecryptor(EncryptAlgorithm algorithm) {
        return switch (algorithm) {
            case AES_GCM -> new AesGcmConfigDecryptor();
            case AES_CBC -> new AesCbcConfigDecryptor();
            case SM4_CBC, SM4_ECB -> new Sm4ConfigDecryptor();
            case RSA -> new RsaConfigDecryptor();
            case SM2 -> new Sm2ConfigDecryptor();
            default -> throw new UnsupportedOperationException("不支持解密的算法: " + algorithm);
        };
    }
}
