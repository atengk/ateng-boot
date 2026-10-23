package io.github.atengk.boot.ssh.template;

import io.github.atengk.boot.ssh.context.SshContextHolder;
import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SftpFileInfo;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.pool.SshSessionPool;
import io.github.atengk.boot.ssh.properties.SshProperties;
import io.github.atengk.boot.ssh.registry.SshClientRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * SFTP 工业级文件传输门面 (SftpTemplate)
 * <p>
 * 1. 深度融通四位一体路由：透明支持缺省节点、YAML 命名注册表、线程上下文 (SshContextHolder) 与动态描述符 (SshServerInfo)；<br>
 * 2. 依托底座 SshSessionPool 建立瞬态借还模型（Stateless Borrowing），会话长连接池化复用，SFTP 子系统通道随用随关；<br>
 * 3. 严格践行原子上传铁律（.uploading.tmp 写入 + 原子覆盖 rename），配合 catch 异常补偿主动删除临时文件碎片；<br>
 * 4. 强力防范路径穿越提权（Path Traversal），上传多级嵌套路径自动递归创建父目录 (mkdir -p)；<br>
 * 5. 全面提供流式上传下载、字节数组操作、文件元数据 (SftpFileInfo)、存在性检查、删除与 POSIX 权限 chmod 治理。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Slf4j
public class SftpTemplate {

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
        upload(null, remotePath, inputStream);
    }

    /**
     * 上传输入流数据至指定远程服务器路径
     *
     * @param serverInfo  动态目标服务器描述符（为 null 则走上下文寻址）
     * @param remotePath  远程目标文件路径
     * @param inputStream 本地输入流
     */
    public void upload(SshServerInfo serverInfo, String remotePath, InputStream inputStream) {
        Assert.notNull(inputStream, "InputStream must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                ensureParentDirectoryExists(sftp, normalizedPath);
                doUploadStream(sftp, normalizedPath, inputStream);
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
        upload(null, remotePath, localFile);
    }

    /**
     * 上传本地磁盘文件至指定远程服务器路径
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param localFile  本地磁盘文件
     */
    public void upload(SshServerInfo serverInfo, String remotePath, File localFile) {
        Assert.notNull(localFile, "Local file must not be null");
        Assert.isTrue(localFile.exists() && localFile.isFile(), "Local file must exist and be a regular file: " + localFile.getAbsolutePath());
        try (InputStream in = new FileInputStream(localFile)) {
            upload(serverInfo, remotePath, in);
        } catch (IOException e) {
            throw new SshOperationException(SshResultCode.SFTP_TRANSFER_FAILED,
                    "读取本地文件失败 [" + localFile.getAbsolutePath() + "]: " + e.getMessage(), e);
        }
    }

    /**
     * 上传字节数组至远程路径（走当前上下文/缺省路由）
     *
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void upload(String remotePath, byte[] data) {
        upload(null, remotePath, data);
    }

    /**
     * 上传字节数组至指定远程服务器路径
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param data       数据字节数组
     */
    public void upload(SshServerInfo serverInfo, String remotePath, byte[] data) {
        Assert.notNull(data, "Data byte array must not be null");
        upload(serverInfo, remotePath, new ByteArrayInputStream(data));
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
        download(null, remotePath, outputStream);
    }

    /**
     * 下载远程文件并写入本地输出流
     *
     * @param serverInfo   动态目标服务器描述符
     * @param remotePath   远程目标文件路径
     * @param outputStream 目标输出流
     */
    public void download(SshServerInfo serverInfo, String remotePath, OutputStream outputStream) {
        Assert.notNull(outputStream, "OutputStream must not be null");
        String normalizedPath = normalizeRemotePath(remotePath);
        SshServerInfo target = resolveServerInfo(serverInfo);

        sessionPool.execute(target, session -> {
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
                 InputStream in = sftp.read(normalizedPath)) {
                copyStream(in, outputStream);
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
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
        downloadToFile(null, remotePath, localFile);
    }

    /**
     * 下载远程文件至本地磁盘文件
     *
     * @param serverInfo 动态目标服务器描述符
     * @param remotePath 远程目标文件路径
     * @param localFile  本地目标文件
     */
    public void downloadToFile(SshServerInfo serverInfo, String remotePath, File localFile) {
        Assert.notNull(localFile, "Local destination file must not be null");
        File parentDir = localFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(localFile)) {
            download(serverInfo, remotePath, out);
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
        download(serverInfo, remotePath, baos);
        return baos.toByteArray();
    }

    // ==========================================
    // 3. 文件属性、元数据与管理契约 (stat, exists, delete, chmod)
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
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
            try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
                createDirectoriesRecursively(sftp, normalizedPath);
                return null;
            } catch (Exception e) {
                throw translateException(e, normalizedPath);
            }
        });
    }

    // ==========================================
    // 内部私有支撑方法
    // ==========================================

    private void doUploadStream(SftpClient sftp, String remotePath, InputStream in) throws IOException {
        boolean atomic = properties.getSftp().isAtomicUploadEnabled();
        if (!atomic) {
            try (OutputStream out = sftp.write(remotePath, SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate)) {
                copyStream(in, out);
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
                copyStream(in, out);
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

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        int bufferSize = (int) properties.getSftp().getBufferSize().toBytes();
        if (bufferSize <= 0) {
            bufferSize = 32768;
        }
        byte[] buffer = new byte[bufferSize];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
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
    public static String normalizeRemotePath(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            throw new IllegalArgumentException("Remote path must not be null or blank");
        }
        String cleaned = StringUtils.cleanPath(remotePath.replace('\\', '/'));
        if (cleaned.contains("../") || cleaned.endsWith("/..") || cleaned.equals("..")) {
            throw new IllegalArgumentException("检测到高危路径穿越 (Path Traversal) 尝试: " + remotePath);
        }
        return cleaned;
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
