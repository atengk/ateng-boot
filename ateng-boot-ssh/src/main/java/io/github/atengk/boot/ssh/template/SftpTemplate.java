package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.listener.SftpProgressListener;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.core.CoreModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.util.Assert;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SFTP 工业级文件传输门面 (SftpTemplate)
 * <p>
 * 1. 深度融通四位一体路由：透明支持缺省节点、YAML 命名注册表、线程上下文 (SshContextHolder) 与动态描述符 (SshServerInfo)；<br>
 * 2. 依托底座 SshSessionPool 建立瞬态借还模型（Stateless Borrowing），会话长连接池化复用，SFTP 子系统通道随用随关；<br>
 * 3. 严格践行原子上传铁律（.uploading.tmp 写入 + 原子覆盖 rename），配合 catch 异常补偿主动删除临时文件碎片；<br>
 * 4. 强力防范路径穿越提权（Path Traversal），上传多级嵌套路径自动递归创建父目录 (mkdir -p)；<br>
 * 5. 全面提供流式上传下载、传输进度实时监听 (SftpProgressListener)、深度优先非空目录递归删除 (deleteRecursively)、
 * 目录树整包双向同步 (uploadDirectory / downloadDirectory)、文件元数据 (SftpFileInfo)、目录项检索 (list) 与 POSIX 权限 chmod 治理；<br>
 * 6. 对称补齐增量追加写 (append)、远程重命名与跨目录移动 (rename/move)、快捷纯文本直读直写 (readString/writeString)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SftpTemplate {

    private static final Set<String> HIGH_RISK_ROOT_PATHS = Set.of(
            "/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin",
            "/srv", "/sys", "/tmp", "/usr", "/var"
    );

    @Getter
    private final SshSessionPool sessionPool;

    @Getter
    private final SshClientRegistry clientRegistry;

    @Getter
    private final SshProperties properties;

    public SftpTemplate(SshSessionPool sessionPool,
                        SshClientRegistry clientRegistry,
                        SshProperties properties) {
        Assert.notNull(sessionPool, "SshSessionPool must not be null");
        Assert.notNull(clientRegistry, "SshClientRegistry must not be null");
        this.sessionPool = sessionPool;
        this.clientRegistry = clientRegistry;
        this.properties = properties != null ? properties : new SshProperties();
    }

    // ==========================================
    // 1. 流式文件上传契约 (upload)
    // ==========================================

    /**
     * 上传输入流数据至远程路径（走当前上下文/缺省路由）
     *
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void upload(String remotePath, InputStream inputStream) {
        upload(null, remotePath, inputStream, null);
    }

    /**
     * 上传输入流数据至远程路径并挂载传输进度监听器
     *
     * @param remotePath       远程目标文件路径
     * @param inputStream      本地输入流
     * @param progressListener 传输进度监听器（可为 null）
     */
    public void upload(String remotePath, InputStream inputStream, SftpProgressListener progressListener) {
        upload(null, remotePath, inputStream, progressListener);
    }

    /**
     * 上传输入流数据至指定远程服务器路径
     *
     * @param serverInfo  动态目标服务器描述符（为 null 则走上下文寻址）
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void upload(SshServerInfo serverInfo, String remotePath, InputStream inputStream) {
        upload(serverInfo, remotePath, inputStream, null);
    }

    /**
     * 上传输入流数据至指定远程服务器路径并挂载进度监听器
     *
     * @param serverInfo       动态目标服务器描述符
     * @param remotePath       远程目标文件路径
     * @param inputStream      本地输入流
     * @param progressListener 传输进度监听器
     */
    public void upload(SshServerInfo serverInfo, String remotePath, InputStream inputStream, SftpProgressListener progressListener) {
        Assert.notNull(inputStream, "InputStream must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                ensureParentDirectoryExists(sftp, normalizedPath);
                doUploadStream(sftp, normalizedPath, inputStream, progressListener, -1L);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 上传本地磁盘文件至远程路径（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param localFile  本地磁盘文件
     */
    public void upload(String remotePath, File localFile) {
        upload(null, remotePath, localFile, null);
    }

    /**
     * 上传本地磁盘文件至远程路径并挂载进度监听器
     *
     * @param remotePath       远程目标文件路径
     * @param localFile        本地磁盘文件
     * @param progressListener 传输进度监听器
     */
    public void upload(String remotePath, File localFile, SftpProgressListener progressListener) {
        upload(null, remotePath, localFile, progressListener);
    }

    /**
     * 上传本地磁盘文件至指定远程服务器路径
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param localFile  本地磁盘文件
     */
    public void upload(SshServerInfo serverInfo, String remotePath, File localFile) {
        upload(serverInfo, remotePath, localFile, null);
    }

    /**
     * 上传本地磁盘文件至指定远程服务器路径并挂载进度监听器
     *
     * @param serverInfo       动态目标服务器描述符
     * @param remotePath       远程目标文件路径
     * @param localFile        本地磁盘文件
     * @param progressListener 传输进度监听器
     */
    public void upload(SshServerInfo serverInfo, String remotePath, File localFile, SftpProgressListener progressListener) {
        Assert.notNull(localFile, "Local file must not be null");
        Assert.isTrue(localFile.exists() && localFile.isFile(), "Local file must exist and be a regular file: " + localFile.getAbsolutePath());
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session);
                 InputStream in = new FileInputStream(localFile)) {
                ensureParentDirectoryExists(sftp, normalizedPath);
                doUploadStream(sftp, normalizedPath, in, progressListener, localFile.length());
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 上传字节数组至远程路径（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void upload(String remotePath, byte[] data) {
        upload(null, remotePath, data, null);
    }

    /**
     * 上传字节数组至远程路径并挂载进度监听器
     *
     * @param remotePath       远程目标文件路径
     * @param data             数据字节数组
     * @param progressListener 传输进度监听器
     */
    public void upload(String remotePath, byte[] data, SftpProgressListener progressListener) {
        upload(null, remotePath, data, progressListener);
    }

    /**
     * 上传字节数组至指定远程服务器路径
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void upload(SshServerInfo serverInfo, String remotePath, byte[] data) {
        upload(serverInfo, remotePath, data, null);
    }

    /**
     * 上传字节数组至指定远程服务器路径并挂载进度监听器
     *
     * @param serverInfo       动态目标服务器描述符
     * @param remotePath       远程目标文件路径
     * @param data             数据字节数组
     * @param progressListener 传输进度监听器
     */
    public void upload(SshServerInfo serverInfo, String remotePath, byte[] data, SftpProgressListener progressListener) {
        Assert.notNull(data, "Data byte array must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session);
                 InputStream in = new ByteArrayInputStream(data)) {
                ensureParentDirectoryExists(sftp, normalizedPath);
                doUploadStream(sftp, normalizedPath, in, progressListener, (long) data.length);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    // ==========================================
    // 1.1 增量追加写契约 (append)
    // ==========================================

    /**
     * 向远程文件末尾追加输入流数据（走当前上下文/缺省路由）
     * <p>
     * 遵循 POSIX {@code O_CREAT | O_APPEND} 标准语义；若远程文件不存在，则自动递归创建缺失的父级目录并新建文件写入。
     * </p>
     *
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void append(String remotePath, InputStream inputStream) {
        append((SshServerInfo) null, remotePath, inputStream);
    }

    /**
     * 向远程文件末尾追加字节数组数据（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void append(String remotePath, byte[] data) {
        append((SshServerInfo) null, remotePath, data);
    }

    /**
     * 在指定具名服务器节点上向远程文件末尾追加输入流数据
     *
     * @param serverName  节点名称
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void append(String serverName, String remotePath, InputStream inputStream) {
        append(resolveServerInfo(serverName), remotePath, inputStream);
    }

    /**
     * 在指定具名服务器节点上向远程文件末尾追加字节数组数据
     *
     * @param serverName 节点名称
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void append(String serverName, String remotePath, byte[] data) {
        append(resolveServerInfo(serverName), remotePath, data);
    }

    /**
     * 在指定目标描述符服务器上向远程文件末尾追加字节数组数据
     *
     * @param serverInfo 动态目标描述符
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void append(SshServerInfo serverInfo, String remotePath, byte[] data) {
        Assert.notNull(data, "byte[] data must not be null");
        append(serverInfo, remotePath, new ByteArrayInputStream(data));
    }

    /**
     * 在指定目标描述符服务器上向远程文件末尾追加输入流数据
     *
     * @param serverInfo  动态目标描述符
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void append(SshServerInfo serverInfo, String remotePath, InputStream inputStream) {
        Assert.notNull(inputStream, "InputStream must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                ensureParentDirectoryExists(sftp, normalizedPath);
                doAppendStream(sftp, normalizedPath, inputStream);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    // ==========================================
    // 2. 流式文件下载契约 (download)
    // ==========================================

    /**
     * 下载远程文件并写入本地输出流（走当前上下文/缺省路由）
     *
     * @param remotePath   远程目标文件路径
     * @param outputStream 目标输出流
     */
    public void download(String remotePath, OutputStream outputStream) {
        download(null, remotePath, outputStream, null);
    }

    /**
     * 下载远程文件并写入本地输出流，同时挂载进度监听器
     *
     * @param remotePath       远程目标文件路径
     * @param outputStream     目标输出流
     * @param progressListener 传输进度监听器
     */
    public void download(String remotePath, OutputStream outputStream, SftpProgressListener progressListener) {
        download(null, remotePath, outputStream, progressListener);
    }

    /**
     * 下载远程文件并写入本地输出流
     *
     * @param serverInfo   动态目标服务器描述符
     * @param remotePath   远程目标文件路径
     * @param outputStream 目标输出流
     */
    public void download(SshServerInfo serverInfo, String remotePath, OutputStream outputStream) {
        download(serverInfo, remotePath, outputStream, null);
    }

    /**
     * 下载远程文件并写入本地输出流，同时挂载进度监听器
     *
     * @param serverInfo       动态目标服务器描述符
     * @param remotePath       远程目标文件路径
     * @param outputStream     目标输出流
     * @param progressListener 传输进度监听器
     */
    public void download(SshServerInfo serverInfo, String remotePath, OutputStream outputStream, SftpProgressListener progressListener) {
        Assert.notNull(outputStream, "OutputStream must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session);
                 InputStream in = sftp.read(normalizedPath)) {
                long totalBytes = -1L;
                try {
                    SftpClient.Attributes attrs = sftp.stat(normalizedPath);
                    if (attrs != null) {
                        totalBytes = attrs.getSize();
                    }
                } catch (Exception ignore) {
                    log.debug("ateng-boot-ssh: 无法获取远程文件大小用于进度计算 [{}]: {}", normalizedPath, ignore.getMessage());
                }
                copyStream(in, outputStream, progressListener, totalBytes);
                outputStream.flush();
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 下载远程文件流并由函数式消费者处理（走当前上下文/缺省路由）
     *
     * @param remotePath     远程目标文件路径
     * @param streamConsumer 输入流消费函数
     */
    public void download(String remotePath, Consumer<InputStream> streamConsumer) {
        download(null, remotePath, streamConsumer);
    }

    /**
     * 下载远程文件流并由函数式消费者处理
     *
     * @param serverInfo     动态目标服务器描述符
     * @param remotePath     远程目标文件路径
     * @param streamConsumer 输入流消费函数
     */
    public void download(SshServerInfo serverInfo, String remotePath, Consumer<InputStream> streamConsumer) {
        Assert.notNull(streamConsumer, "StreamConsumer must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session);
                 InputStream in = sftp.read(normalizedPath)) {
                streamConsumer.accept(in);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 下载远程文件至本地磁盘文件（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param localFile  本地目标文件
     */
    public void downloadToFile(String remotePath, File localFile) {
        downloadToFile(null, remotePath, localFile, null);
    }

    /**
     * 下载远程文件至本地磁盘文件，挂载进度监听器
     *
     * @param remotePath       远程目标文件路径
     * @param localFile        本地目标文件
     * @param progressListener 传输进度监听器
     */
    public void downloadToFile(String remotePath, File localFile, SftpProgressListener progressListener) {
        downloadToFile(null, remotePath, localFile, progressListener);
    }

    /**
     * 下载远程文件至本地磁盘文件
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param localFile  本地目标文件
     */
    public void downloadToFile(SshServerInfo serverInfo, String remotePath, File localFile) {
        downloadToFile(serverInfo, remotePath, localFile, null);
    }

    /**
     * 下载远程文件至本地磁盘文件，挂载进度监听器
     *
     * @param serverInfo       动态目标服务器描述符
     * @param remotePath       远程目标文件路径
     * @param localFile        本地目标文件
     * @param progressListener 传输进度监听器
     */
    public void downloadToFile(SshServerInfo serverInfo, String remotePath, File localFile, SftpProgressListener progressListener) {
        Assert.notNull(localFile, "Local destination file must not be null");
        File parentDir = localFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(localFile)) {
            download(serverInfo, remotePath, out, progressListener);
        } catch (IOException e) {
            throw new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                    "写入本地文件失败 [" + localFile.getAbsolutePath() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 下载远程文件为内存字节数组（仅适用于中小文件）
     *
     * @param remotePath 远程目标文件路径
     * @return 文件字节数据
     */
    public byte[] downloadToBytes(String remotePath) {
        return downloadToBytes(null, remotePath);
    }

    /**
     * 下载远程文件为内存字节数组
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @return 文件字节数据
     */
    public byte[] downloadToBytes(SshServerInfo serverInfo, String remotePath) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        download(serverInfo, remotePath, baos, null);
        return baos.toByteArray();
    }

    // ==========================================
    // 2.1 文本读写契约 (readString, writeString)
    // ==========================================

    /**
     * 读取远程文本文件全部内容（缺省 UTF-8 编码，走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @return 文本内容
     */
    public String readString(String remotePath) {
        return readString(remotePath, StandardCharsets.UTF_8);
    }

    /**
     * 读取远程文本文件全部内容（指定字符编码，走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param charset    字符集编码（为 null 则缺省 UTF-8）
     * @return 文本内容
     */
    public String readString(String remotePath, Charset charset) {
        return readString((SshServerInfo) null, remotePath, charset);
    }

    /**
     * 在指定具名服务器节点上读取远程文本文件全部内容（缺省 UTF-8 编码）
     *
     * @param serverName 节点名称
     * @param remotePath 远程目标文件路径
     * @return 文本内容
     */
    public String readString(String serverName, String remotePath) {
        return readString(serverName, remotePath, StandardCharsets.UTF_8);
    }

    /**
     * 在指定具名服务器节点上读取远程文本文件全部内容（指定字符编码）
     *
     * @param serverName 节点名称
     * @param remotePath 远程目标文件路径
     * @param charset    字符集编码
     * @return 文本内容
     */
    public String readString(String serverName, String remotePath, Charset charset) {
        return readString(resolveServerInfo(serverName), remotePath, charset);
    }

    /**
     * 在指定目标描述符服务器上读取远程文本文件全部内容（缺省 UTF-8 编码）
     *
     * @param serverInfo 动态目标描述符
     * @param remotePath 远程目标文件路径
     * @return 文本内容
     */
    public String readString(SshServerInfo serverInfo, String remotePath) {
        return readString(serverInfo, remotePath, StandardCharsets.UTF_8);
    }

    /**
     * 在指定目标描述符服务器上读取远程文本文件全部内容（指定字符编码）
     *
     * @param serverInfo 动态目标描述符
     * @param remotePath 远程目标文件路径
     * @param charset    字符集编码（为 null 则缺省 UTF-8）
     * @return 文本内容
     */
    public String readString(SshServerInfo serverInfo, String remotePath, Charset charset) {
        byte[] bytes = downloadToBytes(serverInfo, remotePath);
        Charset effectiveCharset = (charset != null) ? charset : StandardCharsets.UTF_8;
        return new String(bytes, effectiveCharset);
    }

    /**
     * 写入字符串文本内容至远程文件（缺省覆盖模式，缺省 UTF-8 编码，走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param content    文本内容（严禁为 null）
     */
    public void writeString(String remotePath, String content) {
        writeString(remotePath, content, StandardCharsets.UTF_8);
    }

    /**
     * 写入字符串文本内容至远程文件（指定字符编码，走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param content    文本内容（严禁为 null）
     * @param charset    字符集编码（为 null 则缺省 UTF-8）
     */
    public void writeString(String remotePath, String content, Charset charset) {
        writeString((SshServerInfo) null, remotePath, content, charset);
    }

    /**
     * 在指定具名服务器节点上写入字符串文本内容至远程文件（缺省 UTF-8 编码）
     *
     * @param serverName 节点名称
     * @param remotePath 远程目标文件路径
     * @param content    文本内容
     */
    public void writeString(String serverName, String remotePath, String content) {
        writeString(serverName, remotePath, content, StandardCharsets.UTF_8);
    }

    /**
     * 在指定具名服务器节点上写入字符串文本内容至远程文件（指定字符编码）
     *
     * @param serverName 节点名称
     * @param remotePath 远程目标文件路径
     * @param content    文本内容
     * @param charset    字符集编码
     */
    public void writeString(String serverName, String remotePath, String content, Charset charset) {
        writeString(resolveServerInfo(serverName), remotePath, content, charset);
    }

    /**
     * 在指定目标描述符服务器上写入字符串文本内容至远程文件（缺省 UTF-8 编码）
     *
     * @param serverInfo 动态目标描述符
     * @param remotePath 远程目标文件路径
     * @param content    文本内容
     */
    public void writeString(SshServerInfo serverInfo, String remotePath, String content) {
        writeString(serverInfo, remotePath, content, StandardCharsets.UTF_8);
    }

    /**
     * 在指定目标描述符服务器上写入字符串文本内容至远程文件（指定字符编码）
     *
     * @param serverInfo 动态目标描述符
     * @param remotePath 远程目标文件路径
     * @param content    文本内容
     * @param charset    字符集编码
     */
    public void writeString(SshServerInfo serverInfo, String remotePath, String content, Charset charset) {
        Assert.notNull(content, "Content string must not be null");
        Charset effectiveCharset = (charset != null) ? charset : StandardCharsets.UTF_8;
        upload(serverInfo, remotePath, content.getBytes(effectiveCharset));
    }

    // ==========================================
    // 3. 文件属性、元数据与管理契约 (stat, exists, delete, chmod, list)
    // ==========================================

    /**
     * 查询远程文件或目录的只读元数据（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @return 文件元数据对象
     */
    public SftpFileInfo stat(String remotePath) {
        return stat(null, remotePath);
    }

    /**
     * 查询远程文件或目录的只读元数据
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @return 文件元数据对象
     */
    public SftpFileInfo stat(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        return sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                SftpClient.Attributes attrs = sftp.stat(normalizedPath);
                return SftpFileInfo.fromAttributes(normalizedPath, attrs);
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 检查远程文件或目录是否存在（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标路径
     * @return 是否存在
     */
    public boolean exists(String remotePath) {
        return exists(null, remotePath);
    }

    /**
     * 检查远程文件或目录是否存在
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标路径
     * @return 是否存在
     */
    public boolean exists(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        return sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                sftp.stat(normalizedPath);
                return true;
            } catch (SftpException e) {
                if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw translateException(e, normalizedPath);
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 列出远程指定目录下的所有文件与子目录元数据列表（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目录路径
     * @return 目录项元数据列表（自动过滤 "." 与 ".." 相对标记）
     */
    public List<SftpFileInfo> list(String remotePath) {
        return list(null, remotePath);
    }

    /**
     * 列出远程指定目录下的所有文件与子目录元数据列表
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目录路径
     * @return 目录项元数据列表
     */
    public List<SftpFileInfo> list(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        return sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                Iterable<SftpClient.DirEntry> entries = sftp.readDir(normalizedPath);
                List<SftpFileInfo> result = new ArrayList<>();
                for (SftpClient.DirEntry entry : entries) {
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) {
                        continue;
                    }
                    String itemPath = normalizedPath.endsWith("/") ? normalizedPath + name : normalizedPath + "/" + name;
                    result.add(SftpFileInfo.fromAttributes(itemPath, entry.getAttributes()));
                }
                return Collections.unmodifiableList(result);
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 删除远程单个文件或空目录（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标路径
     * @return 是否删除成功（若文件原本不存在则返回 false）
     */
    public boolean delete(String remotePath) {
        return delete(null, remotePath);
    }

    /**
     * 删除远程单个文件或空目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标路径
     * @return 是否删除成功（若文件原本不存在则返回 false）
     */
    public boolean delete(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        return sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                SftpClient.Attributes attrs;
                try {
                    attrs = sftp.stat(normalizedPath);
                } catch (SftpException e) {
                    if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                        return false;
                    }
                    throw e;
                }

                if (attrs.isDirectory()) {
                    sftp.rmdir(normalizedPath);
                } else {
                    sftp.remove(normalizedPath);
                }
                return true;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 深度优先（DFS）递归删除远程非空目录或文件（走当前上下文/缺省路由）
     * <p>
     * 具备严格的高危系统保护路径校验，严禁删除根目录或关键系统目录。
     * </p>
     *
     * @param remotePath 远程目标路径
     * @return 是否删除成功（若路径原本不存在则返回 false）
     */
    public boolean deleteRecursively(String remotePath) {
        return deleteRecursively(null, remotePath);
    }

    /**
     * 深度优先（DFS）递归删除远程非空目录或文件
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标路径
     * @return 是否删除成功
     */
    public boolean deleteRecursively(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        validatePathSafeForRecursiveDeletion(normalizedPath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        return sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                return doDeleteRecursively(sftp, normalizedPath);
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 修改远程文件或目录的 POSIX 权限（走当前上下文/缺省路由）
     *
     * @param remotePath  远程目标路径
     * @param permissions POSIX 权限位（如 0755, 0644）
     */
    public void chmod(String remotePath, int permissions) {
        chmod(null, remotePath, permissions);
    }

    /**
     * 修改远程文件或目录的 POSIX 权限
     *
     * @param serverInfo  动态目标服务器描述符
     * @param remotePath  远程目标路径
     * @param permissions POSIX 权限位（如 0755, 0644）
     */
    public void chmod(SshServerInfo serverInfo, String remotePath, int permissions) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                SftpClient.Attributes attrs = new SftpClient.Attributes();
                attrs.setPermissions(permissions);
                sftp.setStat(normalizedPath, attrs);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 创建远程单级目录（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目录路径
     */
    public void mkdir(String remotePath) {
        mkdir(null, remotePath);
    }

    /**
     * 创建远程单级目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目录路径
     */
    public void mkdir(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                sftp.mkdir(normalizedPath);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    /**
     * 递归创建远程多级父目录（类似 mkdir -p，走当前上下文/缺省路由）
     *
     * @param remotePath 远程目录路径
     */
    public void mkdirs(String remotePath) {
        mkdirs(null, remotePath);
    }

    /**
     * 递归创建远程多级父目录（类似 mkdir -p）
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目录路径
     */
    public void mkdirs(SshServerInfo serverInfo, String remotePath) {
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                createDirectoriesRecursively(sftp, normalizedPath);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    // ==========================================
    // 3.1 文件移动与重命名契约 (rename, move)
    // ==========================================

    /**
     * 重命名远程文件或目录（走当前上下文/缺省路由）
     *
     * @param oldPath 原始路径
     * @param newPath 新路径
     */
    public void rename(String oldPath, String newPath) {
        rename((SshServerInfo) null, oldPath, newPath);
    }

    /**
     * 在指定具名服务器节点上重命名远程文件或目录
     *
     * @param serverName 节点名称
     * @param oldPath    原始路径
     * @param newPath    新路径
     */
    public void rename(String serverName, String oldPath, String newPath) {
        rename(resolveServerInfo(serverName), oldPath, newPath);
    }

    /**
     * 在指定目标描述符服务器上重命名远程文件或目录
     *
     * @param serverInfo 动态目标描述符
     * @param oldPath    原始路径
     * @param newPath    新路径
     */
    public void rename(SshServerInfo serverInfo, String oldPath, String newPath) {
        move(serverInfo, oldPath, newPath);
    }

    /**
     * 移动远程文件或目录至目标路径（支持跨目录移动且自动创建目标父级目录，走当前上下文/缺省路由）
     *
     * @param sourcePath 原始源路径
     * @param targetPath 目标路径（可为新文件路径或目标目录路径）
     */
    public void move(String sourcePath, String targetPath) {
        move((SshServerInfo) null, sourcePath, targetPath);
    }

    /**
     * 在指定具名服务器节点上移动远程文件或目录
     *
     * @param serverName 节点名称
     * @param sourcePath 原始源路径
     * @param targetPath 目标路径
     */
    public void move(String serverName, String sourcePath, String targetPath) {
        move(resolveServerInfo(serverName), sourcePath, targetPath);
    }

    /**
     * 在指定目标描述符服务器上移动远程文件或目录
     *
     * @param serverInfo 动态目标描述符
     * @param sourcePath 原始源路径
     * @param targetPath 目标路径
     */
    public void move(SshServerInfo serverInfo, String sourcePath, String targetPath) {
        Assert.hasText(sourcePath, "Source path must not be null or blank");
        Assert.hasText(targetPath, "Target path must not be null or blank");
        String normalizedSource = cleanAndValidatePath(sourcePath);
        String normalizedTarget = cleanAndValidatePath(targetPath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                String destination = normalizedTarget;
                boolean isDir = targetPath.endsWith("/") || targetPath.endsWith("\\");
                if (!isDir) {
                    try {
                        SftpClient.Attributes attrs = sftp.stat(normalizedTarget);
                        if (attrs.isDirectory()) {
                            isDir = true;
                        }
                    } catch (Exception ignore) {
                        // 目标尚不存在
                    }
                }
                if (isDir) {
                    String cleanSource = normalizedSource.endsWith("/")
                            ? normalizedSource.substring(0, normalizedSource.length() - 1)
                            : normalizedSource;
                    String filename = cleanSource.substring(cleanSource.lastIndexOf('/') + 1);
                    destination = destination.endsWith("/") ? destination + filename : destination + "/" + filename;
                }
                ensureParentDirectoryExists(sftp, destination);
                sftp.rename(normalizedSource, destination, SftpClient.CopyMode.Overwrite);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedSource + " -> " + normalizedTarget);
            }
        });
    }

    // ==========================================
    // 4. 目录树整包双向同步契约 (uploadDirectory / downloadDirectory)
    // ==========================================

    /**
     * 递归上传本地目录树整包至远程目录（走当前上下文/缺省路由）
     *
     * @param localDir  本地根目录
     * @param remoteDir 远程目标根目录
     */
    public void uploadDirectory(File localDir, String remoteDir) {
        uploadDirectory(null, localDir, remoteDir);
    }

    /**
     * 递归上传本地目录树整包至远程目录
     *
     * @param localDir  本地根目录 Path
     * @param remoteDir 远程目标根目录
     */
    public void uploadDirectory(Path localDir, String remoteDir) {
        uploadDirectory(null, localDir != null ? localDir.toFile() : null, remoteDir);
    }

    /**
     * 递归上传本地目录树整包至指定远程服务器目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param localDir   本地根目录 Path
     * @param remoteDir  远程目标根目录
     */
    public void uploadDirectory(SshServerInfo serverInfo, Path localDir, String remoteDir) {
        uploadDirectory(serverInfo, localDir != null ? localDir.toFile() : null, remoteDir);
    }

    /**
     * 递归上传本地目录树整包至指定远程服务器目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param localDir   本地根目录 File
     * @param remoteDir  远程目标根目录
     */
    public void uploadDirectory(SshServerInfo serverInfo, File localDir, String remoteDir) {
        Assert.notNull(localDir, "Local directory must not be null");
        Assert.isTrue(localDir.exists() && localDir.isDirectory(), "Local path must exist and be a directory: " + localDir.getAbsolutePath());
        String normalizedRemoteDir = normalizeRemotePath(remoteDir);
        SshServerInfo target = resolveServerInfo(serverInfo);

        Path localBasePath = localDir.toPath();

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                createDirectoriesRecursively(sftp, normalizedRemoteDir);

                Files.walkFileTree(localBasePath, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        if (localBasePath.equals(dir)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relPath = localBasePath.relativize(dir).toString().replace('\\', '/');
                        String targetDir = normalizedRemoteDir.endsWith("/") ? normalizedRemoteDir + relPath : normalizedRemoteDir + "/" + relPath;
                        createDirectoriesRecursively(sftp, targetDir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        String filename = file.getFileName().toString();
                        if (isIgnoredHostFile(filename)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relPath = localBasePath.relativize(file).toString().replace('\\', '/');
                        String targetFile = normalizedRemoteDir.endsWith("/") ? normalizedRemoteDir + relPath : normalizedRemoteDir + "/" + relPath;
                        try (InputStream in = Files.newInputStream(file)) {
                            doUploadStream(sftp, targetFile, in, null, attrs.size());
                        } catch (Exception e) {
                            throw new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                                    "目录同步上传文件失败 [" + file.toAbsolutePath() + " -> " + targetFile + "]: " + e.getMessage(), e);
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedRemoteDir);
            }
        });
    }

    /**
     * 递归下载远程目录树整包至本地目录（走当前上下文/缺省路由）
     *
     * @param remoteDir 远程根目录
     * @param localDir  本地目标根目录 File
     */
    public void downloadDirectory(String remoteDir, File localDir) {
        downloadDirectory(null, remoteDir, localDir);
    }

    /**
     * 递归下载远程目录树整包至本地目录
     *
     * @param remoteDir 远程根目录
     * @param localDir  本地目标根目录 Path
     */
    public void downloadDirectory(String remoteDir, Path localDir) {
        downloadDirectory(null, remoteDir, localDir != null ? localDir.toFile() : null);
    }

    /**
     * 递归下载远程目录树整包至本地目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remoteDir  远程根目录
     * @param localDir   本地目标根目录 Path
     */
    public void downloadDirectory(SshServerInfo serverInfo, String remoteDir, Path localDir) {
        downloadDirectory(serverInfo, remoteDir, localDir != null ? localDir.toFile() : null);
    }

    /**
     * 递归下载远程目录树整包至本地目录
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remoteDir  远程根目录
     * @param localDir   本地目标根目录 File
     */
    public void downloadDirectory(SshServerInfo serverInfo, String remoteDir, File localDir) {
        Assert.notNull(localDir, "Local destination directory must not be null");
        String normalizedRemoteDir = normalizeRemotePath(remoteDir);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = createSftpClient(session)) {
                Path localBasePath = localDir.toPath();
                Files.createDirectories(localBasePath);
                Set<String> visitedPaths = new HashSet<>();
                doDownloadDirectoryRecursively(sftp, normalizedRemoteDir, localBasePath, visitedPaths);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedRemoteDir);
            }
        });
    }

    // ==========================================
    // 内部私有支撑方法
    // ==========================================

    private SftpClient createSftpClient(ClientSession session) throws IOException {
        SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
        Duration timeout = properties.getSftp().getTransferTimeout();
        if (timeout != null && !timeout.isZero()) {
            CoreModuleProperties.IDLE_TIMEOUT.set(sftp.getClientChannel(), timeout);
            CoreModuleProperties.NIO2_READ_TIMEOUT.set(sftp.getClientChannel(), timeout);
            CoreModuleProperties.NIO2_MIN_WRITE_TIMEOUT.set(sftp.getClientChannel(), timeout);
        }
        return sftp;
    }

    private void doUploadStream(SftpClient sftp, String remotePath, InputStream in, SftpProgressListener progressListener, long totalBytes) throws IOException {
        boolean atomic = properties.getSftp().isAtomicUploadEnabled();
        if (!atomic) {
            try (OutputStream out = sftp.write(remotePath, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                copyStream(in, out, progressListener, totalBytes);
                out.flush();
            }
            return;
        }

        // 原子上传守护流程：写入临时后缀文件 -> 原子覆盖 rename -> 异常补偿删除
        String suffix = properties.getSftp().getAtomicUploadSuffix();
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tempPath = remotePath + "." + shortId + suffix;

        try {
            try (OutputStream out = sftp.write(tempPath, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                copyStream(in, out, progressListener, totalBytes);
                out.flush();
            }
            // 完整闭合后执行原子重命名覆盖
            sftp.rename(tempPath, remotePath, SftpClient.CopyMode.Overwrite);
        } catch (Exception e) {
            // 异常最佳努力补偿清理临时碎片文件
            try {
                sftp.remove(tempPath);
            } catch (Exception ignore) {
                log.debug("ateng-boot-ssh: 清理临时上传文件失败 [{}]: {}", tempPath, ignore.getMessage());
            }
            throw e;
        }
    }

    private void doAppendStream(SftpClient sftp, String remotePath, InputStream in) throws IOException {
        try (OutputStream out = sftp.write(remotePath, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Append)) {
            copyStream(in, out, null, -1L);
            out.flush();
        }
    }

    private void copyStream(InputStream in, OutputStream out, SftpProgressListener listener, long totalBytes) throws IOException {
        int bufferSize = (int) properties.getSftp().getBufferSize().toBytes();
        if (bufferSize <= 0) {
            bufferSize = 32768;
        }
        byte[] buffer = new byte[bufferSize];
        int bytesRead;
        long transferred = 0L;
        try {
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                transferred += bytesRead;
                if (listener != null) {
                    try {
                        listener.onProgress(transferred, totalBytes);
                    } catch (Throwable t) {
                        log.debug("ateng-boot-ssh: SftpProgressListener 进度回调异常: {}", t.getMessage());
                    }
                }
            }
        } finally {
            if (listener instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignore) {
                    log.debug("ateng-boot-ssh: 关闭 SftpProgressListener 异常: {}", ignore.getMessage());
                }
            }
        }
    }

    private boolean doDeleteRecursively(SftpClient sftp, String path) throws IOException {
        SftpClient.Attributes attrs;
        try {
            attrs = sftp.lstat(path);
        } catch (SftpException e) {
            if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }

        // 若为符号链接（即使指向目录）或普通文件，直接单点删除链接本身，严禁 DFS 穿透删除目标源目录真实数据
        if (attrs.isSymbolicLink() || !attrs.isDirectory()) {
            sftp.remove(path);
            return true;
        }

        Iterable<SftpClient.DirEntry> entries = sftp.readDir(path);
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) {
                continue;
            }
            String childPath = path.endsWith("/") ? path + name : path + "/" + name;
            doDeleteRecursively(sftp, childPath);
        }
        sftp.rmdir(path);
        return true;
    }

    private void doDownloadDirectoryRecursively(SftpClient sftp, String remotePath, Path localDir, Set<String> visitedPaths) throws IOException {
        String canonicalPath;
        try {
            canonicalPath = sftp.canonicalPath(remotePath);
        } catch (Exception e) {
            canonicalPath = remotePath;
        }
        if (!visitedPaths.add(canonicalPath)) {
            log.warn("ateng-boot-ssh: 检测到 SFTP 目录符号链接成环或重复访问，跳过递归: {}", remotePath);
            return;
        }

        Iterable<SftpClient.DirEntry> entries = sftp.readDir(remotePath);
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name) || isIgnoredHostFile(name)) {
                continue;
            }
            String childRemote = remotePath.endsWith("/") ? remotePath + name : remotePath + "/" + name;
            Path childLocal = localDir.resolve(name);

            SftpClient.Attributes attrs = entry.getAttributes();
            // 符号链接特殊处理：防范死循环与递归穿透
            if (attrs.isSymbolicLink()) {
                try {
                    SftpClient.Attributes targetAttrs = sftp.stat(childRemote);
                    if (targetAttrs.isDirectory()) {
                        Files.createDirectories(childLocal);
                        doDownloadDirectoryRecursively(sftp, childRemote, childLocal, visitedPaths);
                    } else {
                        downloadSingleFile(sftp, childRemote, childLocal, targetAttrs.getSize());
                    }
                } catch (Exception e) {
                    log.warn("ateng-boot-ssh: 无法解析符号链接目标 [{}]: {}", childRemote, e.getMessage());
                }
                continue;
            }

            if (attrs.isDirectory()) {
                Files.createDirectories(childLocal);
                doDownloadDirectoryRecursively(sftp, childRemote, childLocal, visitedPaths);
            } else {
                downloadSingleFile(sftp, childRemote, childLocal, attrs.getSize());
            }
        }
    }

    private void downloadSingleFile(SftpClient sftp, String remotePath, Path localFile, long fileSize) throws IOException {
        Path parent = localFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (InputStream in = sftp.read(remotePath);
             OutputStream out = Files.newOutputStream(localFile)) {
            copyStream(in, out, null, fileSize);
            out.flush();
        } catch (Exception e) {
            throw new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                    "目录同步下载文件失败 [" + remotePath + " -> " + localFile.toAbsolutePath() + "]: " + e.getMessage(), e);
        }
    }

    private boolean isIgnoredHostFile(String filename) {
        if (filename == null || filename.isBlank()) {
            return true;
        }
        String lower = filename.toLowerCase();
        return ".ds_store".equals(lower) || "desktop.ini".equals(lower) || "thumbs.db".equals(lower);
    }

    private void validatePathSafeForRecursiveDeletion(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Remote path must not be null or blank");
        }
        String clean = path;
        while (clean.length() > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (HIGH_RISK_ROOT_PATHS.contains(clean)) {
            throw new IllegalArgumentException("禁止递归删除系统关键受保护目录: " + path);
        }
    }

    private void ensureParentDirectoryExists(SftpClient sftp, String remotePath) throws IOException {
        int lastSlash = remotePath.lastIndexOf('/');
        if (lastSlash > 0) {
            String parentDir = remotePath.substring(0, lastSlash);
            createDirectoriesRecursively(sftp, parentDir);
        }
    }

    private void createDirectoriesRecursively(SftpClient sftp, String dirPath) throws IOException {
        if (dirPath == null || dirPath.isBlank() || "/".equals(dirPath)) {
            return;
        }
        String[] parts = dirPath.split("/");
        StringBuilder current = new StringBuilder();
        if (dirPath.startsWith("/")) {
            current.append("/");
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (current.length() > 0 && current.charAt(current.length() - 1) != '/') {
                current.append("/");
            }
            current.append(part);
            String path = current.toString();
            try {
                SftpClient.Attributes attrs = sftp.stat(path);
                if (!attrs.isDirectory()) {
                    throw new IOException("目标路径已存在但不是目录: " + path);
                }
            } catch (SftpException e) {
                if (e.getStatus() == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                    try {
                        sftp.mkdir(path);
                    } catch (SftpException ex) {
                        // 并发防卫：再次校验目录是否由并发创建完成
                        try {
                            SftpClient.Attributes attrs = sftp.stat(path);
                            if (!attrs.isDirectory()) {
                                throw ex;
                            }
                        } catch (Exception ignore) {
                            log.debug("ateng-boot-ssh: 并发校验已建目录异常 [{}]: {}", path, ignore.getMessage());
                            throw ex;
                        }
                    }
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * 规范化并清洗远程文件路径，严防路径穿越攻击 (Path Traversal)
     *
     * @param remotePath 原始路径
     * @return 经过净化后的路径
     */
    public static String cleanAndValidatePath(String remotePath) {
        return normalizeRemotePath(remotePath);
    }

    /**
     * 规范化并清洗远程文件路径，严防路径穿越攻击 (Path Traversal)
     *
     * @param remotePath 原始路径
     * @return 经过净化后的路径
     */
    public static String normalizeRemotePath(String remotePath) {
        return FileUtil.cleanPath(remotePath);
    }

    /**
     * 底层异常受控转译
     */
    private SshOperationException translateException(Exception e, String path) {
        if (e instanceof SshOperationException soe) {
            return soe;
        }
        if (e instanceof SftpException sftpEx) {
            int status = sftpEx.getStatus();
            if (status == SftpConstants.SSH_FX_NO_SUCH_FILE) {
                return new SshOperationException(SshResultCode.SFTP_FILE_NOT_FOUND,
                        "SFTP 远程文件或目录不存在 [" + path + "]: " + sftpEx.getMessage(), sftpEx);
            }
            if (status == SftpConstants.SSH_FX_PERMISSION_DENIED) {
                return new SshOperationException(SshResultCode.SFTP_PERMISSION_DENIED,
                        "SFTP 远程操作权限不足 [" + path + "]: " + sftpEx.getMessage(), sftpEx);
            }
            return new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                    "SFTP 操作执行失败 [" + path + "]: " + sftpEx.getMessage(), sftpEx);
        }
        return new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                "SFTP 操作异常 [" + path + "]: " + e.getMessage(), e);
    }

    /**
     * 四位一体目标描述符解析逻辑
     */
    public SshServerInfo resolveServerInfo(SshServerInfo explicitInfo) {
        if (explicitInfo != null) {
            return explicitInfo;
        }
        SshServerInfo contextInfo = SshContextHolder.getServerInfo();
        if (contextInfo != null) {
            return contextInfo;
        }
        String contextName = SshContextHolder.getServer();
        if (contextName != null && !contextName.isBlank()) {
            return clientRegistry.getServer(contextName);
        }
        return clientRegistry.getDefaultServer();
    }

    public SshServerInfo resolveServerInfo(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            return clientRegistry.getServer(serverName);
        }
        return resolveServerInfo((SshServerInfo) null);
    }
}
