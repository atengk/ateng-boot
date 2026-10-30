package io.github.atengk.boot.ftp.template;

import io.github.atengk.boot.ftp.context.FtpContextHolder;
import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.listener.FtpProgressListener;
import io.github.atengk.boot.ftp.listener.FtpProgressThrottler;
import io.github.atengk.boot.ftp.listener.ProgressInputStream;
import io.github.atengk.boot.ftp.listener.ProgressOutputStream;
import io.github.atengk.boot.ftp.model.BatchTransferResult;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.model.TransferFailureItem;
import io.github.atengk.boot.ftp.pool.FtpClientCallback;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.registry.FtpClientRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.pool2.ObjectPool;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * FTP / FTPS 核心对称操作门面
 * <p>
 * 基于 Apache Commons Pool2 受管连接池与 Apache Commons Net 构建，
 * 对称覆盖流式/文件/字节/Resource 读写、原子上传守护与异常补偿、
 * 前置路径穿越安全清洗、级联多级目录自动创建（mkdir -p）、
 * RFC 959 协议线缆转码、RFC 3659 MLSD 机器目录高精度解析与受管流安全生命周期。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class FtpTemplate {

    @Getter
    private final ObjectPool<FTPClient> pool;

    @Getter
    private final FtpProperties properties;

    @Getter
    private final FtpClientRegistry registry;

    @Getter
    private final String serverName;

    /**
     * 单节点独立门面构造器
     *
     * @param pool       连接池
     * @param properties 配置属性
     */
    public FtpTemplate(ObjectPool<FTPClient> pool, FtpProperties properties) {
        this(pool, properties, null, FtpClientRegistry.DEFAULT_SERVER);
    }

    /**
     * 多节点受管门面构造器
     *
     * @param pool       默认连接池
     * @param properties 默认配置属性
     * @param registry   多节点注册表
     */
    public FtpTemplate(ObjectPool<FTPClient> pool, FtpProperties properties, FtpClientRegistry registry) {
        this(pool, properties, registry, FtpClientRegistry.DEFAULT_SERVER);
    }

    /**
     * 具名节点全量构造器
     *
     * @param pool       节点连接池
     * @param properties 节点配置属性
     * @param registry   多节点注册表
     * @param serverName 节点名称
     */
    public FtpTemplate(ObjectPool<FTPClient> pool, FtpProperties properties, FtpClientRegistry registry, String serverName) {
        Assert.notNull(pool, "ObjectPool must not be null");
        Assert.notNull(properties, "FtpProperties must not be null");
        this.pool = pool;
        this.properties = properties;
        this.registry = registry;
        this.serverName = serverName != null && !serverName.isBlank() ? serverName : FtpClientRegistry.DEFAULT_SERVER;
    }

    // ==========================================
    // 核心执行底座与逃逸舱 (Core Execution & Escape Hatch)
    // ==========================================

    /**
     * 在受管连接池借出的原生客户端会话中执行操作（逃逸舱）
     * <p>
     * 支持优先探测 {@link FtpContextHolder} 绑定的线程级目标服务节点进行动态路由，
     * 若未指定或为当前节点自身，则在当前节点连接池中安全执行。
     * </p>
     *
     * @param action 用户自定义回调
     * @param <T>    返回值类型
     * @return 执行结果
     */
    public <T> T execute(FtpClientCallback<T> action) {
        Assert.notNull(action, "FtpClientCallback must not be null");

        // 1. 动态切源路由探测：若线程上下文绑定了特定节点，且不是当前节点自身，则委托给注册表对应的节点执行
        String currentTarget = FtpContextHolder.getServer();
        if (currentTarget != null && !currentTarget.isBlank()
                && registry != null
                && !currentTarget.equalsIgnoreCase(this.serverName)) {
            return registry.getTemplate(currentTarget).execute(action);
        }

        FTPClient client = null;
        boolean invalidated = false;
        try {
            client = pool.borrowObject();
            return action.doInFtp(client);
        } catch (Exception e) {
            // 防御性连接治理：凡涉及底层 IO/网络异常，或连接已处于断开/不可用状态，强制标记失效物理销毁
            boolean connectionBroken = (client == null) || !client.isConnected() || !client.isAvailable();
            boolean hasIoError = (e instanceof IOException) || (e.getCause() instanceof IOException);
            if ((hasIoError || connectionBroken) && client != null) {
                try {
                    pool.invalidateObject(client);
                    invalidated = true;
                } catch (Exception ignored) {
                }
            }
            if (e instanceof FtpException fe) {
                throw fe;
            }
            throw new FtpException(FtpResultCode.OPERATION_FAILED, "执行 FTP 操作失败: " + e.getMessage(), e);
        } finally {
            if (client != null && !invalidated) {
                try {
                    pool.returnObject(client);
                } catch (Exception e) {
                    log.warn("归还 FTP 客户端至连接池失败: {}", e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // 连通性自验健康探针 (Connectivity Health Probes)
    // ==========================================

    /**
     * 探测当前/默认 FTP 服务节点的物理连通性
     * <p>
     * 自动借出连接发送 {@code NOOP} 指令验证 TCP 握手与会话鉴权状态，并在 finally 中确定性归还连接，
     * 毫秒级快速返回，全程零文件读写副作用。若连接异常或不可达，安全返回 false。
     * 支持优先感知 {@link FtpContextHolder} 绑定的线程级切源节点。
     * </p>
     *
     * @return 连通成功返回 true，否则返回 false
     */
    public boolean testConnection() {
        return testConnection(FtpContextHolder.getServer());
    }

    /**
     * 探测指定具名 FTP 服务节点的物理连通性
     *
     * @param targetServerName 目标服务节点名称（若为 null 或空则使用当前节点）
     * @return 连通成功返回 true，否则返回 false
     */
    public boolean testConnection(String targetServerName) {
        if (targetServerName != null && !targetServerName.isBlank()
                && registry != null
                && !targetServerName.equalsIgnoreCase(this.serverName)) {
            try {
                return registry.getTemplate(targetServerName).testConnection();
            } catch (Exception e) {
                log.debug("探测指定 FTP 节点 [{}] 连通性失败: {}", targetServerName, e.getMessage());
                return false;
            }
        }
        return testConnectionInternal();
    }

    /**
     * 探测当前/默认 FTP 服务节点的物理连通性，失败时抛出语义化异常
     *
     * @throws FtpException 当连接不可用、心跳失败或鉴权异常时抛出 CONNECT_FAILED 异常
     */
    public void testConnectionOrThrow() {
        testConnectionOrThrow(FtpContextHolder.getServer());
    }

    /**
     * 探测指定具名 FTP 服务节点的物理连通性，失败时抛出语义化异常
     *
     * @param targetServerName 目标服务节点名称（若为 null 或空则使用当前节点）
     * @throws FtpException 当连接不可用、心跳失败或鉴权异常时抛出 CONNECT_FAILED 异常
     */
    public void testConnectionOrThrow(String targetServerName) {
        if (targetServerName != null && !targetServerName.isBlank()
                && registry != null
                && !targetServerName.equalsIgnoreCase(this.serverName)) {
            registry.getTemplate(targetServerName).testConnectionOrThrow();
            return;
        }
        testConnectionOrThrowInternal();
    }

    private boolean testConnectionInternal() {
        FTPClient client = null;
        boolean invalidated = false;
        try {
            client = pool.borrowObject();
            if (client == null || !client.isConnected() || !client.isAvailable()) {
                return false;
            }
            return client.sendNoOp();
        } catch (Exception e) {
            log.debug("FTP 连通性探测失败: {}", e.getMessage());
            if (client != null) {
                try {
                    pool.invalidateObject(client);
                    invalidated = true;
                } catch (Exception ignored) {
                }
            }
            return false;
        } finally {
            if (client != null && !invalidated) {
                try {
                    pool.returnObject(client);
                } catch (Exception e) {
                    log.warn("归还 FTP 客户端至连接池失败: {}", e.getMessage());
                }
            }
        }
    }

    private void testConnectionOrThrowInternal() {
        FTPClient client = null;
        boolean invalidated = false;
        try {
            client = pool.borrowObject();
            if (client == null || !client.isConnected() || !client.isAvailable()) {
                throw new FtpException(FtpResultCode.CONNECT_FAILED,
                        "FTP 服务节点 [" + this.serverName + "] 连接不可用或已断开");
            }
            boolean ok = client.sendNoOp();
            if (!ok) {
                throw new FtpException(FtpResultCode.CONNECT_FAILED,
                        "FTP 服务节点 [" + this.serverName + "] NOOP 心跳检测失败，响应码: " + client.getReplyCode());
            }
        } catch (Exception e) {
            if (client != null) {
                try {
                    pool.invalidateObject(client);
                    invalidated = true;
                } catch (Exception ignored) {
                }
            }
            if (e instanceof FtpException fe) {
                throw fe;
            }
            throw new FtpException(FtpResultCode.CONNECT_FAILED,
                    "FTP 服务节点 [" + this.serverName + "] 连通性探测失败: " + e.getMessage(), e);
        } finally {
            if (client != null && !invalidated) {
                try {
                    pool.returnObject(client);
                } catch (Exception e) {
                    log.warn("归还 FTP 客户端至连接池失败: {}", e.getMessage());
                }
            }
        }
    }

    // ==========================================
    // 上传对称操作 (Symmetric Upload & Atomic Guard)
    // ==========================================

    /**
     * 上传输入流到远程路径（支持原子上传守护与父目录自动递归创建）
     *
     * @param remotePath 远程文件目标绝对或相对路径
     * @param in         待上传的数据输入流
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, InputStream in) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(in, "InputStream must not be null");
        String safePath = cleanAndValidatePath(remotePath);

        boolean isAtomic = properties.isAtomicUpload();
        String suffix = properties.getTempFileSuffix() != null ? properties.getTempFileSuffix() : ".tmp";
        String uploadPath = isAtomic ? safePath + suffix : safePath;
        String wireUploadPath = toWirePath(uploadPath);
        String wireTargetPath = toWirePath(safePath);

        try {
            return execute(client -> {
                // 1. 父级多级目录自动递归创建 (mkdir -p)
                if (properties.isAutoCreateDirectories()) {
                    String parentDir = getParentPath(safePath);
                    if (parentDir != null && !parentDir.isBlank() && !"/".equals(parentDir)) {
                        makeDirectoriesInternal(client, parentDir);
                    }
                }

                boolean success;
                try {
                    success = client.storeFile(wireUploadPath, in);
                } catch (Exception e) {
                    throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                            "FTP 文件写入异常: " + safePath + ", 原因: " + e.getMessage(), e);
                }
                if (!success) {
                    int reply = client.getReplyCode();
                    throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                            "FTP 文件上传失败: " + safePath + ", 服务端返回码: " + reply);
                }
                if (isAtomic) {
                    boolean renamed = client.rename(wireUploadPath, wireTargetPath);
                    if (!renamed) {
                        // 部分服务端在目标文件已存在时不予直接覆盖，尝试先删除目标文件再原子 rename
                        try {
                            client.deleteFile(wireTargetPath);
                            renamed = client.rename(wireUploadPath, wireTargetPath);
                        } catch (Exception ignored) {
                        }
                    }
                    if (!renamed) {
                        int reply = client.getReplyCode();
                        throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                                "FTP 原子重命名失败: " + uploadPath + " -> " + safePath + ", 服务端返回码: " + reply);
                    }
                }
                return true;
            });
        } catch (Exception e) {
            // 异常补偿机制：此时外层 execute 已经退出并完全释放了借出的连接，绝无嵌套持有连接自死锁风险
            if (isAtomic) {
                try {
                    execute(cleanClient -> {
                        cleanClient.deleteFile(wireUploadPath);
                        return true;
                    });
                    log.debug("FTP 原子上传异常中断，已自动物理清理临时文件: {}", uploadPath);
                } catch (Exception ex) {
                    log.warn("FTP 原子上传异常清理临时文件失败: {}, 原因: {}", uploadPath, ex.getMessage());
                }
            }
            if (e instanceof FtpException fe) {
                throw fe;
            }
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "FTP 上传文件异常: " + safePath + ", 原因: " + e.getMessage(), e);
        }
    }

    /**
     * 上传本地文件到远程路径
     *
     * @param remotePath 远程文件目标路径
     * @param localFile  本地磁盘文件
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, File localFile) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(localFile, "localFile must not be null");
        if (!localFile.exists() || !localFile.isFile()) {
            throw new FtpException(FtpResultCode.INVALID_PATH, "本地待上传文件不存在或不是标准文件: " + localFile.getAbsolutePath());
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(localFile))) {
            return upload(remotePath, in);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "读取本地待上传文件失败: " + localFile.getAbsolutePath(), e);
        }
    }

    /**
     * 上传内存字节数组到远程路径
     *
     * @param remotePath 远程文件目标路径
     * @param bytes      文件字节数组
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, byte[] bytes) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(bytes, "bytes must not be null");
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return upload(remotePath, in);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "构建字节输入流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 上传 Spring 原生 Resource 到远程路径
     *
     * @param remotePath 远程文件目标路径
     * @param resource   Spring 资源对象
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, Resource resource) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(resource, "Resource must not be null");
        try (InputStream in = resource.getInputStream()) {
            return upload(remotePath, in);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "读取 Spring Resource 失败: " + resource.getDescription(), e);
        }
    }

    /**
     * 上传输入流到远程路径并挂载流式进度监听器
     *
     * @param remotePath       远程文件目标路径
     * @param in               待上传的数据输入流
     * @param progressListener 传输进度监听回调（可为 null）
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, InputStream in, FtpProgressListener progressListener) {
        return upload(remotePath, in, -1L, progressListener);
    }

    /**
     * 上传输入流到远程路径并挂载流式进度监听器（显式指定总大小）
     *
     * @param remotePath       远程文件目标路径
     * @param in               待上传的数据输入流
     * @param totalBytes       数据流已知总大小（未知则传 -1）
     * @param progressListener 传输进度监听回调（可为 null）
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, InputStream in, long totalBytes, FtpProgressListener progressListener) {
        if (progressListener == null) {
            return upload(remotePath, in);
        }
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(in, "InputStream must not be null");

        long effectiveTotal = totalBytes;
        if (effectiveTotal <= 0) {
            try {
                int avail = in.available();
                if (avail > 0) {
                    effectiveTotal = avail;
                }
            } catch (Exception ignored) {
            }
        }

        FtpProgressThrottler throttler = new FtpProgressThrottler(progressListener, effectiveTotal);
        ProgressInputStream pin = new ProgressInputStream(in, throttler);
        boolean success = upload(remotePath, (InputStream) pin);
        if (success) {
            throttler.finish();
        }
        return success;
    }

    /**
     * 上传本地文件到远程路径并挂载流式进度监听器
     *
     * @param remotePath       远程文件目标路径
     * @param localFile        本地待上传文件
     * @param progressListener 传输进度监听回调（可为 null）
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, File localFile, FtpProgressListener progressListener) {
        if (progressListener == null) {
            return upload(remotePath, localFile);
        }
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(localFile, "localFile must not be null");
        if (!localFile.exists() || !localFile.isFile()) {
            throw new FtpException(FtpResultCode.INVALID_PATH, "本地待上传文件不存在或不是标准文件: " + localFile.getAbsolutePath());
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(localFile))) {
            return upload(remotePath, in, localFile.length(), progressListener);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "读取本地待上传文件失败: " + localFile.getAbsolutePath(), e);
        }
    }

    /**
     * 便捷写入纯文本内容（默认使用 UTF-8 字符集）
     *
     * @param remotePath 远程文件目标路径
     * @param content    待写入的文本内容
     * @return 写入成功返回 true
     */
    public boolean writeString(String remotePath, String content) {
        return writeString(remotePath, content, StandardCharsets.UTF_8);
    }

    /**
     * 便捷写入纯文本内容（指定字符集）
     *
     * @param remotePath 远程文件目标路径
     * @param content    待写入的文本内容
     * @param charset    字符编码
     * @return 写入成功返回 true
     */
    public boolean writeString(String remotePath, String content, Charset charset) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(content, "content must not be null");
        Charset targetCharset = charset != null ? charset : StandardCharsets.UTF_8;
        return upload(remotePath, content.getBytes(targetCharset));
    }

    // ==========================================
    // 增量追加操作 (Incremental Append)
    // ==========================================

    /**
     * 向远程文件末尾追加输入流数据（RFC 959 APPE 指令）
     *
     * @param remotePath 远程文件路径
     * @param in         待追加的数据输入流
     * @return 追加成功返回 true
     */
    public boolean append(String remotePath, InputStream in) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(in, "InputStream must not be null");
        String safePath = cleanAndValidatePath(remotePath);
        String wirePath = toWirePath(safePath);

        return execute(client -> {
            if (properties.isAutoCreateDirectories()) {
                String parentDir = getParentPath(safePath);
                if (parentDir != null && !parentDir.isBlank() && !"/".equals(parentDir)) {
                    makeDirectoriesInternal(client, parentDir);
                }
            }
            boolean success;
            try {
                success = client.appendFile(wirePath, in);
            } catch (Exception e) {
                throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                        "FTP 文件追加写入异常: " + safePath + ", 原因: " + e.getMessage(), e);
            }
            if (!success) {
                int reply = client.getReplyCode();
                throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                        "FTP 文件追加失败: " + safePath + ", 服务端返回码: " + reply);
            }
            return true;
        });
    }

    /**
     * 向远程文件末尾追加字节数组
     *
     * @param remotePath 远程文件路径
     * @param bytes      待追加的字节数据
     * @return 追加成功返回 true
     */
    public boolean append(String remotePath, byte[] bytes) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(bytes, "bytes must not be null");
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            return append(remotePath, in);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.UPLOAD_FAILED, "FTP 字节数组追加失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向远程文件末尾追加纯文本内容（使用默认 UTF-8 编码）
     *
     * @param remotePath 远程文件路径
     * @param content    待追加的文本内容
     * @return 追加成功返回 true
     */
    public boolean append(String remotePath, String content) {
        Charset charset = properties.getControlEncoding() != null
                ? Charset.forName(properties.getControlEncoding())
                : StandardCharsets.UTF_8;
        return append(remotePath, content, charset);
    }

    /**
     * 向远程文件末尾追加纯文本内容（指定字符编码）
     *
     * @param remotePath 远程文件路径
     * @param content    待追加的文本内容
     * @param charset    字符编码
     * @return 追加成功返回 true
     */
    public boolean append(String remotePath, String content, Charset charset) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(content, "content must not be null");
        Assert.notNull(charset, "charset must not be null");
        return append(remotePath, content.getBytes(charset));
    }

    // ==========================================
    // 下载对称操作 (Symmetric Download)
    // ==========================================

    /**
     * 下载远程文件到指定输出流
     *
     * @param remotePath 远程文件路径
     * @param out        目标输出流
     * @return 下载成功返回 true
     */
    public boolean download(String remotePath, OutputStream out) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(out, "OutputStream must not be null");
        String safePath = cleanAndValidatePath(remotePath);
        return execute(client -> {
            String wirePath = toWirePath(safePath);
            boolean success = client.retrieveFile(wirePath, out);
            if (!success) {
                int reply = client.getReplyCode();
                throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                        "FTP 文件下载失败: " + safePath + ", 服务端返回码: " + reply);
            }
            return true;
        });
    }

    /**
     * 下载远程文件并保存到本地磁盘文件
     *
     * @param remotePath 远程文件路径
     * @param localFile  本地目标文件
     * @return 下载成功返回 true
     */
    public boolean download(String remotePath, File localFile) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(localFile, "localFile must not be null");
        File parent = localFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(localFile))) {
            return download(remotePath, out);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "写入本地下载文件失败: " + localFile.getAbsolutePath(), e);
        }
    }

    /**
     * 下载远程文件到指定输出流并挂载流式进度监听器
     *
     * @param remotePath       远程文件路径
     * @param out              目标输出流
     * @param progressListener 传输进度监听回调（可为 null）
     * @return 下载成功返回 true
     */
    public boolean download(String remotePath, OutputStream out, FtpProgressListener progressListener) {
        if (progressListener == null) {
            return download(remotePath, out);
        }
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(out, "OutputStream must not be null");

        long totalBytes = -1L;
        try {
            FtpFileInfo info = getFileInfo(remotePath);
            if (info != null && info.getSize() > 0) {
                totalBytes = info.getSize();
            }
        } catch (Exception ignored) {
        }

        FtpProgressThrottler throttler = new FtpProgressThrottler(progressListener, totalBytes);
        ProgressOutputStream pout = new ProgressOutputStream(out, throttler);
        boolean success = download(remotePath, (OutputStream) pout);
        if (success) {
            throttler.finish();
        }
        return success;
    }

    /**
     * 下载远程文件并保存到本地磁盘文件并挂载流式进度监听器
     *
     * @param remotePath       远程文件路径
     * @param localFile        本地目标文件
     * @param progressListener 传输进度监听回调（可为 null）
     * @return 下载成功返回 true
     */
    public boolean download(String remotePath, File localFile, FtpProgressListener progressListener) {
        if (progressListener == null) {
            return download(remotePath, localFile);
        }
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(localFile, "localFile must not be null");
        File parent = localFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(localFile))) {
            return download(remotePath, out, progressListener);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "写入本地下载文件失败: " + localFile.getAbsolutePath(), e);
        }
    }

    /**
     * 下载远程文件为内存字节数组（内置容量防爆守护）
     *
     * @param remotePath 远程文件路径
     * @return 文件字节数组
     */
    public byte[] downloadToBytes(String remotePath) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        String safePath = cleanAndValidatePath(remotePath);
        long maxBytes = properties.getMaxDownloadBytes() != null
                ? properties.getMaxDownloadBytes().toBytes()
                : DataSize.ofMegabytes(50).toBytes();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        execute(client -> {
            String wirePath = toWirePath(safePath);
            try (OutputStream limitedOut = new OutputStream() {
                private long count = 0;

                private void checkLimit(long delta) {
                    count += delta;
                    if (count > maxBytes) {
                        throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                                "FTP 下载文件大小超过内存安全阈值: " + maxBytes + " bytes");
                    }
                }

                @Override
                public void write(int b) throws IOException {
                    checkLimit(1);
                    out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    checkLimit(len);
                    out.write(b, off, len);
                }
            }) {
                boolean success = client.retrieveFile(wirePath, limitedOut);
                if (!success) {
                    int reply = client.getReplyCode();
                    throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                            "FTP 文件下载失败: " + safePath + ", 服务端返回码: " + reply);
                }
            }
            return true;
        });
        return out.toByteArray();
    }

    /**
     * 便捷读取远程文件为纯文本字符串（默认使用 UTF-8 字符集）
     *
     * @param remotePath 远程文件路径
     * @return 文本内容字符串
     */
    public String readString(String remotePath) {
        return readString(remotePath, StandardCharsets.UTF_8);
    }

    /**
     * 便捷读取远程文件为纯文本字符串（指定字符集）
     *
     * @param remotePath 远程文件路径
     * @param charset    字符编码
     * @return 文本内容字符串
     */
    public String readString(String remotePath, Charset charset) {
        byte[] bytes = downloadToBytes(remotePath);
        Charset targetCharset = charset != null ? charset : StandardCharsets.UTF_8;
        return new String(bytes, targetCharset);
    }

    /**
     * 受管流安全消费回调下载（强制确定性闭合流与会话）
     * <p>
     * 绝不直接返回裸 InputStream，彻底杜绝数据通道悬挂与连接池污染。
     * </p>
     *
     * @param remotePath 远程文件路径
     * @param consumer   数据流消费者回调
     */
    public void downloadStream(String remotePath, Consumer<InputStream> consumer) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(consumer, "Consumer must not be null");
        String safePath = cleanAndValidatePath(remotePath);
        execute(client -> {
            String wirePath = toWirePath(safePath);
            InputStream in = null;
            boolean commandCompleted = false;
            try {
                in = client.retrieveFileStream(wirePath);
                if (in == null) {
                    int reply = client.getReplyCode();
                    throw new FtpException(FtpResultCode.FILE_NOT_FOUND,
                            "获取远程文件流失败 (文件不存在或权限不足): " + safePath + ", 服务端返回码: " + reply);
                }
                consumer.accept(in);
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                    try {
                        commandCompleted = client.completePendingCommand();
                    } catch (Exception e) {
                        log.debug("FTP completePendingCommand 闭合异常: {}", e.getMessage());
                    }
                    if (!commandCompleted) {
                        // 若挂起指令未能正常闭合，连接协议状态已不可信，主动断开以触发连接池销毁防卫
                        try {
                            client.disconnect();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (!commandCompleted) {
                log.warn("FTP 挂起数据读取指令未正常结束: {}", safePath);
                throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "完成 FTP 数据读取挂起指令失败: " + safePath);
            }
            return null;
        });
    }

    // ==========================================
    // 高级文件与目录管理 (Advanced File System Operations)
    // ==========================================

    /**
     * 判断远程文件或目录是否存在
     *
     * @param remotePath 远程文件或目录路径
     * @return 存在返回 true，否则返回 false
     */
    public boolean exists(String remotePath) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        String safePath = cleanAndValidatePath(remotePath);
        return execute(client -> {
            String wirePath = toWirePath(safePath);
            // 1. 优先尝试 RFC 3659 mlistFile
            try {
                FTPFile ftpFile = client.mlistFile(wirePath);
                if (ftpFile != null) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            // 2. 尝试 listFiles
            try {
                FTPFile[] files = client.listFiles(wirePath);
                if (files != null && files.length > 0) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            // 3. 检查是否为有效目录 (尝试切换工作目录并复位)
            String currentDir = client.printWorkingDirectory();
            try {
                if (client.changeWorkingDirectory(wirePath)) {
                    if (currentDir != null) {
                        client.changeWorkingDirectory(currentDir);
                    }
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        });
    }

    /**
     * 获取远程单文件或目录的标准强类型元数据
     *
     * @param remotePath 远程路径
     * @return 文件元数据对象，若不存在保底返回 null
     */
    public FtpFileInfo getFileInfo(String remotePath) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        String safePath = cleanAndValidatePath(remotePath);
        return execute(client -> {
            String wirePath = toWirePath(safePath);
            FTPFile ftpFile = null;
            // 1. 优先尝试 RFC 3659 mlistFile
            try {
                ftpFile = client.mlistFile(wirePath);
            } catch (Exception ignored) {
            }
            // 2. 平滑降级：优先从父目录枚举匹配自身元数据（彻底杜绝将目录下首个子文件误当目录自身的缺陷）
            if (ftpFile == null) {
                if ("/".equals(safePath) || safePath.isEmpty()) {
                    return FtpFileInfo.builder()
                            .name("/")
                            .path("/")
                            .size(0)
                            .directory(true)
                            .file(false)
                            .build();
                }
                String parentDir = getParentPath(safePath);
                String targetName = getFileName(safePath);
                try {
                    String lookupDir = (parentDir != null && !parentDir.isBlank()) ? parentDir : "/";
                    FTPFile[] siblings = client.listFiles(toWirePath(lookupDir));
                    if (siblings != null) {
                        for (FTPFile sibling : siblings) {
                            if (sibling == null) {
                                continue;
                            }
                            String decodedSibling = fromWirePath(sibling.getName());
                            if (targetName.equals(decodedSibling) || targetName.equals(sibling.getName())) {
                                ftpFile = sibling;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                // 3. 若从父级未定位到，尝试直接 listFiles 探测单文件自身
                if (ftpFile == null) {
                    try {
                        FTPFile[] files = client.listFiles(wirePath);
                        if (files != null && files.length == 1) {
                            String firstDecoded = fromWirePath(files[0].getName());
                            if (targetName.equals(firstDecoded) || targetName.equals(files[0].getName())) {
                                ftpFile = files[0];
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            if (ftpFile == null) {
                return null;
            }
            String rawName = ftpFile.getName();
            String decodedName = fromWirePath(rawName);
            String name = decodedName != null && decodedName.contains("/")
                    ? decodedName.substring(decodedName.lastIndexOf('/') + 1)
                    : decodedName;
            if (name == null || name.isBlank()) {
                name = getFileName(safePath);
            }
            return FtpFileInfo.builder()
                    .name(name)
                    .path(safePath)
                    .size(ftpFile.getSize())
                    .isDirectory(ftpFile.isDirectory())
                    .isFile(ftpFile.isFile())
                    .lastModified(ftpFile.getTimestampInstant())
                    .build();
        });
    }

    /**
     * 获取指定目录下的所有文件及子目录列表（优先尝试 RFC 3659 MLSD 机器目录探测）
     *
     * @param remoteDir 远程目录路径（为空或 "/" 默认表示根目录）
     * @return 强类型文件元数据列表（无结果保底返回空列表，绝不返回 null）
     */
    public List<FtpFileInfo> listFiles(String remoteDir) {
        return listFiles(remoteDir, null);
    }

    /**
     * 按条件过滤获取指定目录下的文件及子目录列表
     *
     * @param remoteDir 远程目录路径
     * @param filter    断言过滤条件
     * @return 强类型文件元数据列表
     */
    public List<FtpFileInfo> listFiles(String remoteDir, Predicate<FtpFileInfo> filter) {
        return execute(client -> {
            String targetDir = remoteDir != null && !remoteDir.isBlank() ? cleanAndValidatePath(remoteDir) : "/";
            String wireDir = toWirePath(targetDir);
            FTPFile[] ftpFiles = null;

            // 1. 优先尝试 RFC 3659 MLSD 机器级精确目录解析
            try {
                ftpFiles = client.mlistDir(wireDir);
            } catch (Exception e) {
                log.debug("RFC 3659 mlistDir 不受支持或失败，将平滑回退传统 LIST: {}", e.getMessage());
            }

            // 2. 平滑降级为传统 listFiles（若 mlistDir 未返回文件或不受支持）
            if (ftpFiles == null || ftpFiles.length == 0) {
                ftpFiles = client.listFiles(wireDir);
            }
            if (ftpFiles == null || ftpFiles.length == 0) {
                return Collections.emptyList();
            }

            List<FtpFileInfo> result = new ArrayList<>(ftpFiles.length);
            for (FTPFile ftpFile : ftpFiles) {
                if (ftpFile == null) {
                    continue;
                }
                String rawName = ftpFile.getName();
                if (".".equals(rawName) || "..".equals(rawName)) {
                    continue;
                }
                String decodedName = fromWirePath(rawName);
                String fullPath = normalizePath(targetDir, decodedName);
                Instant lastModified = ftpFile.getTimestampInstant();

                FtpFileInfo info = FtpFileInfo.builder()
                        .name(decodedName)
                        .path(fullPath)
                        .size(ftpFile.getSize())
                        .isDirectory(ftpFile.isDirectory())
                        .isFile(ftpFile.isFile())
                        .lastModified(lastModified)
                        .build();

                if (filter == null || filter.test(info)) {
                    result.add(info);
                }
            }
            return result;
        });
    }

    /**
     * 列出指定目录下的所有子目录绝对路径列表
     *
     * @param remoteDir 远程目录路径
     * @return 子目录路径列表
     */
    public List<String> listDirectories(String remoteDir) {
        return listFiles(remoteDir, FtpFileInfo::isDirectory)
                .stream()
                .map(FtpFileInfo::getPath)
                .toList();
    }

    /**
     * 级联递归创建多级目录（mkdir -p 对称能力）
     *
     * @param remoteDir 远程目录路径
     * @return 创建成功或目录已存在返回 true
     */
    public boolean makeDirectories(String remoteDir) {
        Assert.hasText(remoteDir, "remoteDir must not be blank");
        String safeDir = cleanAndValidatePath(remoteDir);
        return execute(client -> makeDirectoriesInternal(client, safeDir));
    }

    /**
     * 删除远程单个文件
     *
     * @param remotePath 远程文件路径
     * @return 删除成功返回 true，文件不存在或删除失败返回 false
     */
    public boolean deleteFile(String remotePath) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        String safePath = cleanAndValidatePath(remotePath);
        return execute(client -> {
            String wirePath = toWirePath(safePath);
            boolean success = client.deleteFile(wirePath);
            if (!success) {
                int reply = client.getReplyCode();
                log.debug("FTP 删除文件未成功: {}, 服务端返回码: {}", safePath, reply);
            }
            return success;
        });
    }

    /**
     * 递归删除远程目录及其所有子文件与子目录
     *
     * @param remoteDir 远程目录路径
     * @return 删除成功返回 true
     */
    public boolean deleteDirectory(String remoteDir) {
        return deleteDirectory(remoteDir, true);
    }

    /**
     * 删除远程目录（支持非空目录递归清空）
     *
     * @param remoteDir 远程目录路径
     * @param recursive 是否递归删除目录下所有文件与子目录
     * @return 删除成功返回 true
     */
    public boolean deleteDirectory(String remoteDir, boolean recursive) {
        Assert.hasText(remoteDir, "remoteDir must not be blank");
        String safeDir = cleanAndValidatePath(remoteDir);
        return execute(client -> deleteDirectoryInternal(client, safeDir, recursive));
    }

    /**
     * 重命名文件或目录
     *
     * @param fromPath 原文件或目录路径
     * @param toPath   目标文件或目录路径
     * @return 重命名成功返回 true
     */
    public boolean rename(String fromPath, String toPath) {
        Assert.hasText(fromPath, "fromPath must not be blank");
        Assert.hasText(toPath, "toPath must not be blank");
        String safeFrom = cleanAndValidatePath(fromPath);
        String safeTo = cleanAndValidatePath(toPath);

        return execute(client -> {
            // 若开启自动建目录，且目标父目录不存在时先递归创建
            if (properties.isAutoCreateDirectories()) {
                String parent = getParentPath(safeTo);
                if (parent != null && !parent.isBlank() && !"/".equals(parent)) {
                    makeDirectoriesInternal(client, parent);
                }
            }
            String wireFrom = toWirePath(safeFrom);
            String wireTo = toWirePath(safeTo);
            boolean success = client.rename(wireFrom, wireTo);
            if (!success) {
                int reply = client.getReplyCode();
                log.warn("FTP 重命名失败: {} -> {}, 服务端返回码: {}", safeFrom, safeTo, reply);
            }
            return success;
        });
    }

    /**
     * 移动文件或目录到指定目标目录下
     *
     * @param fromPath 待移动的源文件或目录路径
     * @param toDir    目标父目录路径
     * @return 移动成功返回 true
     */
    public boolean move(String fromPath, String toDir) {
        Assert.hasText(fromPath, "fromPath must not be blank");
        Assert.hasText(toDir, "toDir must not be blank");
        String safeFrom = cleanAndValidatePath(fromPath);
        String safeDir = cleanAndValidatePath(toDir);
        String fileName = getFileName(safeFrom);
        String targetPath = normalizePath(safeDir, fileName);
        return rename(safeFrom, targetPath);
    }

    /**
     * 复制远程 FTP 文件到新路径（受管管道流单文件原子对拷）
     * <p>
     * 严格限定为单文件复制，目标端写入采用原子上传守护（.tmp + rename），
     * 源文件不存在时立即抛出 {@link FtpResultCode#FILE_NOT_FOUND} 异常。
     * </p>
     *
     * @param fromPath 源文件路径
     * @param toPath   目标文件路径
     * @return 复制成功返回 true
     */
    public boolean copy(String fromPath, String toPath) {
        Assert.hasText(fromPath, "fromPath must not be blank");
        Assert.hasText(toPath, "toPath must not be blank");
        String safeFrom = cleanAndValidatePath(fromPath);
        String safeTo = cleanAndValidatePath(toPath);

        if (safeFrom.equals(safeTo)) {
            throw new IllegalArgumentException("FTP 复制源路径与目标路径不能相同: " + safeFrom);
        }

        // 1. 验证源文件存在性
        if (!exists(safeFrom)) {
            throw new FtpException(FtpResultCode.FILE_NOT_FOUND, "FTP 源文件不存在，无法执行复制: " + safeFrom);
        }

        // 2. 验证源路径不能是目录 (优先探测 CWD 与 FtpFileInfo)
        boolean isDir = execute(client -> {
            String wireFrom = toWirePath(safeFrom);
            String currentDir = client.printWorkingDirectory();
            try {
                if (client.changeWorkingDirectory(wireFrom)) {
                    if (currentDir != null) {
                        client.changeWorkingDirectory(currentDir);
                    }
                    return true;
                }
            } catch (Exception ignored) {
            }
            return false;
        });
        if (!isDir) {
            FtpFileInfo fileInfo = getFileInfo(safeFrom);
            if (fileInfo != null && fileInfo.isDirectory()) {
                isDir = true;
            }
        }
        if (isDir) {
            throw new FtpException(FtpResultCode.INVALID_PATH, "FTP 复制仅支持单文件，不支持目录: " + safeFrom);
        }

        // 3. 借用受管临时文件进行流式中转对拷（兼顾内存防爆与连接池防死锁）
        File tempBufferFile = null;
        try {
            tempBufferFile = File.createTempFile("ateng_ftp_copy_", ".tmp");
            boolean downloaded = download(safeFrom, tempBufferFile);
            if (!downloaded) {
                throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "FTP 复制文件下载源文件失败: " + safeFrom);
            }
            return upload(safeTo, tempBufferFile);
        } catch (IOException e) {
            throw new FtpException(FtpResultCode.OPERATION_FAILED, "FTP 复制文件创建本地缓冲临时文件失败: " + e.getMessage(), e);
        } finally {
            if (tempBufferFile != null && tempBufferFile.exists()) {
                try {
                    Files.deleteIfExists(tempBufferFile.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    // ==========================================
    // 批量目录同步与容错引擎 (Batch Directory Sync & Fail-Safe Engine)
    // ==========================================

    private static final int MAX_DIRECTORY_DEPTH = 32;

    /**
     * 批量递归上传本地目录至 FTP 远端目录（默认 Fail-Safe 容错模式）
     *
     * @param localDir  本地待上传的根目录
     * @param remoteDir 远程目标目录路径
     * @return 强类型批量传输聚合回执
     */
    public BatchTransferResult uploadDirectory(File localDir, String remoteDir) {
        return uploadDirectory(localDir, remoteDir, null, false);
    }

    /**
     * 批量递归上传本地目录至 FTP 远端目录（支持自定义断言过滤与 Fail-Fast / Fail-Safe 双模机制）
     *
     * @param localDir  本地待上传的根目录
     * @param remoteDir 远程目标目录路径
     * @param filter    文件断言过滤器（为 null 时传输全部非系统隐性文件）
     * @param failFast  是否开启快速失败（true 表示遇错立即熔断抛出异常；false 表示 Fail-Safe 记录错误并继续传输）
     * @return 强类型批量传输聚合回执
     */
    public BatchTransferResult uploadDirectory(File localDir, String remoteDir, Predicate<File> filter, boolean failFast) {
        Assert.notNull(localDir, "localDir must not be null");
        Assert.isTrue(localDir.exists(), "localDir does not exist: " + localDir.getAbsolutePath());
        Assert.isTrue(localDir.isDirectory(), "localDir must be a directory: " + localDir.getAbsolutePath());
        Assert.hasText(remoteDir, "remoteDir must not be blank");

        String currentTarget = FtpContextHolder.getServer();
        if (currentTarget != null && !currentTarget.isBlank()
                && registry != null
                && !currentTarget.equalsIgnoreCase(this.serverName)) {
            return registry.getTemplate(currentTarget).uploadDirectory(localDir, remoteDir, filter, failFast);
        }

        String safeRemoteDir = cleanAndValidatePath(remoteDir);
        long startTime = System.nanoTime();
        log.debug("开始批量递归上传目录: {} -> {} (failFast={})", localDir.getAbsolutePath(), safeRemoteDir, failFast);

        BatchTransferResult result = new BatchTransferResult();
        try {
            uploadDirectoryInternal(localDir, localDir, safeRemoteDir, filter, failFast, result, 0);
        } finally {
            result.setDuration(Duration.ofNanos(System.nanoTime() - startTime));
            log.debug("完成批量递归上传目录: {} -> {}, 耗时: {}ms, 总文件: {}, 成功: {}, 失败: {}, 跳过: {}, 传输字节: {}",
                    localDir.getAbsolutePath(), safeRemoteDir, result.getDuration().toMillis(),
                    result.getTotalFiles(), result.getSuccessCount(), result.getFailureCount(),
                    result.getSkippedCount(), result.getTransferredBytes());
        }
        return result;
    }

    /**
     * 批量递归下载 FTP 远端目录至本地磁盘目录（默认 Fail-Safe 容错模式）
     *
     * @param remoteDir 远程待下载的根目录路径
     * @param localDir  本地存储目标根目录
     * @return 强类型批量传输聚合回执
     */
    public BatchTransferResult downloadDirectory(String remoteDir, File localDir) {
        return downloadDirectory(remoteDir, localDir, null, false);
    }

    /**
     * 批量递归下载 FTP 远端目录至本地磁盘目录（支持自定义断言过滤与 Fail-Fast / Fail-Safe 双模机制）
     *
     * @param remoteDir 远程待下载的根目录路径
     * @param localDir  本地存储目标根目录
     * @param filter    文件元数据断言过滤器（为 null 时下载全部非系统隐性文件）
     * @param failFast  是否开启快速失败（true 表示遇错立即熔断抛出异常；false 表示 Fail-Safe 记录错误并继续传输）
     * @return 强类型批量传输聚合回执
     */
    public BatchTransferResult downloadDirectory(String remoteDir, File localDir, Predicate<FtpFileInfo> filter, boolean failFast) {
        Assert.hasText(remoteDir, "remoteDir must not be blank");
        Assert.notNull(localDir, "localDir must not be null");
        if (localDir.exists()) {
            Assert.isTrue(localDir.isDirectory(), "localDir must be a directory: " + localDir.getAbsolutePath());
        } else {
            localDir.mkdirs();
        }

        String currentTarget = FtpContextHolder.getServer();
        if (currentTarget != null && !currentTarget.isBlank()
                && registry != null
                && !currentTarget.equalsIgnoreCase(this.serverName)) {
            return registry.getTemplate(currentTarget).downloadDirectory(remoteDir, localDir, filter, failFast);
        }

        String safeRemoteDir = cleanAndValidatePath(remoteDir);

        // 验证远端目标存在且必须为有效目录
        if (!"/".equals(safeRemoteDir)) {
            boolean isDir = execute(client -> {
                String wireDir = toWirePath(safeRemoteDir);
                String currentDir = client.printWorkingDirectory();
                try {
                    if (client.changeWorkingDirectory(wireDir)) {
                        if (currentDir != null) {
                            client.changeWorkingDirectory(currentDir);
                        }
                        return true;
                    }
                } catch (Exception ignored) {
                }
                return false;
            });
            if (!isDir) {
                if (!exists(safeRemoteDir)) {
                    throw new FtpException(FtpResultCode.FILE_NOT_FOUND, "FTP 远程目录不存在: " + safeRemoteDir);
                }
                throw new FtpException(FtpResultCode.INVALID_PATH, "FTP 远程路径不是有效目录: " + safeRemoteDir);
            }
        }

        long startTime = System.nanoTime();
        log.debug("开始批量递归下载目录: {} -> {} (failFast={})", safeRemoteDir, localDir.getAbsolutePath(), failFast);

        BatchTransferResult result = new BatchTransferResult();
        try {
            downloadDirectoryInternal(safeRemoteDir, safeRemoteDir, localDir, filter, failFast, result, 0);
        } finally {
            result.setDuration(Duration.ofNanos(System.nanoTime() - startTime));
            log.debug("完成批量递归下载目录: {} -> {}, 耗时: {}ms, 总文件: {}, 成功: {}, 失败: {}, 跳过: {}, 传输字节: {}",
                    safeRemoteDir, localDir.getAbsolutePath(), result.getDuration().toMillis(),
                    result.getTotalFiles(), result.getSuccessCount(), result.getFailureCount(),
                    result.getSkippedCount(), result.getTransferredBytes());
        }
        return result;
    }

    private void uploadDirectoryInternal(File rootDir, File currentDir, String baseRemoteDir,
                                         Predicate<File> filter, boolean failFast,
                                         BatchTransferResult result, int depth) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw new FtpException(FtpResultCode.INVALID_PATH,
                    "本地目录递归层级超过最大安全深度限制 (" + MAX_DIRECTORY_DEPTH + "层): " + currentDir.getAbsolutePath());
        }

        File[] files = currentDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        for (File file : files) {
            if (file == null) {
                continue;
            }

            String fileName = file.getName();
            if (file.isDirectory()) {
                // 1. 过滤操作系统隐性系统目录（如 .git）
                if (isSystemOrHiddenFile(fileName)) {
                    continue;
                }
                // 计算当前子目录在服务端的相对路径并确保远端目录结构存在
                String relPath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                String subRemoteDir = normalizePath(baseRemoteDir, relPath);
                try {
                    makeDirectories(subRemoteDir);
                } catch (Exception e) {
                    log.warn("批量上传创建远端子目录失败: {}", subRemoteDir, e);
                }
                uploadDirectoryInternal(rootDir, file, baseRemoteDir, filter, failFast, result, depth + 1);
            } else if (file.isFile()) {
                result.setTotalFiles(result.getTotalFiles() + 1);
                // 2. 隐藏系统文件前置过滤（环境宿主中立防卫）
                if (isSystemOrHiddenFile(fileName)) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    continue;
                }
                // 3. 自定义谓词过滤
                if (filter != null && !filter.test(file)) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    continue;
                }

                // 4. 执行文件上传
                String relPath = rootDir.toPath().relativize(file.toPath()).toString().replace('\\', '/');
                String targetRemotePath = normalizePath(baseRemoteDir, relPath);
                long fileLength = file.length();
                try {
                    boolean uploaded = upload(targetRemotePath, file);
                    if (uploaded) {
                        result.setSuccessCount(result.getSuccessCount() + 1);
                        result.setTransferredBytes(result.getTransferredBytes() + fileLength);
                    } else {
                        throw new FtpException(FtpResultCode.UPLOAD_FAILED, "上传文件返回未成功: " + targetRemotePath);
                    }
                } catch (Exception e) {
                    log.warn("批量上传单文件失败: {} -> {}, 错误: {}", file.getAbsolutePath(), targetRemotePath, e.getMessage());
                    result.addFailure(TransferFailureItem.of(targetRemotePath, e));
                    if (failFast) {
                        if (e instanceof FtpException fe) {
                            throw fe;
                        }
                        throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                                "批量上传在文件 [" + targetRemotePath + "] 处发生异常，触发快速熔断: " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private void downloadDirectoryInternal(String rootRemoteDir, String currentRemoteDir, File baseLocalDir,
                                           Predicate<FtpFileInfo> filter, boolean failFast,
                                           BatchTransferResult result, int depth) {
        if (depth > MAX_DIRECTORY_DEPTH) {
            throw new FtpException(FtpResultCode.INVALID_PATH,
                    "FTP 远程目录递归层级超过最大安全深度限制 (" + MAX_DIRECTORY_DEPTH + "层): " + currentRemoteDir);
        }

        List<FtpFileInfo> fileInfos = listFiles(currentRemoteDir);
        if (fileInfos == null || fileInfos.isEmpty()) {
            return;
        }

        for (FtpFileInfo info : fileInfos) {
            if (info == null) {
                continue;
            }

            String name = info.getName();
            if (info.isDirectory()) {
                if (isSystemOrHiddenFile(name)) {
                    continue;
                }
                // 计算相对路径并创建本地对应子目录
                String relPath = computeRelativePath(rootRemoteDir, info.getPath());
                File subLocalDir = new File(baseLocalDir, relPath.replace('/', File.separatorChar));
                if (!subLocalDir.exists()) {
                    subLocalDir.mkdirs();
                }
                downloadDirectoryInternal(rootRemoteDir, info.getPath(), baseLocalDir, filter, failFast, result, depth + 1);
            } else if (info.isFile()) {
                result.setTotalFiles(result.getTotalFiles() + 1);
                if (isSystemOrHiddenFile(name)) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    continue;
                }
                if (filter != null && !filter.test(info)) {
                    result.setSkippedCount(result.getSkippedCount() + 1);
                    continue;
                }

                String relPath = computeRelativePath(rootRemoteDir, info.getPath());
                File targetLocalFile = new File(baseLocalDir, relPath.replace('/', File.separatorChar));
                try {
                    boolean downloaded = download(info.getPath(), targetLocalFile);
                    if (downloaded) {
                        result.setSuccessCount(result.getSuccessCount() + 1);
                        result.setTransferredBytes(result.getTransferredBytes() + targetLocalFile.length());
                    } else {
                        throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "下载文件返回未成功: " + info.getPath());
                    }
                } catch (Exception e) {
                    log.warn("批量下载单文件失败: {} -> {}, 错误: {}", info.getPath(), targetLocalFile.getAbsolutePath(), e.getMessage());
                    result.addFailure(TransferFailureItem.of(info.getPath(), e));
                    if (failFast) {
                        if (e instanceof FtpException fe) {
                            throw fe;
                        }
                        throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                                "批量下载在文件 [" + info.getPath() + "] 处发生异常，触发快速熔断: " + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private String computeRelativePath(String baseDir, String fullPath) {
        if (fullPath == null) {
            return "";
        }
        if (baseDir == null || "/".equals(baseDir) || baseDir.isBlank()) {
            return fullPath.startsWith("/") ? fullPath.substring(1) : fullPath;
        }
        String normalizedBase = baseDir.endsWith("/") ? baseDir.substring(0, baseDir.length() - 1) : baseDir;
        if (fullPath.startsWith(normalizedBase)) {
            String sub = fullPath.substring(normalizedBase.length());
            return sub.startsWith("/") ? sub.substring(1) : sub;
        }
        return fullPath.startsWith("/") ? fullPath.substring(1) : fullPath;
    }

    /**
     * 校验是否为操作系统隐性文件、临时文件或版本控制目录（环境宿主中立防卫）
     *
     * @param name 文件或目录名称
     * @return 是隐性系统文件或垃圾元数据时返回 true
     */
    public static boolean isSystemOrHiddenFile(String name) {
        if (name == null || name.isBlank()) {
            return true;
        }
        String lower = name.toLowerCase();
        return lower.equals("desktop.ini")
                || lower.equals(".ds_store")
                || lower.equals("thumbs.db")
                || lower.equals(".git")
                || lower.startsWith("._")
                || lower.endsWith(".tmp");
    }

    // ==========================================
    // 协议线缆转码与路径安全工具 (Wire Transcoding & Path Guards)
    // ==========================================

    /**
     * 清洗并防卫校验路径，防御路径穿越（Path Traversal）安全漏洞
     *
     * @param path 原始路径字符串
     * @return 清洗规范化后的绝对或相对路径
     * @throws FtpException 当路径包含 .. 越权序列时抛出 INVALID_PATH 异常
     */
    public String cleanAndValidatePath(String path) {
        Assert.hasText(path, "path must not be blank");
        String normalized = path.replace('\\', '/');
        if (normalized.indexOf('\0') >= 0) {
            throw new FtpException(FtpResultCode.INVALID_PATH, "FTP 路径包含非法空字符: " + path);
        }
        boolean isAbsolute = normalized.startsWith("/");
        String[] segments = normalized.split("/+");
        List<String> cleanSegments = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                throw new FtpException(FtpResultCode.INVALID_PATH, "FTP 路径包含非法路径穿越字符: " + path);
            }
            cleanSegments.add(segment);
        }
        if (cleanSegments.isEmpty()) {
            return isAbsolute ? "/" : "";
        }
        String joined = String.join("/", cleanSegments);
        return isAbsolute ? "/" + joined : joined;
    }

    /**
     * 获取指定路径的父级目录路径
     *
     * @param path 完整路径
     * @return 父级目录路径，若没有父级则返回 null 或 "/"
     */
    public String getParentPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return null;
        }
        if (lastSlash == 0) {
            return "/";
        }
        return path.substring(0, lastSlash);
    }

    /**
     * 从指定路径中提取纯文件名或最后一级名称
     *
     * @param path 完整路径
     * @return 文件名
     */
    public String getFileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) {
            return path;
        }
        return path.substring(lastSlash + 1);
    }

    /**
     * 将业务路径中文字符转换为控制通道协议线缆路径
     * <p>
     * 当控制通道配置为 ISO-8859-1 时，执行 RFC 959 线缆字节转码（打包为 ISO-8859-1 字符）；
     * 当控制通道已经是 UTF-8 或 GBK 时，Commons Net 底层 Socket Writer 会原生以此编码写入，直接返回原始路径。
     * </p>
     *
     * @param path 业务字符串路径
     * @return 协议线缆路径
     */
    public String toWirePath(String path) {
        if (path == null) {
            return null;
        }
        String encoding = properties.getControlEncoding();
        if (encoding != null && StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(encoding)) {
            try {
                return new String(path.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
            } catch (Exception e) {
                return path;
            }
        }
        return path;
    }

    /**
     * 将服务端返回的线缆字节路径转码回业务编码字符串
     *
     * @param wirePath 协议线缆路径
     * @return 业务编码字符串
     */
    public String fromWirePath(String wirePath) {
        if (wirePath == null) {
            return null;
        }
        String encoding = properties.getControlEncoding();
        if (encoding != null && StandardCharsets.ISO_8859_1.name().equalsIgnoreCase(encoding)) {
            try {
                return new String(wirePath.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            } catch (Exception e) {
                return wirePath;
            }
        }
        return wirePath;
    }

    /**
     * 规范化目录与文件名的拼接路径
     *
     * @param dir  目录路径
     * @param name 文件或子目录名
     * @return 规整后的绝对路径
     */
    public String normalizePath(String dir, String name) {
        if (dir == null || dir.isBlank() || "/".equals(dir)) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    // ==========================================
    // 内部私有递归辅助 (Internal Recursive Helpers)
    // ==========================================

    private boolean makeDirectoriesInternal(FTPClient client, String remoteDir) throws IOException {
        if (remoteDir == null || remoteDir.isBlank() || "/".equals(remoteDir)) {
            return true;
        }
        boolean isAbsolute = remoteDir.startsWith("/");
        String[] parts = remoteDir.split("/+");
        StringBuilder current = new StringBuilder();
        if (isAbsolute) {
            current.append("/");
        }
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if (isAbsolute && current.length() > 1) {
                current.append("/");
            } else if (!isAbsolute && current.length() > 0) {
                current.append("/");
            }
            current.append(part);
            String dirPath = current.toString();
            String wireDirPath = toWirePath(dirPath);
            client.makeDirectory(wireDirPath);
        }
        // 最终校验：目标目录是否真实存在并可进入（彻底防御由于权限不足等原因导致的虚假成功）
        String wireTarget = toWirePath(remoteDir);
        String currentDir = client.printWorkingDirectory();
        try {
            if (client.changeWorkingDirectory(wireTarget)) {
                if (currentDir != null) {
                    client.changeWorkingDirectory(currentDir);
                }
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean deleteDirectoryInternal(FTPClient client, String remoteDir, boolean recursive) throws IOException {
        String wireDir = toWirePath(remoteDir);
        if (!recursive) {
            return client.removeDirectory(wireDir);
        }
        FTPFile[] files = client.listFiles(wireDir);
        if (files != null) {
            for (FTPFile file : files) {
                if (file == null) {
                    continue;
                }
                String name = file.getName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String subPath = normalizePath(remoteDir, fromWirePath(name));
                if (file.isDirectory()) {
                    deleteDirectoryInternal(client, subPath, true);
                } else {
                    client.deleteFile(toWirePath(subPath));
                }
            }
        }
        return client.removeDirectory(wireDir);
    }
}
