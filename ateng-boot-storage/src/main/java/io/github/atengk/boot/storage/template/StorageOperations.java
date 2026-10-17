package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/**
 * 存储操作高阶门面契约接口
 *
 * @author Ateng
 * @since 2026-10-17
 */
public interface StorageOperations {

    /**
     * 上传输入流到指定存储路径（自动推导 MIME 与计算大小）
     *
     * @param path 目标相对路径
     * @param in   输入流
     * @return 存储元数据对象
     */
    StorageObject upload(String path, InputStream in);

    /**
     * 携带明确 MIME 内容类型上传输入流
     *
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @return 存储元数据对象
     */
    StorageObject upload(String path, InputStream in, String contentType);

    /**
     * 完整参数上传输入流
     *
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @param size        数据流字节大小（未知时传 -1）
     * @return 存储元数据对象
     */
    StorageObject upload(String path, InputStream in, String contentType, long size);

    /**
     * 上传 Spring Web MultipartFile 表单文件
     *
     * @param path 目标相对路径
     * @param file 表单上传文件
     * @return 存储元数据对象
     */
    StorageObject upload(String path, MultipartFile file);

    /**
     * 下载目标对象内容输入流
     *
     * @param path 目标相对路径
     * @return 输入流（调用方负责关闭）
     */
    InputStream download(String path);

    /**
     * 获取 Spring 原生资源载荷
     *
     * @param path 目标相对路径
     * @return StorageResource 实例
     */
    StorageResource getResource(String path);

    /**
     * 获取存储对象元数据
     *
     * @param path 目标相对路径
     * @return 存储元数据对象
     */
    StorageObject getMetadata(String path);

    /**
     * 判断对象是否存在
     *
     * @param path 目标相对路径
     * @return true 存在，false 不存在
     */
    boolean exists(String path);

    /**
     * 删除目标存储对象（天然幂等）
     * 若目标对象原本不存在，静默返回 true
     *
     * @param path 目标相对路径
     * @return true 操作成功
     */
    boolean delete(String path);

    /**
     * 批量删除存储对象（天然幂等）
     *
     * @param paths 目标相对路径列表
     * @return true 操作成功
     */
    boolean deleteBatch(List<String> paths);

    /**
     * 获取预签名只读下载外链凭证
     *
     * @param path     目标相对路径
     * @param duration 有效时长
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUrl(String path, Duration duration);

    /**
     * 获取预签名直传写入外链凭证
     *
     * @param path     目标相对路径
     * @param duration 有效时长
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUploadUrl(String path, Duration duration);
}
