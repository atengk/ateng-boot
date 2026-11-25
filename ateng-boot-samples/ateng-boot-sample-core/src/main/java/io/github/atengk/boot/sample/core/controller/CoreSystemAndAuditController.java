package io.github.atengk.boot.sample.core.controller;

import io.github.atengk.boot.core.audit.OperateTypeEnum;
import io.github.atengk.boot.core.model.OptionItem;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.model.UserAgentInfo;
import io.github.atengk.boot.core.util.EnumUtil;
import io.github.atengk.boot.core.util.UserAgentUtil;
import io.github.atengk.boot.sample.core.dto.AuditAndDiagnoseRequest;
import io.github.atengk.boot.sample.core.dto.SlowAlertAndFileRequest;
import io.github.atengk.boot.sample.core.dto.SlowAlertSampleRequest;
import io.github.atengk.boot.sample.core.service.CoreSystemScenarioService;
import io.github.atengk.boot.sample.core.vo.AuditAndDiagnoseVO;
import io.github.atengk.boot.sample.core.vo.SlowAlertAndFileVO;
import io.github.atengk.boot.sample.core.vo.SlowAlertSampleVO;
import io.github.atengk.boot.sample.core.vo.SystemBaselineAndNetVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统基线防卫、网络探测、慢方法治理与审计诊断业务领域控制器
 * 聚合系统运行环境基线快照、CIDR 网络匹配、慢调用告警、受管临时目录安全治理、操作/SQL 审计与异常回溯分析
 *
 * @author Ateng
 * @since 2026-11-07
 */
@Validated
@RestController
@RequestMapping("/sample/core/system")
@RequiredArgsConstructor
public class CoreSystemAndAuditController {

    private final CoreSystemScenarioService systemScenarioService;

    /**
     * 系统环境基线防卫与网络探测
     * 提取当前 JVM 时区、编码、Headless、网络超时基线、宿主机物理 IPv4 与 CIDR 子网白名单匹配
     *
     * @param clientIp    测试客户端 IP（支持附带反代端口，默认 192.168.1.100:8080）
     * @param cidrPattern 测试 CIDR 网段规则（默认 192.168.1.0/24）
     * @return 系统基线与网络匹配响应视图
     */
    @GetMapping("/baseline-and-net")
    public Result<SystemBaselineAndNetVO> getBaselineAndNet(
            @RequestParam(required = false, defaultValue = "192.168.1.100:8080") String clientIp,
            @RequestParam(required = false, defaultValue = "192.168.1.0/24") String cidrPattern) {
        return Result.success(systemScenarioService.getBaselineAndNet(clientIp, cidrPattern));
    }

    /**
     * 慢方法告警与受管安全文件治理
     * 执行慢计算触发 @SlowAlert 告警、秒表分段耗时测算、文件名路径穿越清洗与受管临时目录安全读写及确定性清理
     *
     * @param request 请求参数
     * @return 执行过程与治理结果视图
     */
    @PostMapping("/slow-alert-and-file")
    public Result<SlowAlertAndFileVO> executeSlowAlertAndFile(@RequestBody @Valid SlowAlertAndFileRequest request) {
        return Result.success(systemScenarioService.executeSlowAlertAndFile(request));
    }

    /**
     * 结构化操作/SQL 审计与异常诊断
     * 演示操作审计领域事件发布与异步监听消费、物理 SQL 审计模型构建与异常链根因深度回溯及有界截断
     *
     * @param request 请求参数
     * @return 审计模型与异常诊断视图
     */
    @PostMapping("/audit-and-diagnose")
    public Result<AuditAndDiagnoseVO> executeAuditAndDiagnose(@RequestBody @Valid AuditAndDiagnoseRequest request) {
        return Result.success(systemScenarioService.executeAuditAndDiagnose(request));
    }

    /**
     * 获取通用操作审计动作类型字典选项列表
     * 演示 OperateTypeEnum 实现 IBaseEnum<Integer> 后通过 EnumUtil.toOptions 输出标准 OptionItem 列表
     *
     * @return 操作动作类型选项列表（共 11 项：0=其他 至 10=清空）
     */
    @GetMapping("/audit-operate-types")
    public Result<List<OptionItem<Integer>>> listAuditOperateTypes() {
        return Result.success(EnumUtil.toOptions(OperateTypeEnum.class));
    }

    /**
     * 慢调用领域事件解耦与启动期慢 Bean 诊断闭环
     * 通过 Spring 代理触发 @SlowAlert 慢方法、验证 SlowAlertEvent 脱敏摘要及 traceId 消费，并输出启动期慢 Bean 诊断报告
     *
     * @param request 请求参数
     * @return 慢调用事件快照与启动期慢 Bean 诊断视图
     */
    @PostMapping("/slow-alert")
    public Result<SlowAlertSampleVO> executeSlowAlertAndDiagnostic(@RequestBody @Valid SlowAlertSampleRequest request) {
        return Result.success(systemScenarioService.executeSlowAlertAndDiagnostic(request));
    }

    /**
     * 轻量级零依赖 User-Agent 解析与防注入净化端点
     * 优先解析显式传入的 ua 查询参数，未传时自动读取请求头 User-Agent
     *
     * @param ua       可选待解析 User-Agent 字符串
     * @param headerUa HTTP 请求头 User-Agent
     * @return 解析后的不可变客户端元数据
     */
    @GetMapping("/user-agent")
    public Result<UserAgentInfo> parseUserAgent(
            @RequestParam(required = false) String ua,
            @RequestHeader(value = "User-Agent", required = false) String headerUa) {
        String targetUa = StringUtils.hasText(ua) ? ua : headerUa;
        return Result.success(UserAgentUtil.parse(targetUa));
    }
}
