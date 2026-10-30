package io.github.atengk.boot.sample.ftp.service;

import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * 业务场景四：多节点动态切源与隔离路由服务组件
 * <p>
 * 演示并实测多数据中心 / 主备 FTP 节点的动态切源路由治理：
 * 依托 {@link FtpContextHolder#runWith(String, Supplier)} 实现线程栈级安全隔离切源，
 * 离开执行作用域自动精准恢复上下文现场，杜绝跨线程连接脏读与串节点风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DynamicRoutingScenarioService {

    private final FtpTemplate ftpTemplate;

    /**
     * 在指定的具名 FTP 服务节点上闭包执行业务操作（栈式切源，自动清理现场）
     *
     * @param serverName 目标 FTP 服务节点标识（如 "backup"）
     * @param action     需要执行的业务操作
     * @param <T>        操作返回值类型
     * @return 执行结果
     */
    public <T> T executeOnServer(String serverName, Supplier<T> action) {
        log.info("动态切源路由至节点 [{}] 执行操作", serverName);
        return FtpContextHolder.runWith(serverName, action);
    }

    /**
     * 快捷将内容写入备用节点 (backup)
     *
     * @param remotePath 远程文件路径
     * @param content    文件内容
     * @return 写入成功返回 true
     */
    public boolean writeToBackupServer(String remotePath, String content) {
        return executeOnServer("backup", () -> ftpTemplate.writeString(remotePath, content));
    }

    /**
     * 快捷从备用节点 (backup) 读取内容
     *
     * @param remotePath 远程文件路径
     * @return 读取到的文本内容
     */
    public String readFromBackupServer(String remotePath) {
        return executeOnServer("backup", () -> ftpTemplate.readString(remotePath));
    }

    /**
     * 判断备用节点 (backup) 上指定文件是否存在
     *
     * @param remotePath 远程文件路径
     * @return 存在返回 true
     */
    public boolean existsOnBackupServer(String remotePath) {
        return executeOnServer("backup", () -> ftpTemplate.exists(remotePath));
    }
}
