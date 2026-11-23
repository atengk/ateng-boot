package io.github.atengk.boot.sample.ftp.service;

import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务场景五：物理连通性自验探针服务组件
 * <p>
 * 演示并实测面向运维巡检、服务保活与管理后台节点验证的无副作用物理连通性探测：
 * 依托底层受管连接池借出连接并向控制通道发送 RFC 959 {@code NOOP} 心跳指令，
 * 毫秒级快速返回，全程零物理文件读写副作用。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConnectivityProbeScenarioService {

    private final FtpTemplate ftpTemplate;

    /**
     * 探测当前/默认 FTP 服务节点的物理连通性
     *
     * @return 连通成功返回 true，不可用返回 false
     */
    public boolean checkDefaultServerHealth() {
        boolean healthy = ftpTemplate.testConnection();
        log.info("探测默认 FTP 节点连通性: {}", healthy ? "HEALTHY (连通正常)" : "DOWN (连接不可用)");
        return healthy;
    }

    /**
     * 探测指定具名 FTP 服务节点的物理连通性
     *
     * @param serverName 目标服务节点标识（如 "backup"）
     * @return 连通成功返回 true，不可用返回 false
     */
    public boolean checkServerHealth(String serverName) {
        boolean healthy = ftpTemplate.testConnection(serverName);
        log.info("探测具名 FTP 节点 [{}] 连通性: {}", serverName, healthy ? "HEALTHY (连通正常)" : "DOWN (连接不可用)");
        return healthy;
    }

    /**
     * 强校验指定具名 FTP 服务节点的物理连通性（失败时抛出语义化异常）
     *
     * @param serverName 目标服务节点标识
     * @throws FtpException 当连接不可用或心跳失败时抛出异常
     */
    public void assertServerHealthy(String serverName) {
        log.info("断言 FTP 服务节点 [{}] 物理健康连通性...", serverName);
        ftpTemplate.testConnectionOrThrow(serverName);
    }
}
