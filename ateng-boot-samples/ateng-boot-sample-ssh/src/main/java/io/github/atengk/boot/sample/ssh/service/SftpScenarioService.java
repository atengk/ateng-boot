package io.github.atengk.boot.sample.ssh.service;

import io.github.atengk.boot.ssh.listener.SftpProgressListener;
import io.github.atengk.boot.ssh.listener.SftpProgressThrottler;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.template.SftpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * SFTP 工业级文件传输与生命周期管理场景服务
 * <p>
 * 演示非 Web 纯底座高阶特性：<br>
 * 1. 单文件流式原子上传与下载；<br>
 * 2. 尾部流式追加写 (append)；<br>
 * 3. 文件/目录原子改名与跨目录移动 (rename/move)；<br>
 * 4. 纯文本快捷读写 (readString/writeString)；<br>
 * 5. 流式传输进度高频节流防抖 (SftpProgressThrottler)；<br>
 * 6. DFS 深度优先递归删除非空目录树 (deleteRecursively)；<br>
 * 7. 目录树整包双向镜像同步 (uploadDirectory/downloadDirectory)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SftpScenarioService {

    private final SftpTemplate sftpTemplate;

    /**
     * 上传字节内容至远端目标路径（启用原子临时上传文件机制）
     *
     * @param remotePath 远端绝对路径
     * @param data       待上传字节数组
     */
    public void upload(String remotePath, byte[] data) {
        log.info("ateng-boot-ssh-sample: 正在上传文件至 [remotePath={}, size={} bytes]", remotePath, data != null ? data.length : 0);
        try (InputStream in = new ByteArrayInputStream(data != null ? data : new byte[0])) {
            sftpTemplate.upload(remotePath, in);
        } catch (Exception e) {
            log.error("ateng-boot-ssh-sample: 上传文件失败 [remotePath={}]", remotePath, e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    /**
     * 流式下载远端文件并返回全部字节数据
     *
     * @param remotePath 远端文件路径
     * @return 文件字节内容
     */
    public byte[] download(String remotePath) {
        log.info("ateng-boot-ssh-sample: 正在流式下载文件 [remotePath={}]", remotePath);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        sftpTemplate.download(remotePath, out);
        return out.toByteArray();
    }

    /**
     * 向远端既有文件尾部追加字节数据（支持断点续写与日志流式追加）
     *
     * @param remotePath 远端文件路径
     * @param data       追加数据
     */
    public void append(String remotePath, byte[] data) {
        log.info("ateng-boot-ssh-sample: 正在向文件尾部追加数据 [remotePath={}, appendSize={} bytes]", remotePath, data != null ? data.length : 0);
        try (InputStream in = new ByteArrayInputStream(data != null ? data : new byte[0])) {
            sftpTemplate.append(remotePath, in);
        } catch (Exception e) {
            log.error("ateng-boot-ssh-sample: 追加写文件失败 [remotePath={}]", remotePath, e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    /**
     * 原子重命名远端文件或目录
     *
     * @param oldPath 原路径
     * @param newPath 新路径
     */
    public void rename(String oldPath, String newPath) {
        log.info("ateng-boot-ssh-sample: 正在原子重命名 [oldPath={} -> newPath={}]", oldPath, newPath);
        sftpTemplate.rename(oldPath, newPath);
    }

    /**
     * 移动远端文件至目标新路径
     *
     * @param sourcePath 源路径
     * @param targetPath 目标路径
     */
    public void move(String sourcePath, String targetPath) {
        log.info("ateng-boot-ssh-sample: 正在移动文件 [sourcePath={} -> targetPath={}]", sourcePath, targetPath);
        sftpTemplate.move(sourcePath, targetPath);
    }

    /**
     * 快捷写入 UTF-8 字符串内容至远端配置文件
     *
     * @param remotePath 远端文件路径
     * @param content    文本内容
     */
    public void writeConfig(String remotePath, String content) {
        log.info("ateng-boot-ssh-sample: 正在写入远端配置文件 [remotePath={}]", remotePath);
        sftpTemplate.writeString(remotePath, content);
    }

    /**
     * 快捷读取远端文本配置文件内容
     *
     * @param remotePath 远端文件路径
     * @return 文本内容
     */
    public String readConfig(String remotePath) {
        log.info("ateng-boot-ssh-sample: 正在读取远端配置文件 [remotePath={}]", remotePath);
        return sftpTemplate.readString(remotePath);
    }

    /**
     * 带流式进度防抖监听的上传操作（防御高频进度回调产生日志风暴或高频网络风暴）
     *
     * @param remotePath 远端路径
     * @param data       数据内容
     * @param interval   防抖最小时间窗口
     * @param listener   业务进度监听器
     */
    public void uploadWithThrottledProgress(String remotePath, byte[] data, Duration interval, SftpProgressListener listener) {
        log.info("ateng-boot-ssh-sample: 正在执行带节流防抖的流式上传 [remotePath={}, interval={}]", remotePath, interval);
        SftpProgressListener throttledListener = SftpProgressThrottler.of(listener, interval);
        try (InputStream in = new ByteArrayInputStream(data != null ? data : new byte[0])) {
            sftpTemplate.upload(remotePath, in, throttledListener);
        } catch (Exception e) {
            log.error("ateng-boot-ssh-sample: 节流上传文件失败 [remotePath={}]", remotePath, e);
            throw (e instanceof RuntimeException re) ? re : new RuntimeException(e);
        }
    }

    /**
     * DFS 深度优先递归删除远端非空目录树或普通文件
     *
     * @param remotePath 远端路径
     * @return true 若删除成功
     */
    public boolean deleteRecursively(String remotePath) {
        log.info("ateng-boot-ssh-sample: 正在 DFS 递归删除远端路径 [remotePath={}]", remotePath);
        return sftpTemplate.deleteRecursively(remotePath);
    }

    /**
     * 整包递归同步本地目录至远端
     *
     * @param localDir  本地源目录
     * @param remoteDir 远端目标目录
     */
    public void syncUploadDirectory(File localDir, String remoteDir) {
        log.info("ateng-boot-ssh-sample: 正在整包上传目录树 [localDir={} -> remoteDir={}]", localDir, remoteDir);
        sftpTemplate.uploadDirectory(localDir, remoteDir);
    }

    /**
     * 整包递归下载远端目录树至本地目录
     *
     * @param remoteDir 远端源目录
     * @param localDir  本地目标目录
     */
    public void syncDownloadDirectory(String remoteDir, File localDir) {
        log.info("ateng-boot-ssh-sample: 正在整包下载目录树 [remoteDir={} -> localDir={}]", remoteDir, localDir);
        sftpTemplate.downloadDirectory(remoteDir, localDir);
    }

    /**
     * 检索指定目录下的文件元数据列表
     *
     * @param remotePath 远端目录路径
     * @return 文件元数据列表
     */
    public List<SftpFileInfo> list(String remotePath) {
        List<SftpFileInfo> list = sftpTemplate.list(remotePath);
        return list != null ? list : Collections.emptyList();
    }

    /**
     * 检查远端文件或目录是否存在
     *
     * @param remotePath 远端路径
     * @return true 若存在
     */
    public boolean exists(String remotePath) {
        return sftpTemplate.exists(remotePath);
    }
}
