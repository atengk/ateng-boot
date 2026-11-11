package io.github.atengk.boot.sample.tcp.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.tcp.dto.ClusterDispatchRequest;
import io.github.atengk.boot.sample.tcp.dto.SessionBackpressureRequest;
import io.github.atengk.boot.sample.tcp.dto.SpiProbeRequest;
import io.github.atengk.boot.sample.tcp.dto.TrafficShapingAdjustRequest;
import io.github.atengk.boot.sample.tcp.service.TcpGovernanceConsoleService;
import io.github.atengk.boot.sample.tcp.vo.TcpDispatchResultVO;
import io.github.atengk.boot.sample.tcp.vo.TcpGovernanceOverviewVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSessionInfoVO;
import io.github.atengk.boot.sample.tcp.vo.TcpSpiProbeVO;
import io.github.atengk.boot.sample.tcp.vo.TcpTrafficShapingVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TCP 全链路治理、SPI 可观测性、背压流控与集群桥接控制器
 * 提供活跃 SPI 装配清单、动态背压策略切换、全局流量整形调优、集群级广播分发及 SPI 探针联调接口
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/tcp/governance")
@RequiredArgsConstructor
public class TcpGovernanceController {

    private final TcpGovernanceConsoleService governanceConsoleService;

    /**
     * 获取全量 SPI 扩展装配列表、网关流量整形指标、集群桥接模式与 Actuator 运维探针总览
     *
     * @return 治理态势总览视图
     */
    @GetMapping("/overview")
    public Result<TcpGovernanceOverviewVO> getOverview() {
        return Result.success(governanceConsoleService.getOverview());
    }

    /**
     * 动态调整指定设备活跃会话的出站背压流控策略与最大阻塞超时时间
     *
     * @param request 背压调节请求
     * @return 更新后的只读会话视图
     */
    @PostMapping("/backpressure")
    public Result<TcpSessionInfoVO> adjustBackpressure(@Valid @RequestBody SessionBackpressureRequest request) {
        log.info("[HTTP-GOVERNANCE] 调整会话背压策略: deviceId={}, policy={}, timeoutMs={}",
                request.getDeviceId(), request.getPolicy(), request.getTimeoutMillis());
        return Result.success(governanceConsoleService.adjustBackpressure(request));
    }

    /**
     * 通过集群桥接器（LocalTcpClusterBridge / RedisPubSubTcpClusterBridge）执行设备/分组/全网广播
     *
     * @param request 集群分发请求
     * @return 集群分发回执视图
     */
    @PostMapping("/cluster-broadcast")
    public Result<TcpDispatchResultVO> clusterDispatch(@Valid @RequestBody ClusterDispatchRequest request) {
        log.info("[HTTP-GOVERNANCE] 执行集群桥接分发: mode={}, target={}", request.getMode(), request.getTarget());
        return Result.success(governanceConsoleService.clusterDispatch(request));
    }

    /**
     * 实时修改服务端全局读写流量整形（GlobalTrafficShapingHandler）限速阈值
     *
     * @param request 限速调节请求
     * @return 调整后的实时带宽统计快照
     */
    @PostMapping("/traffic-shaping")
    public Result<TcpTrafficShapingVO> adjustTrafficShaping(@Valid @RequestBody TrafficShapingAdjustRequest request) {
        log.info("[HTTP-GOVERNANCE] 动态调整流量整形限速: server={}, writeLimit={}, readLimit={}",
                request.getServerName(), request.getWriteLimitBytesPerSec(), request.getReadLimitBytesPerSec());
        return Result.success(governanceConsoleService.adjustTrafficShaping(request));
    }

    /**
     * 触发实时 SPI 联动链路探针，验证关联提取器、报文过滤阻断与函数式消息处理器
     *
     * @param request 探针执行参数
     * @return 探针执行详情与最新 SPI 统计计数
     */
    @PostMapping("/spi-probe")
    public Result<TcpSpiProbeVO> probeSpi(@Valid @RequestBody SpiProbeRequest request) {
        log.info("[HTTP-GOVERNANCE] 执行 SPI 治理链路探针: mode={}, note={}", request.getMode(), request.getNote());
        return Result.success(governanceConsoleService.probeSpi(request));
    }
}
