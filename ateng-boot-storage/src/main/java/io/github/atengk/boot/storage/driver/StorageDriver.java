package io.github.atengk.boot.storage.driver;

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
public interface StorageDriver {

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
     * 获取预签名直传写入外链凭证
     *
     * @param path     存储对象相对路径
     * @param duration 链接有效持续时长
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        throw new UnsupportedOperationException("Current storage driver does not support presigned upload URL");
    }
}
