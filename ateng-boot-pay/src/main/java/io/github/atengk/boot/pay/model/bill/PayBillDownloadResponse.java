package io.github.atengk.boot.pay.model.bill;

import io.github.atengk.boot.pay.enums.PayChannel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.InputStream;
import java.io.Serializable;

/**
 * 渠道财务对账单下载响应实体
 *
 * @author Ateng
 * @since 2026-10-24
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayBillDownloadResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 支付渠道
     */
    private PayChannel channel;

    /**
     * 账单日期
     */
    private String billDate;

    /**
     * 账单类型
     */
    private String billType;

    /**
     * 账单下载地址（支付宝返回 30s 内有效的直链 URL，微信接口在查询元数据时返回）
     */
    private String downloadUrl;

    /**
     * 账单输入流（直接流式下载时装载）
     */
    private transient InputStream fileStream;

    /**
     * 哈希摘要算法（如 SHA1, MD5）
     */
    private String hashType;

    /**
     * 原始哈希校验码
     */
    private String hashValue;
}
