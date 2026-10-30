package io.github.atengk.boot.sample.ftp.service;

import io.github.atengk.boot.ftp.model.BatchTransferResult;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.function.Predicate;

/**
 * 业务场景二：金融日结对账全目录递归上传与下载批处理组件
 * <p>
 * 演示并实测生产级金融与大数据批量对账单场景：
 * 1. 本地生成的多层级对账目录镜像递归上传至远程结算中心；
 * 2. 远端对账结算目录完整递归下载至本地审计工作区；
 * 3. 宿主隐性系统文件 (desktop.ini, .DS_Store, Thumbs.db, .git) 自动剔除；
 * 4. 批处理 Fail-Safe 容错模型与聚合回执断言（单文件异常不熔断批次，精准回执失败明细）；
 * 5. 快速失败 Fail-Fast 熔断机制。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementBatchSyncScenarioService {

    private final FtpTemplate ftpTemplate;

    /**
     * 批量递归上传结算对账目录（默认 Fail-Safe 容错模式）
     *
     * @param localSettlementDir 本地结算对账目录
     * @param remoteSettlementDir 远端结算存储根目录
     * @return 批量传输强类型聚合回执
     */
    public BatchTransferResult syncSettlementToRemote(File localSettlementDir, String remoteSettlementDir) {
        log.info("开始同步本地结算目录至远端: {} -> {}", localSettlementDir.getAbsolutePath(), remoteSettlementDir);
        return ftpTemplate.uploadDirectory(localSettlementDir, remoteSettlementDir);
    }

    /**
     * 批量递归上传结算对账目录（支持自定义扩展名过滤与 Fail-Fast / Fail-Safe 双模机制）
     *
     * @param localSettlementDir 本地结算对账目录
     * @param remoteSettlementDir 远端结算存储根目录
     * @param filter             文件断言过滤器
     * @param failFast           是否遇错立即熔断
     * @return 批量传输强类型聚合回执
     */
    public BatchTransferResult syncSettlementToRemote(File localSettlementDir, String remoteSettlementDir,
                                                     Predicate<File> filter, boolean failFast) {
        log.info("开始定制同步本地结算目录至远端: {} -> {}, failFast={}", localSettlementDir.getAbsolutePath(), remoteSettlementDir, failFast);
        return ftpTemplate.uploadDirectory(localSettlementDir, remoteSettlementDir, filter, failFast);
    }

    /**
     * 批量递归下载远端结算对账目录到本地（默认 Fail-Safe 容错模式）
     *
     * @param remoteSettlementDir 远端结算存储根目录
     * @param localSettlementDir 本地存储目标目录
     * @return 批量传输强类型聚合回执
     */
    public BatchTransferResult syncSettlementFromRemote(String remoteSettlementDir, File localSettlementDir) {
        log.info("开始从远端同步结算目录至本地: {} -> {}", remoteSettlementDir, localSettlementDir.getAbsolutePath());
        return ftpTemplate.downloadDirectory(remoteSettlementDir, localSettlementDir);
    }

    /**
     * 批量递归下载远端结算对账目录到本地（支持自定义断言过滤与 Fail-Fast / Fail-Safe 双模机制）
     *
     * @param remoteSettlementDir 远端结算存储根目录
     * @param localSettlementDir 本地存储目标目录
     * @param filter             远端文件元数据过滤器
     * @param failFast           是否遇错立即熔断
     * @return 批量传输强类型聚合回执
     */
    public BatchTransferResult syncSettlementFromRemote(String remoteSettlementDir, File localSettlementDir,
                                                       Predicate<FtpFileInfo> filter, boolean failFast) {
        log.info("开始定制从远端同步结算目录至本地: {} -> {}, failFast={}", remoteSettlementDir, localSettlementDir.getAbsolutePath(), failFast);
        return ftpTemplate.downloadDirectory(remoteSettlementDir, localSettlementDir, filter, failFast);
    }
}
