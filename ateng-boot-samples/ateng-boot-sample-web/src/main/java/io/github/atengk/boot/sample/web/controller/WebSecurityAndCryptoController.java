package io.github.atengk.boot.sample.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.DigestUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.sample.web.crypto.SampleCryptoKeyConfiguration;
import io.github.atengk.boot.sample.web.dto.CryptoPrepareEnvelopeDTO;
import io.github.atengk.boot.sample.web.dto.CryptoTransferDTO;
import io.github.atengk.boot.sample.web.vo.CryptoPreparedEnvelopeVO;
import io.github.atengk.boot.sample.web.vo.CryptoReceiptVO;
import io.github.atengk.boot.web.crypto.annotation.ApiDecrypt;
import io.github.atengk.boot.web.crypto.annotation.ApiEncrypt;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.enums.CryptoResponseKeyMode;
import io.github.atengk.boot.web.crypto.filter.ApiCryptoFilter;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.model.ApiEncryptedPayload;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import io.github.atengk.boot.web.sign.util.SignCanonicalUtil;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 传输层混合加解密与 {@code @ApiSign} Encrypt-then-Sign 协同体验控制器
 *
 * @author Ateng
 * @since 2026-11-27
 */
@RestController
@RequestMapping("/sample/web/crypto")
public class WebSecurityAndCryptoController {

    private final WebProperties webProperties;
    private final List<ApiCipher> ciphers;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SampleCryptoKeyConfiguration> sampleCryptoKeyProvider;
    private final ObjectProvider<ApiSigner> apiSignerProvider;
    private final ObjectProvider<SignKeyProvider> signKeyProvider;

    public WebSecurityAndCryptoController(WebProperties webProperties,
                                          List<ApiCipher> ciphers,
                                          ObjectMapper objectMapper,
                                          ObjectProvider<SampleCryptoKeyConfiguration> sampleCryptoKeyProvider,
                                          ObjectProvider<ApiSigner> apiSignerProvider,
                                          ObjectProvider<SignKeyProvider> signKeyProvider) {
        this.webProperties = webProperties;
        this.ciphers = ciphers != null ? ciphers : Collections.emptyList();
        this.objectMapper = objectMapper;
        this.sampleCryptoKeyProvider = sampleCryptoKeyProvider;
        this.apiSignerProvider = apiSignerProvider;
        this.signKeyProvider = signKeyProvider;
    }

    /**
     * 1. 加密信封测试辅助端：根据客户端明文 JSON 快速生成合法加密请求头、密文信封及可选 Encrypt-then-Sign 签名头
     *
     * @param dto 辅助打包请求参数
     * @return 密文信封与完整请求头映射
     */
    @PostMapping("/prepare-envelope")
    public Result<CryptoPreparedEnvelopeVO> prepareEnvelope(@RequestBody @Valid CryptoPrepareEnvelopeDTO dto) throws Exception {
        WebProperties.CryptoProperties cryptoProps = webProperties.getCrypto();
        CryptoAlgorithm alg = (dto.getAlgorithm() != null && dto.getAlgorithm() != CryptoAlgorithm.DEFAULT)
                ? dto.getAlgorithm()
                : (cryptoProps.getAlgorithm() != CryptoAlgorithm.DEFAULT ? cryptoProps.getAlgorithm() : CryptoAlgorithm.RSA_AES_GCM);
        String appId = StringUtils.hasText(cryptoProps.getAppId()) ? cryptoProps.getAppId() : "default";
        String keyId = StringUtils.hasText(dto.getKeyId()) ? dto.getKeyId().trim() : cryptoProps.getActiveKeyId();

        SampleCryptoKeyConfiguration sampleKeyConfig = sampleCryptoKeyProvider.getIfAvailable();
        String serverPubKey = sampleKeyConfig != null
                ? sampleKeyConfig.resolveServerPublicKey(cryptoProps, alg)
                : (alg == CryptoAlgorithm.SM2_SM4_GCM ? cryptoProps.getSm2ServerPublicKey() : cryptoProps.getRsaServerPublicKey());
        if (!StringUtils.hasText(serverPubKey)) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未配置或生成演示用服务端加密公钥");
        }

