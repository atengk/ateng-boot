package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.encrypt.dto.PropertyDecryptRequest;
import io.github.atengk.boot.sample.encrypt.service.ConfigDecryptionScenarioService;
import io.github.atengk.boot.sample.encrypt.vo.SampleSecretVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 环境机密配置透明解密呈现控制器
 * <p>
 * 提供通过 @ConfigurationProperties 强类型属性对象与 @Value 注解透明注入真实明文的自验端点，
 * 以及按需查询单个环境属性解析结果。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/encrypt/decrypt")
@RequiredArgsConstructor
public class EncryptDecryptionController {

    private final ConfigDecryptionScenarioService decryptionScenarioService;
    private final Environment environment;

    /**
     * 查看当前环境已透明解密绑定的业务机密属性（真实明文）
     *
     * @return 业务机密解密明文展示对象
     */
    @GetMapping("/secrets")
    public Result<SampleSecretVO> getDecryptedSecrets() {
        log.info("[EncryptDecryptionController] 读取业务机密解密属性");
        SampleSecretVO vo = SampleSecretVO.builder()
                .databasePassword(decryptionScenarioService.getDatabasePasswordFromProperties())
                .redisPassword(decryptionScenarioService.getRedisPasswordFromProperties())
                .thirdPartyToken(decryptionScenarioService.getThirdPartyTokenFromProperties())
                .activeAlgorithm("AES-GCM")
                .decryptionStatus(decryptionScenarioService.verifyDecryptionConsistency() ? "VERIFIED_CONSISTENT" : "MISMATCH")
                .build();
        return Result.success(vo);
    }

    /**
     * 单值环境属性动态解密查询
     *
     * @param request 属性查询请求
     * @return 解析或解密后的属性值明文
     */
    @GetMapping("/property")
    public Result<String> getEnvironmentProperty(@Valid @ModelAttribute PropertyDecryptRequest request) {
        log.info("[EncryptDecryptionController] 查询环境属性: {}", request.getPropertyName());
        String val = environment.getProperty(request.getPropertyName());
        return Result.success(val != null ? val : "");
    }
}
