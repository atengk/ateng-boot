package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;

import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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
     * 上传存储对象（携带用户自定义元数据）
     *
     * @param path         存储对象相对路径
     * @param in           对象输入流
     * @param contentType  MIME 内容类型（若为 null 或空白由实现负责推导）
     * @param size         对象字节长度（流未知长度时传 -1）
     * @param userMetadata 用户自定义元数据键值对（S3 映射为 x-amz-meta-*，Local 驱动映射为对象元数据）
     * @return 存储对象元数据
     */
    default StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata) {
        return upload(path, in, contentType, size);
    }

    /**
     * 上传存储对象（携带用户自定义元数据与公共读访问权限控制）
     *
     * @param path         存储对象相对路径
     * @param in           对象输入流
     * @param contentType  MIME 内容类型（若为 null 或空白由实现负责推导）
     * @param size         对象字节长度（流未知长度时传 -1）
     * @param userMetadata 用户自定义元数据键值对
     * @param publicRead   是否赋予公共读取权限（S3 协议下注入 ObjectCannedACL.PUBLIC_READ）
     * @return 存储对象元数据
     */
    default StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata, boolean publicRead) {
        return upload(path, in, contentType, size, userMetadata);
    }

    /**
     * 上传公开可读存储对象（赋予公共读 Canned ACL 或对外直接访问权限）
     *
     * @param path 存储对象相对路径
     * @param in   对象输入流
     * @return 存储对象元数据
     */
    default StorageObject uploadPublic(String path, InputStream in) {
        return uploadPublic(path, in, null, -1);
    }

    /**
     * 携带明确 MIME 内容类型上传公开可读存储对象
     *
     * @param path        存储对象相对路径
     * @param in          对象输入流
     * @param contentType MIME 内容类型
     * @return 存储对象元数据
     */
    default StorageObject uploadPublic(String path, InputStream in, String contentType) {
        return uploadPublic(path, in, contentType, -1);
    }

    /**
     * 携带明确 MIME 内容类型与长度上传公开可读存储对象
     *
     * @param path        存储对象相对路径
     * @param in          对象输入流
     * @param contentType MIME 内容类型
     * @param size        数据流字节大小（未知时传 -1）
     * @return 存储对象元数据
     */
    default StorageObject uploadPublic(String path, InputStream in, String contentType, long size) {
        return upload(path, in, contentType, size, null, true);
    }

    /**
     * 获取公开存储桶或具有公开读取权限对象的永久直接访问链接 (Public URL)
     * 优先使用 domain 自定义域名/CDN 域名；若未配置 domain，S3 根据寻址模式动态拼接 endpoint 与 bucket；本地驱动返回相对或带 domain 路径
     *
     * @param path 存储对象相对路径
     * @return 永久直接访问 URL 字符串
     */
    String getUrl(String path);

    /**
     * 探查底层物理存储介质的健康状态与连通性
     *
     * @throws Exception 当底层存储不可用（如本地目录不存在/不可写、S3 存储桶无法访问等）时抛出异常
     */
    default void ping() throws Exception {
        // 默认空实现
    }

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
     * 删除指定路径前缀下的所有存储对象（天然幂等，流式分批删除）
     * 具备严格防误删熔断防卫：对空前缀、根路径 /、通配符 * 强行阻断抛出 StorageException(INVALID_PATH)
     *
     * @param prefix 目标路径前缀（如 "temp/logs"）
     * @return true 批量删除成功或原本无匹配对象
     */
    boolean deletePrefix(String prefix);

    /**
     * 删除指定目录及其内部包含的所有存储对象（天然幂等）
     * 具备严格防误删熔断防卫：对空路径、根路径 /、通配符 * 强行阻断抛出 StorageException(INVALID_PATH)
     *
     * @param dirPath 目标目录路径（如 "temp/logs" 或 "temp/logs/"）
     * @return true 目录及其子对象删除成功或原本不存在
     */
    default boolean deleteDirectory(String dirPath) {
        return deletePrefix(dirPath);
    }

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
     * 获取大文件分片预签名直传外链凭证
     *
     * @param path       存储对象相对路径
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始，1-based）
     * @param duration   链接有效持续时长
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUploadPartUrl(String path, String uploadId, int chunkIndex, Duration duration) {
        throw new UnsupportedOperationException("Current storage driver does not support presigned upload part URL");
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

    /**
     * 扫描并清理超时未完成的孤儿分片碎片会话（运维与生命周期治理）
     *
     * @param maxAge 分片碎片最大存活保留寿命（超过此时长的未决分片被判定为孤儿碎片进行物理清理）
     * @return 成功清理释放的孤儿分片会话数量
     */
    default int cleanExpiredChunkSessions(Duration maxAge) {
        return 0;
    }

    /**
     * 获取驱动健康探测与诊断上下文信息字典
     *
     * @return 包含驱动类型与核心定位信息的诊断字典
     */
    default Map<String, Object> getDiagnostics() {
        return Collections.emptyMap();
    }
}