        byte[] aad = cryptoProps.isBindAad() ? ApiCryptoContext.buildStandardAad(appId, keyId, alg) : null;
        ApiCipher cipher = ciphers.stream().filter(c -> c.supports(alg)).findFirst()
                .orElseThrow(() -> new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "未找到算法引擎: " + alg));
        ApiCipherResult encrypted = cipher.encrypt(
                dto.getPlainJson().getBytes(StandardCharsets.UTF_8),
                new ApiCryptoContext(alg, appId, keyId, serverPubKey, aad)
        );
        byte[] envelopeBytes = objectMapper.writeValueAsBytes(new ApiEncryptedPayload(encrypted.cipherText()));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ApiCryptoFilter.HEADER_APP_ID, appId);
        headers.put(ApiCryptoFilter.HEADER_KEY_ID, keyId);
        headers.put(ApiCryptoFilter.HEADER_ENCRYPT_ALG, alg.name());
        headers.put(ApiCryptoFilter.HEADER_ENCRYPT_KEY, encrypted.encryptedKey());
        headers.put(ApiCryptoFilter.HEADER_ENCRYPT_IV, encrypted.iv());

        if (dto.isWithSign()) {
            ApiSigner signer = apiSignerProvider.getIfAvailable();
            SignKeyProvider skp = signKeyProvider.getIfAvailable();
            if (signer != null && skp != null && StringUtils.hasText(dto.getRequestUri())) {
                String ts = String.valueOf(System.currentTimeMillis());
                String nonce = IdUtil.fastSimpleUUID();
                SignAlgorithm signAlg = webProperties.getSign().getAlgorithm();
                String bodySha256 = DigestUtil.sha256Hex(envelopeBytes);
                String canonical = SignCanonicalUtil.buildRequestCanonicalString(
                        "POST", dto.getRequestUri(), ts, nonce, Collections.emptyMap(), bodySha256);
                String sig = signer.sign(canonical, skp.getSignKey(appId, signAlg));
                headers.put(ApiSignAspect.HEADER_TIMESTAMP, ts);
                headers.put(ApiSignAspect.HEADER_NONCE, nonce);
                headers.put(ApiSignAspect.HEADER_SIGNATURE, sig);
            }
        }

        return Result.success(CryptoPreparedEnvelopeVO.builder()
                .encryptedBodyJson(new String(envelopeBytes, StandardCharsets.UTF_8))
                .headers(headers)
                .build());
    }

    /**
     * 2. 独立模式：RSA-OAEP-SHA256 + AES-256-GCM 双向支付结算加密接口（透明解密 + @Valid + XSS 清洗 + 响应加密）
     */
    @ApiDecrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
    @ApiEncrypt(algorithm = CryptoAlgorithm.RSA_AES_GCM)
    @PostMapping("/rsa/payment")
    public Result<CryptoReceiptVO> rsaPayment(@RequestBody @Valid CryptoTransferDTO dto) {
        return Result.success(CryptoReceiptVO.builder()
                .orderNo(dto.getOrderNo())
                .accountNo(dto.getAccountNo())
                .amount(dto.getAmount())
                .remark(dto.getRemark())
                .securityChannel("RSA_AES_GCM")
                .build());
    }

    /**
     * 3. 独立模式：国密 SM2 + SM4-GCM 双向涉密归档接口
     */
    @ApiDecrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM)
    @ApiEncrypt(algorithm = CryptoAlgorithm.SM2_SM4_GCM)
    @PostMapping("/sm2/archive")
    public Result<CryptoReceiptVO> sm2Archive(@RequestBody @Valid CryptoTransferDTO dto) {
        return Result.success(CryptoReceiptVO.builder()
                .orderNo(dto.getOrderNo())
                .accountNo(dto.getAccountNo())
                .amount(dto.getAmount())
                .remark(dto.getRemark())
                .securityChannel("SM2_SM4_GCM")
                .build());
    }

    /**
     * 4. 仅响应加密 GET 报表查询接口
     */
    @ApiEncrypt
    @GetMapping("/report")
    public Result<String> encryptedReport(@RequestParam("bizNo") String bizNo) {
        return Result.success("CONFIDENTIAL_REPORT:" + bizNo);
    }

    /**
     * 5. 混合模式（Encrypt-then-Sign）：@ApiSign 密文前置验签防重放 + @ApiDecrypt 解密 + @Idempotent 幂等 + @ApiEncrypt + 出站密文回签
     */
    @ApiSign
    @ApiDecrypt
    @ApiEncrypt
    @Idempotent(key = "#dto.orderNo", timeout = 30)
    @PostMapping("/mixed/transfer")
    public Result<CryptoReceiptVO> mixedTransfer(@RequestBody @Valid CryptoTransferDTO dto) {
        return Result.success(CryptoReceiptVO.builder()
                .orderNo(dto.getOrderNo())
                .accountNo(dto.getAccountNo())
                .amount(dto.getAmount())
                .remark(dto.getRemark())
                .securityChannel("ENCRYPT_THEN_SIGN")
                .build());
    }

    /**
     * 6. 灰度兼容模式：允许未加密明文与密文混合过渡调用
     */
    @ApiDecrypt(required = false)
    @PostMapping("/gray/notice")
    public Result<String> grayNotice(@RequestBody @Valid CryptoTransferDTO dto) {
        return Result.success("GRAY_ACCEPTED:" + dto.getOrderNo());
    }

    /**
     * 7. 纯 Web SPA 无静态私钥双向加解密（复用请求一次性 AES 会话密钥 + 全新随机 IV 回包）
     */
    @ApiDecrypt
    @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
    @PostMapping("/spa/checkout")
    public Result<CryptoReceiptVO> spaCheckout(@RequestBody @Valid CryptoTransferDTO dto) {
        return Result.success(CryptoReceiptVO.builder()
                .orderNo(dto.getOrderNo())
                .accountNo(dto.getAccountNo())
                .amount(dto.getAmount())
                .remark(dto.getRemark())
                .securityChannel("SPA_REQUEST_SESSION_KEY")
                .build());
    }

    /**
     * 8. 纯 Web SPA 无体 GET 密钥握手响应加密（前端仅携带 X-Encrypt-Key，服务端复用该会话密钥加密响应）
     */
    @ApiEncrypt(keyMode = CryptoResponseKeyMode.REQUEST_SESSION_KEY)
    @GetMapping("/spa/profile")
    public Result<Map<String, Object>> spaProfile(@RequestParam("memberId") String memberId) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("memberId", memberId);
        profile.put("tier", "VIP_BLACK");
        profile.put("securityChannel", "SPA_REQUEST_SESSION_KEY_HANDSHAKE");
        return Result.success(profile);
    }
}
