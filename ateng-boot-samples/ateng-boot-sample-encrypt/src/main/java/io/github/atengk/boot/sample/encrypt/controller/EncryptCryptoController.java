package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.encrypt.dto.*;
import io.github.atengk.boot.sample.encrypt.service.ProgrammaticCryptoScenarioService;
import io.github.atengk.boot.sample.encrypt.vo.CryptoResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 编程式受管加解密与对称门面控制器
 * <p>
 * 提供使用 Spring 受管 EncryptTemplate 门面进行默认算法加密、密文解密、多算法/命名Key自定义加密、
 * 纯 Base64 裸密文生成及容错逃生舱解密体验。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/encrypt/crypto")
@RequiredArgsConstructor
public class EncryptCryptoController {

    private final ProgrammaticCryptoScenarioService cryptoScenarioService;

    /**
     * 1. 使用环境默认算法与主密钥加密明文，生成 ENC(...) 标记
     *
     * @param request 加密入参
     * @return 编程式加密结果展示对象
     */
    @PostMapping("/encrypt")
    public Result<CryptoResultVO> encryptDefault(@Valid @RequestBody CryptoEncryptRequest request) {
        log.info("[EncryptCryptoController] 执行默认算法加密");
        String token = cryptoScenarioService.encryptDefault(request.getPlaintext());
        CryptoResultVO vo = CryptoResultVO.builder()
                .plaintext(request.getPlaintext())
                .token(token)
                .algorithm("AES-GCM")
                .build();
        return Result.success(vo);
    }

    /**
     * 2. 自动解密密文字符串（支持标准 ENC(...)、复合串及纯 Base64 裸密文）
     *
     * @param request 解密入参
     * @return 解密还原后的真实明文字符串
     */
    @PostMapping("/decrypt")
    public Result<String> decrypt(@Valid @RequestBody CryptoDecryptRequest request) {
        log.info("[EncryptCryptoController] 执行编程式解密");
        String plain = cryptoScenarioService.decrypt(request.getCiphertext());
        return Result.success(plain);
    }

    /**
     * 3. 指定命名密钥与指定算法加密明文（如国密 SM4-CBC、SM2、RSA）
     *
     * @param request 自定义加密入参
     * @return 编程式加密结果展示对象
     */
    @PostMapping("/encrypt-custom")
    public Result<CryptoResultVO> encryptCustom(@Valid @RequestBody CryptoCustomRequest request) {
        log.info("[EncryptCryptoController] 指定 KeyId [{}] 与算法 [{}] 执行加密", request.getKeyId(), request.getAlgorithm());
        String token = cryptoScenarioService.encryptWithKeyAndAlgorithm(request.getPlaintext(), request.getKeyId(), request.getAlgorithm());
        CryptoResultVO vo = CryptoResultVO.builder()
                .plaintext(request.getPlaintext())
                .token(token)
                .keyId(request.getKeyId())
                .algorithm(request.getAlgorithm().name())
                .build();
        return Result.success(vo);
    }

    /**
     * 4. 纯 Base64 裸密文加密（不带 ENC 前后缀包装）
     *
     * @param request 裸密文加密入参
     * @return 纯 Base64 密文字符串
     */
    @PostMapping("/encrypt-raw")
    public Result<String> encryptRaw(@Valid @RequestBody CryptoRawRequest request) {
        log.info("[EncryptCryptoController] 生成纯 Base64 裸密文");
        String raw = cryptoScenarioService.encryptRaw(request.getPlaintext());
        return Result.success(raw);
    }

    /**
     * 5. 容错逃生舱安全解密：解密失败或密文无效时安全保底返回默认值
     *
     * @param request 逃生舱解密入参
     * @return 解密明文或保底默认值
     */
    @PostMapping("/decrypt-safe")
    public Result<String> decryptSafe(@Valid @RequestBody CryptoSafeDecryptRequest request) {
        log.info("[EncryptCryptoController] 执行容错逃生舱安全解密");
        String safeValue = cryptoScenarioService.decryptOrDefault(request.getCiphertext(), request.getDefaultValue());
        return Result.success(safeValue);
    }
}
