package io.github.atengk.boot.sample.core.service;

import io.github.atengk.boot.core.annotation.SlowAlert;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * 系统基线防卫、慢调用告警与受管临时目录场景服务
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
public class CoreSystemScenarioService {

    private static final Logger log = LoggerFactory.getLogger(CoreSystemScenarioService.class);

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
     * 获取系统运行基线快照信息
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
}
