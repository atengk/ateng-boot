package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 统一存储协议驱动 SPI 契约
 * 抽象本地磁盘与 S3 兼容协议等底层物理存储介质的原子交互规范
 *
 * @author Ateng
 * @since 2026-10-17
 */
public interface StorageDriver extends AutoCloseable {

    /**
     * 释放底层存储驱动持有的连接池、网络通道或系统句柄（默认空实现）
     */
    @Override
    default void close() {
        // 默认空实现，由持有网络连接池的驱动（如 S3Driver）按需覆写
    }

    /**
     * 上传存储对象
     *
     * @param path        存储对象相对路径
     * @param in          对象输入流
     * @param contentType MIME 内容类型（若为 null 或空白由实现负责推导）
     * @param size        对象字节长度（流未知长度时传 -1）
     * @return 存储对象元数据
     */
    StorageObject upload(String path, InputStream in, String contentType, long size);

    /**
     * 下载存储对象输入流
     *
     * @param path 存储对象相对路径
     * @return 对象内容输入流（调用方负责关闭）
     */
    InputStream download(String path);

    /**
     * 获取存储对象元数据信息
     *
     * @param path 存储对象相对路径
     * @return 强类型元数据对象
     */
    StorageObject getMetadata(String path);

    /**
     * 判断存储对象是否存在
     *
     * @param path 存储对象相对路径
     * @return true 存在，false 不存在
     */
    boolean exists(String path);

    /**
     * 删除指定存储对象（天然幂等）
     * 若目标对象原本即不存在，静默返回 true，杜绝异常中断
     *
     * @param path 存储对象相对路径
     * @return true 删除成功或原本不存在
     */
    boolean delete(String path);

    /**
     * 批量删除存储对象（天然幂等）
     *
     * @param paths 存储对象相对路径列表
     * @return true 批量删除成功
     */
    boolean deleteBatch(List<String> paths);

    /**
     * 跨路径复制存储对象
     *
     * @param sourcePath 源对象相对路径
     * @param targetPath 目标对象相对路径
     * @return true 复制成功
     */
    boolean copy(String sourcePath, String targetPath);

    /**
     * 跨路径移动存储对象
     *
     * @param sourcePath 源对象相对路径
     * @param targetPath 目标对象相对路径
     * @return true 移动成功
     */
    boolean move(String sourcePath, String targetPath);

    /**
     * 列举指定前缀下的存储对象列表
     *
     * @param prefix    路径前缀（若为 null 或空白表示根目录）
     * @param recursive 是否递归遍历子目录（true 深度递归返回所有匹配文件；false 仅返回当前层级文件及直接子目录）
     * @return 存储对象列表（默认过滤以 . 开头的系统隐藏项）
     */
    List<StorageObject> list(String prefix, boolean recursive);

    /**
     * 获取预签名只读下载外链凭证
     *
     * @param path     存储对象相对路径
     * @param duration 链接有效持续时长
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUrl(String path, Duration duration) {
        throw new UnsupportedOperationException("Current storage driver does not support presigned download URL");
    }

    /**
     * 获取预签名直传写入外链凭证（极简重载，默认向后兼容）
     *
     * @param path     存储对象相对路径
     * @param duration 链接有效持续时长
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        return getPresignedUploadUrl(PresignedUploadRequest.builder()
                .path(path)
                .duration(duration)
                .build());
    }

    /**
     * 基于强类型配置构建预签名直传写入外链凭证
     *
     * @param request 直传配置请求
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUploadUrl(PresignedUploadRequest request) {
        throw new UnsupportedOperationException("Current storage driver does not support presigned upload URL");
    }

    /**
     * 初始化大文件分片上传会话
     *
     * @param path 目标存储对象路径
     * @return 全局唯一分片上传会话标识 uploadId
     */
    String initiateChunkedUpload(String path);

    /**
     * 上传单个分片数据（1-based 序号）
     *
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始，1-based）
     * @param in         分片数据输入流
     * @param size       分片字节大小（未知时可传 -1）
     */
    void uploadChunk(String uploadId, int chunkIndex, InputStream in, long size);

    /**
     * 完成分片上传并正序合并碎片
     * 校验 1 ~ totalChunks 连续性，合并成功后自动清理临时碎片
     *
     * @param uploadId    分片上传会话标识
     * @param targetPath  最终合并落盘的目标对象路径
     * @param totalChunks 分片总片数（从 1 到 totalChunks 必须全部就绪）
     * @return 合并完成后的存储对象元数据
     */
    StorageObject completeChunkedUpload(String uploadId, String targetPath, int totalChunks);

    /**
     * 取消或中止分片上传会话并清理所有已上传碎片
     *
     * @param uploadId 分片上传会话标识
     */
    void abortChunkedUpload(String uploadId);
}
