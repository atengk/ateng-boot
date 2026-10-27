package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.OrderSignDTO;
import io.github.atengk.boot.web.sign.annotation.ApiSign;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API 双向数据加签验签与防重放场景控制器
 * 演示通过 @ApiSign 声明式开启请求验签与响应加签，支持 Query/Body 防篡改与 Nonce 防重放
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping({"/api/sample/sign", "/api/sample/security/sign"})
public class WebSecuritySignSampleController {

    /**
     * 国密 SM2/SM3 双向加验签：接收 JSON Body，验签入站请求并对返回的 Result 加签回写响应头
     *
     * @param dto 订单载荷
     * @return 签名确认标识
     */
    @PostMapping("/sm2")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
    public Result<String> createOrderSm2(@Valid @RequestBody OrderSignDTO dto) {
        return Result.success("SM2_SIGNED_" + dto.getOrderNo());
    }

    /**
     * 双向加验签：接收 JSON Body，验签入站请求并对返回的 Result 加签回写响应头
     *
     * @param dto 订单载荷
     * @return 签名确认标识
     */
    @PostMapping("/orders")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
    public Result<String> createOrder(@Valid @RequestBody OrderSignDTO dto) {
        return Result.success("ORD_SIGNED_" + dto.getOrderNo());
    }

    /**
     * 双向加验签：接收 Query 请求参数，验签并加签
     *
     * @param code 查询编码
     * @return 业务数据
     */
    @GetMapping("/query")
    @ApiSign(verifyRequest = true, signResponse = true, expireSeconds = 10)
    public Result<String> queryData(@RequestParam("code") String code) {
        return Result.success("DATA_" + code);
    }

    /**
     * 单向验签：仅校验入站数据签名（如三方 Webhook 通知回调场景）
     *
     * @param dto 订单载荷
     * @return 通知处理标识
     */
    @PostMapping("/inbound")
    @ApiSign(verifyRequest = true, signResponse = false, expireSeconds = 10)
    public Result<String> inboundOnly(@RequestBody OrderSignDTO dto) {
        return Result.success("NOTIFIED_" + dto.getOrderNo());
    }

    /**
     * 单向加签：入站免签，服务端出站数据强制加签（如报表或敏感数据查询）
     *
     * @return 敏感数据载荷
     */
    @GetMapping("/outbound")
    @ApiSign(verifyRequest = false, signResponse = true, expireSeconds = 10)
    public Result<String> outboundOnly() {
        return Result.success("SECRET_REPORT_DATA");
    }
}
