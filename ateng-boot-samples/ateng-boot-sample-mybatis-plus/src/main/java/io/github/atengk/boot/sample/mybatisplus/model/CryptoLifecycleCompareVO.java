package io.github.atengk.boot.sample.mybatisplus.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 数据安全三态生命周期对比视图对象
 * 直观呈现“持久层 AES 密文存储 -> Service 内存 100% 原始真明文 -> 视图序列化 @FieldSensitive 动态掩码”完整闭环。
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
public class CryptoLifecycleCompareVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户主键 ID
     */
    private Long userId;

    /**
     * 数据库底层物理存储的手机号密文（由 JDBC 直接读取，格式为 v1:Base64(IV+Cipher)）
     */
    private String dbCipherPhone;

    /**
     * 数据库底层物理存储的邮箱密文（由 JDBC 直接读取，格式为 v1:Base64(IV+Cipher)）
     */
    private String dbCipherEmail;

    /**
     * 业务层内存实体是否为 100% 原始真实明文（验证未被打星号污染）
     */
    private Boolean inMemoryPlaintextVerified;

    /**
     * 业务层内存中读取到的手机号明文字符长度（如 11 位）
     */
    private Integer inMemoryPhoneLength;

    /**
     * 对外输出的传输视图对象（序列化为 JSON 时由 @FieldSensitive 动态打码）
     */
    private UserVO maskedView;
}
