package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.core.dto.PaymentCryptoRequest;
import io.github.atengk.boot.sample.core.service.CoreSecurityScenarioService;
import io.github.atengk.boot.sample.core.vo.CustomerPrivacyVO;
import io.github.atengk.boot.sample.core.vo.IdGenerateVO;
import io.github.atengk.boot.sample.core.vo.PaymentCryptoVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Core 安全、发号与密码学套件控制器
 * 演示并提供分布式发号与短码、客户隐私数据动态脱敏、原生对称加解密与 HMAC 恒定时间防侧信道验签端点
 *
 * @author Ateng
 * @since 2026-11-07
 */
@RestController
@RequestMapping("/sample/core/security")
@RequiredArgsConstructor
@Validated
public class CoreSecurityController {

    private final CoreSecurityScenarioService securityScenarioService;

    /**
     * 1. 生成分布式业务唯一 ID 与 Base62 紧凑短链
     *
     * @return 包含雪花 ID、Base62 短码、反解元数据与 NanoId 的发号结果
     */
    @GetMapping("/ids/generate")
    public Result<IdGenerateVO> generateDistributedId() {
        IdGenerateVO vo = securityScenarioService.generateDistributedId();
        return Result.success(vo);
    }

    /**
     * 2. 查询客户隐私档案并执行出界动态脱敏
     * 演示在 Controller/HTTP 响应序列化边界自动掩码，且在业务层 JVM 内存保持 100% 原始真明文
     *
     * @return 客户档案视图模型 (在输出 JSON 时由 SensitiveJsonSerializer 自动掩码)
     */
    @GetMapping("/customer/masked")
    public Result<CustomerPrivacyVO> getCustomerMaskedProfile() {
        CustomerPrivacyVO vo = securityScenarioService.getCustomerPrivacyProfile();
        return Result.success(vo);
    }

    /**
     * 3. 支付敏感报文原生加解密、HMAC 恒定时间防侧信道验签与国密/摘要校验和
     *
     * @param request 支付加密与签名请求
     * @return 包含 AES-GCM/CBC 与国密 SM4/CBC 密文、解密验证、验签结果、SM3/SHA-256 摘要与校验和的响应
     */
    @PostMapping("/crypto/verify")
    public Result<PaymentCryptoVO> verifyPaymentCrypto(
            @RequestBody @Valid PaymentCryptoRequest request) {
        PaymentCryptoVO vo = securityScenarioService.processPaymentCryptoAndVerify(request);
        return Result.success(vo);
    }
}
