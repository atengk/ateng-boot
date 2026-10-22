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
 * 对称覆盖流式/文件/字节/Resource 读写、RFC 959 协议线缆转码、
 * RFC 3659 MLSD 机器目录高精度解析与受管流安全生命周期。
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
        } catch (FtpException e) {
            throw e;
        } catch (IOException e) {
            if (client != null) {
                try {
                    pool.invalidateObject(client);
                    invalidated = true;
                } catch (Exception ignored) {
                }
            }
            throw new FtpException(FtpResultCode.OPERATION_FAILED, "执行 FTP 通信操作发生 IO 异常: " + e.getMessage(), e);
        } catch (Exception e) {
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
    // 上传对称操作 (Symmetric Upload)
    // ==========================================

    /**
     * 上传输入流到远程路径
     *
     * @param remotePath 远程文件目标绝对或相对路径
     * @param in         待上传的数据输入流
     * @return 上传成功返回 true
     */
    public boolean upload(String remotePath, InputStream in) {
        Assert.hasText(remotePath, "remotePath must not be blank");
        Assert.notNull(in, "InputStream must not be null");
        return execute(client -> {
            String wirePath = toWirePath(remotePath);
            boolean success = client.storeFile(wirePath, in);
            if (!success) {
                int reply = client.getReplyCode();
                throw new FtpException(FtpResultCode.UPLOAD_FAILED,
                        "FTP 文件上传失败: " + remotePath + ", 服务端返回码: " + reply);
            }
            return true;
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
        return execute(client -> {
            String wirePath = toWirePath(remotePath);
            boolean success = client.retrieveFile(wirePath, out);
            if (!success) {
                int reply = client.getReplyCode();
                throw new FtpException(FtpResultCode.DOWNLOAD_FAILED,
                        "FTP 文件下载失败: " + remotePath + ", 服务端返回码: " + reply);
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
        long maxBytes = properties.getMaxDownloadBytes() != null
                ? properties.getMaxDownloadBytes().toBytes()
                : DataSize.ofMegabytes(50).toBytes();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        execute(client -> {
            String wirePath = toWirePath(remotePath);
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
                            "FTP 文件下载失败: " + remotePath + ", 服务端返回码: " + reply);
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
        execute(client -> {
            String wirePath = toWirePath(remotePath);
            InputStream in = null;
            try {
                in = client.retrieveFileStream(wirePath);
                if (in == null) {
                    int reply = client.getReplyCode();
                    throw new FtpException(FtpResultCode.FILE_NOT_FOUND,
                            "获取远程文件流失败 (文件不存在或权限不足): " + remotePath + ", 服务端返回码: " + reply);
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
                log.warn("FTP 挂起数据读取指令未正常结束: {}", remotePath);
                throw new FtpException(FtpResultCode.DOWNLOAD_FAILED, "完成 FTP 数据读取挂起指令失败: " + remotePath);
            }
            return null;
        });
    }

    // ==========================================
    // 目录查询与元数据解析 (Listing & Metadata)
    // ==========================================

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
            String targetDir = remoteDir != null && !remoteDir.isBlank() ? remoteDir : "/";
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

    // ==========================================
    // 协议线缆转码与路径工具 (Wire Transcoding & Utils)
    // ==========================================

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

    private String normalizePath(String dir, String name) {
        if (dir == null || dir.isBlank() || "/".equals(dir)) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
}
