package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.core.util.DigestUtil;

/**
 * 纯 JDK 原生国密 SM3 密码杂凑算法实现 (GB/T 32905-2016)
 * 底层直接复用 ateng-boot-core 通用底座原语 {@link DigestUtil#sm3(byte[])}，零 Bouncy Castle 外部依赖
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class Sm3Digest {

    private Sm3Digest() {
    }

    /**
     * 计算单字节数组的 32 字节 SM3 杂凑摘要
     *
     * @param msg 输入字节数组
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(byte[] msg) {
        return DigestUtil.sm3(msg);
    }

    /**
     * 计算多段字节数组拼接后的 SM3 杂凑摘要（避免外部多余内存分配）
     *
     * @param parts 多段字节数组
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(byte[]... parts) {
        return DigestUtil.sm3(parts);
    }

    /**
     * 计算字符串的 SM3 杂凑摘要
     *
     * @param str 输入字符串 (UTF-8)
     * @return 32 字节哈希摘要
     */
    public static byte[] digest(String str) {
        return DigestUtil.sm3(str);
    }
}

