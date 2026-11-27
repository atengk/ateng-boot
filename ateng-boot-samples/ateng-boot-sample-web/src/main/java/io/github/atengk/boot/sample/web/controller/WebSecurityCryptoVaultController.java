package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.CryptoTransferDTO;
import io.github.atengk.boot.web.crypto.annotation.ApiDecrypt;
import io.github.atengk.boot.web.crypto.annotation.ApiEncrypt;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * 类级别统一加解密及豁免、文件流自动旁路体验控制器
 *
 * @author Ateng
 * @since 2026-11-27
 */
@ApiDecrypt
@ApiEncrypt
@RestController
@RequestMapping("/sample/web/crypto/vault")
public class WebSecurityCryptoVaultController {

    /**
     * 1. 类级 GET 方法自动跳过入站解密，出站强制加密响应
     */
    @GetMapping("/summary")
    public Result<String> summary() {
        return Result.success("VAULT_CONFIDENTIAL_SUMMARY");
    }

    /**
     * 2. 方法级 enabled=false 精准豁免类级统一加解密（健康检查或明文契约）
     */
    @ApiDecrypt(enabled = false)
    @ApiEncrypt(enabled = false)
    @PostMapping("/plain-ping")
    public Result<String> plainPing(@RequestBody CryptoTransferDTO dto) {
        return Result.success("PLAIN_PING:" + dto.getOrderNo());
    }

    /**
     * 3. Multipart 文件上传自动旁路入站解密，响应仍遵循类级加密或普通返回
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        return Result.success("UPLOADED:" + file.getOriginalFilename());
    }

    /**
     * 4. 二进制流下载自动触发响应包装器透传防爆旁路
     */
    @GetMapping(value = "/download", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] download() {
        return "RAW_BINARY_DATA".getBytes(StandardCharsets.UTF_8);
    }
}
