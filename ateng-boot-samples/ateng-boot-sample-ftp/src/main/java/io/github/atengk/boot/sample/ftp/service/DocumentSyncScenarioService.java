package io.github.atengk.boot.sample.ftp.service;

import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 业务场景一：企业日常文档同步与生命周期管理服务组件
 * <p>
 * 演示并实测常规企业文件的全生命周期操作：
 * 纯文本读写、文件流式上传下载、存在性探查、元数据解析、
 * 增量数据追加写 (append)、单文件受管管道流复制 (copy)、
 * 文件重命名与跨目录移动、确定性删除与目录级联创建。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-30
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentSyncScenarioService {

    private final FtpTemplate ftpTemplate;

    /**
     * 上传字节数组文档到远程 FTP 路径
     *
     * @param remotePath 远程文件目标绝对或相对路径
     * @param content    文件内容字节数组
     * @return 上传成功返回 true
     */
    public boolean uploadBytes(String remotePath, byte[] content) {
        log.info("上传字节文档: {}, 大小: {} bytes", remotePath, content != null ? content.length : 0);
        return ftpTemplate.upload(remotePath, content);
    }

    /**
     * 上传本地磁盘文档到远程 FTP 路径
     *
     * @param remotePath 远程文件目标绝对或相对路径
     * @param localFile  本地磁盘文件
     * @return 上传成功返回 true
     */
    public boolean uploadFile(String remotePath, File localFile) {
        log.info("上传磁盘文档: {} -> {}", localFile.getAbsolutePath(), remotePath);
        return ftpTemplate.upload(remotePath, localFile);
    }

    /**
     * 上传输入流到远程 FTP 路径
     *
     * @param remotePath 远程文件路径
     * @param in         输入流
     * @return 上传成功返回 true
     */
    public boolean uploadStream(String remotePath, InputStream in) {
        log.info("上传输入流文档: {}", remotePath);
        return ftpTemplate.upload(remotePath, in);
    }

    /**
     * 下载远程文件并保存到本地磁盘
     *
     * @param remotePath 远程文件路径
     * @param localFile  本地目标文件
     * @return 下载成功返回 true
     */
    public boolean downloadToFile(String remotePath, File localFile) {
        log.info("下载文档到本地磁盘: {} -> {}", remotePath, localFile.getAbsolutePath());
        return ftpTemplate.download(remotePath, localFile);
    }

    /**
     * 下载远程文件到指定输出流
     *
     * @param remotePath 远程文件路径
     * @param out        目标输出流
     * @return 下载成功返回 true
     */
    public boolean downloadToStream(String remotePath, OutputStream out) {
        log.info("下载文档到输出流: {}", remotePath);
        return ftpTemplate.download(remotePath, out);
    }

    /**
     * 快捷写入纯文本内容至远程文件
     *
     * @param remotePath 远程文件路径
     * @param content    文本内容
     * @return 写入成功返回 true
     */
    public boolean writeText(String remotePath, String content) {
        log.info("快捷写入文本内容至: {}", remotePath);
        return ftpTemplate.writeString(remotePath, content);
    }

    /**
     * 快捷读取远程文件文本内容
     *
     * @param remotePath 远程文件路径
     * @return 文本内容
     */
    public String readText(String remotePath) {
        log.info("快捷读取文本内容: {}", remotePath);
        return ftpTemplate.readString(remotePath);
    }

    /**
     * 增量追加写入文本内容至远程已有文件末尾 (RFC 959 APPE)
     *
     * @param remotePath 远程文件路径
     * @param appendText 待追加的文本内容
     * @return 追加成功返回 true
     */
    public boolean appendText(String remotePath, String appendText) {
        log.info("增量追加文本至文件末尾: {}", remotePath);
        return ftpTemplate.append(remotePath, appendText, StandardCharsets.UTF_8);
    }

    /**
     * 复制远程 FTP 文件到新路径（受管管道流原子对拷）
     *
     * @param sourcePath 源文件路径
     * @param destPath   目标文件路径
     * @return 复制成功返回 true
     */
    public boolean copyDocument(String sourcePath, String destPath) {
        log.info("受管原子复制文件: {} -> {}", sourcePath, destPath);
        return ftpTemplate.copy(sourcePath, destPath);
    }

    /**
     * 安全重命名远程文件或目录
     *
     * @param fromPath 原路径
     * @param toPath   目标路径
     * @return 重命名成功返回 true
     */
    public boolean renameDocument(String fromPath, String toPath) {
        log.info("重命名远程文件: {} -> {}", fromPath, toPath);
        return ftpTemplate.rename(fromPath, toPath);
    }

    /**
     * 移动文件到目标目录下
     *
     * @param fromPath 待移动的文件路径
     * @param toDir    目标父目录路径
     * @return 移动成功返回 true
     */
    public boolean moveDocument(String fromPath, String toDir) {
        log.info("跨目录移动远程文件: {} -> {}", fromPath, toDir);
        return ftpTemplate.move(fromPath, toDir);
    }

    /**
     * 判断远程文件或目录是否存在
     *
     * @param remotePath 远程路径
     * @return 存在返回 true，否则返回 false
     */
    public boolean exists(String remotePath) {
        return ftpTemplate.exists(remotePath);
    }

    /**
     * 获取远程文件强类型元数据信息
     *
     * @param remotePath 远程路径
     * @return 文件元数据对象
     */
    public FtpFileInfo getDocumentInfo(String remotePath) {
        return ftpTemplate.getFileInfo(remotePath);
    }

    /**
     * 列出指定目录下的文件及子目录列表
     *
     * @param remoteDir 远程目录路径
     * @return 文件元数据列表
     */
    public List<FtpFileInfo> listDocuments(String remoteDir) {
        return ftpTemplate.listFiles(remoteDir);
    }

    /**
     * 删除远程文件
     *
     * @param remotePath 远程文件路径
     * @return 删除成功返回 true
     */
    public boolean deleteDocument(String remotePath) {
        log.info("删除远程文档: {}", remotePath);
        return ftpTemplate.deleteFile(remotePath);
    }

    /**
     * 递归创建多级远程目录 (mkdir -p)
     *
     * @param remoteDir 远程目录路径
     * @return 创建成功返回 true
     */
    public boolean makeDirectories(String remoteDir) {
        log.info("递归创建远程多级目录: {}", remoteDir);
        return ftpTemplate.makeDirectories(remoteDir);
    }

    /**
     * 递归删除远程目录及其所有子文件与子目录
     *
     * @param remoteDir 远程目录路径
     * @return 删除成功返回 true
     */
    public boolean deleteDirectory(String remoteDir) {
        log.info("递归删除远程目录: {}", remoteDir);
        return ftpTemplate.deleteDirectory(remoteDir, true);
    }
}
