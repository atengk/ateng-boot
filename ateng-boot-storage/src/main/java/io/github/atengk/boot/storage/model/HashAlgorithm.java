package io.github.atengk.boot.storage.model;

/**
 * 存储散列与完整性校验算法枚举
 *
 * @author Ateng
 * @since 2026-10-20
 */
public enum HashAlgorithm {

    /**
     * MD5 消息摘要算法（128-bit，输出 32 位十六进制字符串，常用于 S3 ETag 及轻量完整性校验）
     */
    MD5("MD5"),

    /**
     * SHA-1 散列算法（160-bit，输出 40 位十六进制字符串，常用于 Git、版本控制比对与历史系统对接）
     */
    SHA1("SHA-1"),

    /**
     * SHA-256 散列算法（256-bit，输出 64 位十六进制字符串，工业级数字指纹与秒传唯一标识标配）
     */
    SHA256("SHA-256"),

    /**
     * CRC32 循环冗余校验算法（32-bit，输出 8 位十六进制字符串或无符号整数，常用于分片切片传输完整性快速比对）
     */
    CRC32("CRC32");

    private final String standardName;

    HashAlgorithm(String standardName) {
        this.standardName = standardName;
    }

    /**
     * 获取标准 JDK 算法名称
     *
     * @return 算法名称（如 "MD5"、"SHA-1"、"SHA-256"、"CRC32"）
     */
    public String getStandardName() {
        return standardName;
    }

    /**
     * 是否为基于 {@link java.security.MessageDigest} 的消息摘要算法
     *
     * @return true 若为 MD5、SHA1、SHA256；false 若为 CRC32
     */
    public boolean isMessageDigest() {
        return this != CRC32;
    }
}
