package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.annotation.SlowAlert;
import io.github.atengk.boot.core.audit.OperateLogEvent;
import io.github.atengk.boot.core.audit.OperateLogModel;
import io.github.atengk.boot.core.audit.SqlLogModel;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.enums.SqlTypeEnum;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.CidrMatcher;
import io.github.atengk.boot.core.util.ExceptionUtil;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.IdUtil;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.core.util.NetUtil;
import io.github.atengk.boot.core.util.SpringUtil;
import io.github.atengk.boot.core.util.StopWatch;
import io.github.atengk.boot.sample.core.dto.AuditAndDiagnoseRequest;
import io.github.atengk.boot.sample.core.dto.SlowAlertAndFileRequest;
import io.github.atengk.boot.sample.core.listener.SampleAuditEventListener;
import io.github.atengk.boot.sample.core.vo.AuditAndDiagnoseVO;
import io.github.atengk.boot.sample.core.vo.SlowAlertAndFileVO;
import io.github.atengk.boot.sample.core.vo.SystemBaselineAndNetVO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统基线防卫、网络探测、慢调用告警与审计诊断场景服务
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
public class CoreSystemScenarioService {

    private static final Logger log = LoggerFactory.getLogger(CoreSystemScenarioService.class);

    private final SampleAuditEventListener auditEventListener;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 演示慢方法执行并触发 @SlowAlert 告警
     *
     * @param taskId      任务标识
     * @param secretToken 敏感凭据（测试凭据脱敏）
     * @return 执行耗时
     */
    @SlowAlert(threshold = 30, includeArgs = true)
    public long processSlowComputation(String taskId, String secretToken) {
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(45);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        long cost = System.currentTimeMillis() - start;
        log.info("慢计算任务执行完成 [taskId={}, cost={}ms]", taskId, cost);
        return cost;
    }

    /**
     * 在当前应用专属受管临时目录下创建业务临时文件
     *
     * @param filename 文件名
     * @param content  文本内容
     * @return 实际生成的文件路径 Path
     * @throws IOException IO 异常
     */
    public Path createSampleTempFile(String filename, String content) throws IOException {
        Path appTempDir = FileUtil.getAppTempDirectory("sample-core");
        Path targetFile = appTempDir.resolve(filename);
        Files.writeString(targetFile, content);
        return targetFile;
    }

    /**
     * 获取系统运行基线快照信息（存量兼容）
     *
     * @return 系统基线信息 Map
     */
    public Map<String, Object> getBaselineSnapshot() {
        Map<String, Object> map = new HashMap<>();
        map.put("timezone", ZoneId.systemDefault().getId());
        map.put("fileEncoding", System.getProperty("file.encoding"));
        map.put("headless", System.getProperty("java.awt.headless"));
        map.put("connectTimeout", System.getProperty("sun.net.client.defaultConnectTimeout"));
        map.put("readTimeout", System.getProperty("sun.net.client.defaultReadTimeout"));
        map.put("physicalIpv4", NetUtil.getLocalIpv4());
        return map;
    }

