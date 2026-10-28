package io.github.atengk.boot.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Duration;

/**
 * 客户端预签名只读下载外链请求模型
 * 封装目标存储路径、授权时效、下载自定义友好文件名、响应 Content-Type 与 Content-Disposition 响应头重写
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignedDownloadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标存储相对路径（必填）
     */
    private String path;

    /**
     * 预签名有效时长（可选，默认 15 分钟）
     */
    @Builder.Default
    private Duration duration = Duration.ofMinutes(15);

    /**
     * 自定义下载保存的文件名（可选，指定后自动基于 RFC 5987 / RFC 6266 构造友好 Content-Disposition）
     */
    private String downloadFilename;

    /**
     * 强制重写下载响应报头的 Content-Type（可选，如 "application/pdf"、"image/png"）
     */
    private String responseContentType;

    /**
     * 强制重写下载响应报头的 Content-Disposition（可选，若未指定且配置了 downloadFilename，则自动推导）
     */
    private String responseContentDisposition;

    /**
     * 目标存储源名称（可选，多存储源场景下指定）
     */
    private String source;
}
