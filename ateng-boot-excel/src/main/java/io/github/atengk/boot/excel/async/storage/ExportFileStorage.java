package io.github.atengk.boot.excel.async.storage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * 异步导出文件持久化与分发 SPI 门面
 * <p>
 * 解耦具体底层物理存储介质（本地磁盘、NAS 挂载卷、MinIO 或阿里云 OSS 等）。
 * 默认装配本地存储实现 {@link LocalFileSystemStorage}，并允许上层微服务无缝替换为对象存储直传实现。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public interface ExportFileStorage {

    /**
     * 保存生成的临时 Excel 文件至持久化存储
     *
     * @param taskId   导出任务 ID
     * @param tempFile 本地已写出的临时文件
     * @param fileName 原始业务报表文件名
     * @return 存储标识键 storageKey
     * @throws IOException 保存失败
     */
    String store(String taskId, File tempFile, String fileName) throws IOException;

    /**
     * 获取指定导出文件的输入流
     *
     * @param taskId     导出任务 ID
     * @param storageKey 存储标识键
     * @return 文件输入流，若不存在则返回 null
     * @throws IOException 读取失败
     */
    InputStream getInputStream(String taskId, String storageKey) throws IOException;

    /**
     * 获取导出文件的外部下载直链或相对端点 URL
     *
     * @param taskId     导出任务 ID
     * @param storageKey 存储标识键
     * @return 文件的下载直链（对于 OSS 返回预签名 URL，对于本地存储返回受管下载端点相对路径），若不支持直链则返回 null
     */
    String getDownloadUrl(String taskId, String storageKey);

    /**
     * 删除指定的文件资源
     *
     * @param taskId     导出任务 ID
     * @param storageKey 存储标识键
     */
    void delete(String taskId, String storageKey);
}