    /**
     * 系统环境基线防卫与网络探测全景
     *
     * @param clientIp    测试客户端 IP（支持附带反代端口）
     * @param cidrPattern 测试 CIDR 规则
     * @return 强类型基线与网络匹配视图
     */
    public SystemBaselineAndNetVO getBaselineAndNet(String clientIp, String cidrPattern) {
        // 1. 提取运行基线
        String timezone = ZoneId.systemDefault().getId();
        String fileEncoding = System.getProperty("file.encoding");
        Boolean headless = Boolean.parseBoolean(System.getProperty("java.awt.headless"));
        String connectTimeout = System.getProperty("sun.net.client.defaultConnectTimeout");
        String readTimeout = System.getProperty("sun.net.client.defaultReadTimeout");

        // 2. 提取物理网卡与端口探测
        String physicalIpv4 = NetUtil.getLocalIpv4();
        Integer usablePort = NetUtil.findAvailablePort(28000, 29000);

        // 3. CIDR 匹配与端口剥离
        String cleanedClientIp = CidrMatcher.stripPort(clientIp);
        boolean cidrMatched = CidrMatcher.matches(clientIp, cidrPattern);

        // 4. IPv6 兼容性测试
        String rawIpv6 = "[fe80::1]:8080";
        String ipv6Cleaned = CidrMatcher.stripPort(rawIpv6);
        boolean ipv6CidrMatched = CidrMatcher.matches("fe80::1", "fe80::/10");

        // 5. Spring 容器状态探测
        Integer serverPort = SpringUtil.getServerPort();
        if (serverPort == null) {
            serverPort = SpringUtil.getProperty("server.port", Integer.class, 8080);
        }
        String activeProfile = SpringUtil.getActiveProfile();

        return SystemBaselineAndNetVO.builder()
                .timezone(timezone)
                .fileEncoding(fileEncoding)
                .headless(headless)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .physicalIpv4(physicalIpv4)
                .usablePort(usablePort)
                .rawClientIp(clientIp)
                .cleanedClientIp(cleanedClientIp)
                .cidrMatched(cidrMatched)
                .ipv6Cleaned(ipv6Cleaned)
                .ipv6CidrMatched(ipv6CidrMatched)
                .serverPort(serverPort)
                .activeProfile(activeProfile)
                .build();
    }

    /**
     * 执行慢方法告警、安全文件名清洗与受管临时目录治理（保证零磁盘残留）
     *
     * @param request 请求参数
     * @return 治理响应视图
     */
    public SlowAlertAndFileVO executeSlowAlertAndFile(SlowAlertAndFileRequest request) {
        StopWatch sw = new StopWatch("system-slow-and-file-" + request.getTaskId());

        // 1. 慢方法触发与耗时度量
        sw.start("1.慢调用方法执行与脱敏告警");
        long slowMethodCostMillis = processSlowComputation(request.getTaskId(), request.getSecretToken());
        sw.stop();

        // 2. 安全文件名清洗（防路径遍历）与受管目录定位
        sw.start("2.安全文件名清洗与受管目录定位");
        String originalFilename = request.getFilename();
        String cleanedFilename = FileUtil.cleanFilename(originalFilename);
        if (cleanedFilename.isBlank()) {
            cleanedFilename = "default-temp.log";
        }
        Path appTempDir = FileUtil.getAppTempDirectory("sample-core");
        Path targetFile = appTempDir.resolve(cleanedFilename);
        sw.stop();

        // 3. 受管临时文件写入与有界流式读取
        sw.start("3.受管临时文件写入与有界安全流式读取");
        String readContent;
        int readByteCount;
        boolean fileExistedBeforeClean;
        try {
            Files.writeString(targetFile, request.getContent(), StandardCharsets.UTF_8);
            fileExistedBeforeClean = Files.exists(targetFile);
            int maxBytes = (request.getMaxReadBytes() != null && request.getMaxReadBytes() > 0)
                    ? request.getMaxReadBytes() : 1024;
            try (InputStream in = Files.newInputStream(targetFile)) {
                byte[] bytesRead = FileUtil.readNBytesSafe(in, maxBytes);
                readByteCount = bytesRead.length;
                readContent = new String(bytesRead, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new BusinessException("受管临时文件读写失败: " + e.getMessage(), e);
        }
        sw.stop();

        // 4. 确定性清理临时目录并验证销毁
        sw.start("4.受管临时目录确定性清理");
        int cleanedFileCount = FileUtil.cleanDirectorySafely(appTempDir);
        boolean fileDestroyedAfterClean = !Files.exists(targetFile);
        sw.stop();

        return SlowAlertAndFileVO.builder()
                .taskId(request.getTaskId())
                .slowMethodCostMillis(slowMethodCostMillis)
                .originalFilename(originalFilename)
                .cleanedFilename(cleanedFilename)
                .readContent(readContent)
                .readByteCount(readByteCount)
                .cleanedFileCount(cleanedFileCount)
                .fileExistedBeforeClean(fileExistedBeforeClean)
                .fileDestroyedAfterClean(fileDestroyedAfterClean)
                .totalCostMillis(sw.getTotalTimeMillis())
                .stopWatchReport(sw.prettyPrint())
                .build();
    }

    /**
     * 结构化操作与 SQL 审计契约、领域事件发布及深度异常因果分析
     *
     * @param request 请求参数
     * @return 审计与诊断响应视图
     */
    public AuditAndDiagnoseVO executeAuditAndDiagnose(AuditAndDiagnoseRequest request) {
        // 1. 组装 18 项全要素操作审计模型
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = IdUtil.fastSimpleUUID();
        }
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "tenant-core-default";
        }
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank()) {
            userId = "user-admin-1001";
        }

