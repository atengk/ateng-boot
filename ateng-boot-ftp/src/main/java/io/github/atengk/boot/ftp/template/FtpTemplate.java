package io.github.atengk.boot.ftp.template;

import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.model.FtpFileInfo;
import io.github.atengk.boot.ftp.pool.FtpClientCallback;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.pool2.ObjectPool;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.unit.DataSize;

import java.io.*;
import java.nio.charset.StandardCharsets;
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
@RequiredArgsConstructor
public class FtpTemplate {

    @Getter
    private final ObjectPool<FTPClient> pool;

    @Getter
    private final FtpProperties properties;

    // ==========================================
    // 核心执行底座与逃逸舱 (Core Execution & Escape Hatch)
    // ==========================================

    /**
     * 在受管连接池借出的原生客户端会话中执行操作（逃逸舱）
     *
     * @param action 用户自定义回调
     * @param <T>    返回值类型
     * @return 执行结果
     */
    public <T> T execute(FtpClientCallback<T> action) {
        Assert.notNull(action, "FtpClientCallback must not be null");
        FTPClient client = null;
        boolean invalidated = false;
        try {
            client = pool.borrowObject();
            return action.doInFtp(client);
        } catch (Exception e) {
            // 防御性连接治理：凡涉及底层 IO/网络异常，该连接状态已不可信，强制标记失效物理销毁
            boolean hasIoError = (e instanceof IOException) || (e.getCause() instanceof IOException);
            if (hasIoError && client != null) {
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

        return execute(client -> {
            // 1. 父级多级目录自动递归创建 (mkdir -p)
            if (properties.isAutoCreateDirectories()) {
                String parentDir = getParentPath(safePath);
                if (parentDir != null && !parentDir.isBlank() && !"/".equals(parentDir)) {
                    makeDirectoriesInternal(client, parentDir);
                }
            }

            boolean isAtomic = properties.isAtomicUpload();
            String suffix = properties.getTempFileSuffix() != null ? properties.getTempFileSuffix() : ".tmp";
            String uploadPath = isAtomic ? safePath + suffix : safePath;
            String wireUploadPath = toWirePath(uploadPath);
            String wireTargetPath = toWirePath(safePath);

            try {
                boolean success = client.storeFile(wireUploadPath, in);
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
            } catch (Exception e) {
                // 异常补偿机制：若开启原子上传且未完整闭合，在独立干净会话中物理清理残留的临时脏文件
                if (isAtomic) {
                    try {
                        execute(cleanClient -> {
                            cleanClient.deleteFile(toWirePath(uploadPath));
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
        });
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

                @Override
                public void write(int b) throws IOException {
                    if (++count > maxBytes) {
                        throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                                "FTP 下载文件大小超过内存安全阈值: " + maxBytes + " bytes");
                    }
                    out.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    count += len;
                    if (count > maxBytes) {
                        throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                                "FTP 下载文件大小超过内存安全阈值: " + maxBytes + " bytes");
                    }
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
                }
            }
            // 关键：在关闭流后必须调用 completePendingCommand() 闭合传输指令
            boolean completed = client.completePendingCommand();
            if (!completed) {
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
            // 2. 平滑降级为 listFiles
            if (ftpFile == null) {
                try {
                    FTPFile[] files = client.listFiles(wirePath);
                    if (files != null && files.length > 0) {
                        ftpFile = files[0];
                    }
                } catch (Exception ignored) {
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
        return true;
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
