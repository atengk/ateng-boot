package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import org.springframework.core.io.Resource;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * 原生零拷贝流式散列计算工具
 * 专为大文件秒传、数据完整性比对与文件指纹排重提供高性能、内存恒定的底层散列计算能力
 *
 * <p>特性说明：</p>
 * <ul>
 *     <li>全面覆盖标准算法：MD5 (128-bit)、SHA-1 (160-bit)、SHA-256 (256-bit) 与 CRC32 (32-bit)；</li>
 *     <li>输入载荷全场景覆盖：支持 {@link InputStream}、{@link File}、{@link Path}（通过 NIO FileChannel 零拷贝管道读取）与 Spring {@link Resource}；</li>
 *     <li>恒定内存消耗：严格采用流式分块循环消费，杜绝任何将整个文件加载至内存堆的危险操作（杜绝 OOM）；</li>
 *     <li>抗时序旁路攻击：比对方法基于 {@link MessageDigest#isEqual(byte[], byte[])} 实现常数级时间比对；</li>
 *     <li>统一标准输出：散列 Hex 统一为标准规范的小写十六进制字符串（CRC32 输出为 8 位小写 Hex）。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class StorageHashUtil {

    /**
     * 默认分块流读取缓冲区大小（16 KB，兼顾吞吐与低内存占用）
     */
    private static final int BUFFER_SIZE = 16384;

    private StorageHashUtil() {
    }

    // ==========================================
    // 通用散列 Hex 计算 (hashHex)
    // ==========================================

    /**
     * 计算数据输入流的指定散列算法十六进制字符串（小写）
     *
     * @param in        数据输入流（流将被完全读取至 EOF，调用方负责流的生命周期关闭）
     * @param algorithm 散列算法
     * @return 标准小写十六进制字符串（CRC32 返回 8 位补零小写 Hex）
     */
    public static String hashHex(InputStream in, HashAlgorithm algorithm) {
        if (in == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "输入流不能为空");
        }
        if (algorithm == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "散列算法不能为空");
        }
        try {
            if (algorithm == HashAlgorithm.CRC32) {
                CRC32 crc = new CRC32();
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    crc.update(buffer, 0, read);
                }
                return formatCrc32Hex(crc.getValue());
            } else {
                MessageDigest digest = MessageDigest.getInstance(algorithm.getStandardName());
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
                return HexFormat.of().formatHex(digest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算数据流散列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 NIO FileChannel 管道零拷贝计算文件的指定散列算法十六进制字符串（小写）
     *
     * @param path      目标物理文件路径
     * @param algorithm 散列算法
     * @return 标准小写十六进制字符串
     */
    public static String hashHex(Path path, HashAlgorithm algorithm) {
        if (path == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件路径不能为空");
        }
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定文件不存在或不是普通文件: " + path);
        }
        if (algorithm == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "散列算法不能为空");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (algorithm == HashAlgorithm.CRC32) {
                CRC32 crc = new CRC32();
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    crc.update(buffer);
                    buffer.clear();
                }
                return formatCrc32Hex(crc.getValue());
            } else {
                MessageDigest digest = MessageDigest.getInstance(algorithm.getStandardName());
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
                return HexFormat.of().formatHex(digest.digest());
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算文件散列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于 File 对象计算指定散列算法十六进制字符串（小写）
     *
     * @param file      物理文件对象
     * @param algorithm 散列算法
     * @return 标准小写十六进制字符串
     */
    public static String hashHex(File file, HashAlgorithm algorithm) {
        if (file == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件对象不能为空");
        }
        return hashHex(file.toPath(), algorithm);
    }

    /**
     * 计算 Spring Resource 资源的指定散列算法十六进制字符串（小写）
     *
     * @param resource  Spring 资源对象（支持 FileSystemResource, ClassPathResource, StorageResource 等）
     * @param algorithm 散列算法
     * @return 标准小写十六进制字符串
     */
    public static String hashHex(Resource resource, HashAlgorithm algorithm) {
        if (resource == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "Resource 资源不能为空");
        }
        if (!resource.exists()) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定 Resource 资源不存在: " + resource.getDescription());
        }
        if (resource.isFile()) {
            try {
                return hashHex(resource.getFile().toPath(), algorithm);
            } catch (IOException ignored) {
                // 回退至流式读取
            }
        }
        try (InputStream in = resource.getInputStream()) {
            return hashHex(in, algorithm);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "读取 Resource 资源计算散列失败: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // 通用散列原始字节数组计算 (hash)
    // ==========================================

    /**
     * 计算输入流的散列原始字节数组
     *
     * @param in        输入流
     * @param algorithm 散列算法
     * @return 原始摘要字节数组
     */
    public static byte[] hash(InputStream in, HashAlgorithm algorithm) {
        if (in == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "输入流不能为空");
        }
        if (algorithm == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "散列算法不能为空");
        }
        try {
            if (algorithm == HashAlgorithm.CRC32) {
                long crcVal = crc32(in);
                ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES);
                buf.putInt((int) crcVal);
                return buf.array();
            } else {
                MessageDigest digest = MessageDigest.getInstance(algorithm.getStandardName());
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
                return digest.digest();
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算数据流散列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文件的散列原始字节数组
     *
     * @param path      文件物理路径
     * @param algorithm 散列算法
     * @return 原始摘要字节数组
     */
    public static byte[] hash(Path path, HashAlgorithm algorithm) {
        if (path == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件路径不能为空");
        }
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定文件不存在或不是普通文件: " + path);
        }
        if (algorithm == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "散列算法不能为空");
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            if (algorithm == HashAlgorithm.CRC32) {
                CRC32 crc = new CRC32();
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    crc.update(buffer);
                    buffer.clear();
                }
                long crcVal = crc.getValue();
                ByteBuffer buf = ByteBuffer.allocate(Integer.BYTES);
                buf.putInt((int) crcVal);
                return buf.array();
            } else {
                MessageDigest digest = MessageDigest.getInstance(algorithm.getStandardName());
                ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
                return digest.digest();
            }
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算文件散列失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算 File 对象的散列原始字节数组
     */
    public static byte[] hash(File file, HashAlgorithm algorithm) {
        if (file == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件对象不能为空");
        }
        return hash(file.toPath(), algorithm);
    }

    /**
     * 计算 Resource 资源的散列原始字节数组
     */
    public static byte[] hash(Resource resource, HashAlgorithm algorithm) {
        if (resource == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "Resource 资源不能为空");
        }
        if (!resource.exists()) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定 Resource 资源不存在: " + resource.getDescription());
        }
        if (resource.isFile()) {
            try {
                return hash(resource.getFile().toPath(), algorithm);
            } catch (IOException ignored) {
            }
        }
        try (InputStream in = resource.getInputStream()) {
            return hash(in, algorithm);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "读取 Resource 资源计算散列失败: " + e.getMessage(), e);
        }
    }

    // ==========================================
    // MD5 便捷方法
    // ==========================================

    public static String md5Hex(InputStream in) {
        return hashHex(in, HashAlgorithm.MD5);
    }

    public static String md5Hex(Path path) {
        return hashHex(path, HashAlgorithm.MD5);
    }

    public static String md5Hex(File file) {
        return hashHex(file, HashAlgorithm.MD5);
    }

    public static String md5Hex(Resource resource) {
        return hashHex(resource, HashAlgorithm.MD5);
    }

    public static byte[] md5(InputStream in) {
        return hash(in, HashAlgorithm.MD5);
    }

    public static byte[] md5(Path path) {
        return hash(path, HashAlgorithm.MD5);
    }

    public static byte[] md5(File file) {
        return hash(file, HashAlgorithm.MD5);
    }

    public static byte[] md5(Resource resource) {
        return hash(resource, HashAlgorithm.MD5);
    }

    // ==========================================
    // SHA-1 便捷方法
    // ==========================================

    public static String sha1Hex(InputStream in) {
        return hashHex(in, HashAlgorithm.SHA1);
    }

    public static String sha1Hex(Path path) {
        return hashHex(path, HashAlgorithm.SHA1);
    }

    public static String sha1Hex(File file) {
        return hashHex(file, HashAlgorithm.SHA1);
    }

    public static String sha1Hex(Resource resource) {
        return hashHex(resource, HashAlgorithm.SHA1);
    }

    public static byte[] sha1(InputStream in) {
        return hash(in, HashAlgorithm.SHA1);
    }

    public static byte[] sha1(Path path) {
        return hash(path, HashAlgorithm.SHA1);
    }

    public static byte[] sha1(File file) {
        return hash(file, HashAlgorithm.SHA1);
    }

    public static byte[] sha1(Resource resource) {
        return hash(resource, HashAlgorithm.SHA1);
    }

    // ==========================================
    // SHA-256 便捷方法
    // ==========================================

    public static String sha256Hex(InputStream in) {
        return hashHex(in, HashAlgorithm.SHA256);
    }

    public static String sha256Hex(Path path) {
        return hashHex(path, HashAlgorithm.SHA256);
    }

    public static String sha256Hex(File file) {
        return hashHex(file, HashAlgorithm.SHA256);
    }

    public static String sha256Hex(Resource resource) {
        return hashHex(resource, HashAlgorithm.SHA256);
    }

    public static byte[] sha256(InputStream in) {
        return hash(in, HashAlgorithm.SHA256);
    }

    public static byte[] sha256(Path path) {
        return hash(path, HashAlgorithm.SHA256);
    }

    public static byte[] sha256(File file) {
        return hash(file, HashAlgorithm.SHA256);
    }

    public static byte[] sha256(Resource resource) {
        return hash(resource, HashAlgorithm.SHA256);
    }

    // ==========================================
    // CRC32 便捷方法 (数值与十六进制)
    // ==========================================

    /**
     * 计算数据输入流的 CRC32 校验和数值（无符号 32 位以 long 返回）
     *
     * @param in 输入流
     * @return CRC32 数值（0 ~ 4294967295L）
     */
    public static long crc32(InputStream in) {
        if (in == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "输入流不能为空");
        }
        try {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return crc.getValue();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算 CRC32 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 计算文件的 CRC32 校验和数值（无符号 32 位以 long 返回）
     *
     * @param path 文件物理路径
     * @return CRC32 数值（0 ~ 4294967295L）
     */
    public static long crc32(Path path) {
        if (path == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件路径不能为空");
        }
        if (!Files.exists(path) || Files.isDirectory(path)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定文件不存在或不是普通文件: " + path);
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            CRC32 crc = new CRC32();
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                crc.update(buffer);
                buffer.clear();
            }
            return crc.getValue();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算 CRC32 失败: " + e.getMessage(), e);
        }
    }

    public static long crc32(File file) {
        if (file == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "文件对象不能为空");
        }
        return crc32(file.toPath());
    }

    public static long crc32(Resource resource) {
        if (resource == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "Resource 资源不能为空");
        }
        if (!resource.exists()) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "指定 Resource 资源不存在: " + resource.getDescription());
        }
        if (resource.isFile()) {
            try {
                return crc32(resource.getFile().toPath());
            } catch (IOException ignored) {
            }
        }
        try (InputStream in = resource.getInputStream()) {
            return crc32(in);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算 CRC32 失败: " + e.getMessage(), e);
        }
    }

    public static String crc32Hex(InputStream in) {
        return formatCrc32Hex(crc32(in));
    }

    public static String crc32Hex(Path path) {
        return formatCrc32Hex(crc32(path));
    }

    public static String crc32Hex(File file) {
        return formatCrc32Hex(crc32(file));
    }

    public static String crc32Hex(Resource resource) {
        return formatCrc32Hex(crc32(resource));
    }

    // ==========================================
    // 校验与时序旁路防御比对 (verifyHash)
    // ==========================================

    /**
     * 抗时序旁路攻击的常数时间哈希字符串比对
     * 采用 {@link MessageDigest#isEqual(byte[], byte[])} 严格防御字符串字符逐位对比泄露时序特征
     *
     * @param actualHash   计算得到的实际哈希字符串
     * @param expectedHash 预期的哈希字符串（支持混杂大小写与前后空白）
     * @return true 若两散列值在语义上完全一致；false 若不一致或任一为 null
     */
    public static boolean verifyHash(String actualHash, String expectedHash) {
        if (actualHash == null || expectedHash == null) {
            return false;
        }
        String cleanActual = actualHash.trim().toLowerCase(Locale.ROOT);
        String cleanExpected = expectedHash.trim().toLowerCase(Locale.ROOT);
        return MessageDigest.isEqual(
                cleanActual.getBytes(StandardCharsets.US_ASCII),
                cleanExpected.getBytes(StandardCharsets.US_ASCII)
        );
    }

    /**
     * 校验数据输入流的散列值是否与预期相符（常数时间比对，抗时序旁路攻击）
     *
     * @param in           输入流
     * @param expectedHash 预期的哈希字符串
     * @param algorithm    散列算法
     * @return true 散列一致；false 散列不一致或计算异常
     */
    public static boolean verifyHash(InputStream in, String expectedHash, HashAlgorithm algorithm) {
        if (in == null || expectedHash == null || algorithm == null) {
            return false;
        }
        try {
            String calculated = hashHex(in, algorithm);
            return verifyHash(calculated, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验文件的散列值是否与预期相符（常数时间比对，抗时序旁路攻击）
     */
    public static boolean verifyHash(Path path, String expectedHash, HashAlgorithm algorithm) {
        if (path == null || expectedHash == null || algorithm == null) {
            return false;
        }
        try {
            String calculated = hashHex(path, algorithm);
            return verifyHash(calculated, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 校验 File 对象的散列值是否与预期相符
     */
    public static boolean verifyHash(File file, String expectedHash, HashAlgorithm algorithm) {
        if (file == null) {
            return false;
        }
        return verifyHash(file.toPath(), expectedHash, algorithm);
    }

    /**
     * 校验 Resource 资源的散列值是否与预期相符
     */
    public static boolean verifyHash(Resource resource, String expectedHash, HashAlgorithm algorithm) {
        if (resource == null || expectedHash == null || algorithm == null) {
            return false;
        }
        try {
            String calculated = hashHex(resource, algorithm);
            return verifyHash(calculated, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 格式化 CRC32 数值为 8 位小写十六进制字符串（高位补零）
     */
    private static String formatCrc32Hex(long crcVal) {
        return String.format(Locale.ROOT, "%08x", crcVal);
    }
}