        OperateLogModel operateLog = OperateLogModel.builder()
                .traceId(traceId)
                .tenantId(tenantId)
                .userId(userId)
                .userName("系统管理员")
                .module(request.getModule())
                .operateType(request.getOperateType())
                .description(request.getDescription())
                .requestMethod("POST")
                .requestUrl("/sample/core/system/audit-and-diagnose")
                .clientIp(NetUtil.getLocalIpv4())
                .operateLocation("内部数据中心")
                .requestParams(JsonUtil.toJsonString(request))
                .responseResult("{\"code\":200,\"msg\":\"操作成功\"}")
                .status(0)
                .costTime(15L)
                .operateTime(LocalDateTime.now())
                .build();
        operateLog.addExtra("clientAgent", "CoreSystemAuditClient/1.0")
                .addExtra("env", SpringUtil.getActiveProfile());

        // 2. 通过 Spring 事件总线解耦发布 OperateLogEvent
        eventPublisher.publishEvent(new OperateLogEvent(this, operateLog));

        // 3. 解析推导 SQL 类型并组装 SqlLogModel 审计模型
        String sql = request.getSql().trim();
        String firstWord = sql.split("\\s+")[0];
        SqlTypeEnum sqlType = SqlTypeEnum.of(firstWord);

        SqlLogModel sqlLog = SqlLogModel.builder()
                .traceId(traceId)
                .tenantId(tenantId)
                .userId(userId)
                .userName("系统管理员")
                .datasource("master")
                .statementId("io.github.atengk.boot.sample.core.mapper.SystemAuditMapper.execute")
                .sql(request.getSql())
                .sqlType(sqlType)
                .costTime(8L)
                .affectedRows(1L)
                .status(0)
                .callLocation("CoreSystemScenarioService.java:executeAuditAndDiagnose")
                .operateTime(LocalDateTime.now())
                .build();

        // 4. 模拟深度嵌套因果异常链并执行根因追溯与有界堆栈截断
        Throwable root = new IllegalStateException(request.getErrorMessage());
        Throwable middle = new RuntimeException("持久层中间驱动通信异常", root);
        Throwable top = new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR, "业务聚合操作失败: " + request.getErrorMessage(), middle);

        Throwable extractedRoot = ExceptionUtil.getRootCause(top);
        String rootMsg = ExceptionUtil.getMessage(extractedRoot);
        String truncatedStack = ExceptionUtil.stackTraceToString(top, 500);
        boolean isTruncated = truncatedStack.contains("... [truncated]");

        return AuditAndDiagnoseVO.builder()
                .traceId(traceId)
                .operateLog(operateLog)
                .eventConsumedCount(auditEventListener.getEventCount())
                .consumedEventModel(auditEventListener.getLastReceivedModel())
                .sqlLog(sqlLog)
                .sqlType(sqlType)
                .dml(sqlType.isDml())
                .query(sqlType.isQuery())
                .rootCauseClass(extractedRoot != null ? extractedRoot.getClass().getName() : "")
                .rootCauseMessage(rootMsg)
                .truncatedStackTrace(truncatedStack)
                .stackTruncated(isTruncated)
                .build();
    }
}
