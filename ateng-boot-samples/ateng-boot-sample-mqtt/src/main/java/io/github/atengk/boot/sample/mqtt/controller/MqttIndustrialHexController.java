package io.github.atengk.boot.sample.mqtt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.mqtt.dto.HexPublishRequest;
import io.github.atengk.boot.sample.mqtt.dto.HexPublishResponse;
import io.github.atengk.boot.sample.mqtt.service.IndustrialHexScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工业国标与十六进制报文体验端控制器
 * 提供电力 DL/T 645、车联网 GB/T 32960 等定长报文 HexPayload 双向转译体验
 *
 * @author Ateng
 * @since 2026-11-10
 */
@Slf4j
@RestController
@RequestMapping("/sample/mqtt/hex")
@RequiredArgsConstructor
public class MqttIndustrialHexController {

    private final IndustrialHexScenarioService hexScenarioService;

    /**
     * 下发工业国标十六进制报文
     *
     * @param request 十六进制报文请求
     * @return 转译回执结果
     */
    @PostMapping("/publish")
    public Result<HexPublishResponse> publishHex(@Valid @RequestBody HexPublishRequest request) {
        log.info("[HTTP-HEX] 接收十六进制报文下发请求: deviceId={}, hexData={}", request.getDeviceId(), request.getHexData());
        HexPublishResponse response = hexScenarioService.publishHexAndResolve(request.getDeviceId(), request.getHexData());
        return Result.success(response);
    }
}
