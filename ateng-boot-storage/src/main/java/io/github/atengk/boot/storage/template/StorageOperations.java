package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
     * 上传纯文本字符串（默认 UTF-8 编码）
     *
     * @param path    目标相对路径
     * @param content 文本内容
     * @return 存储元数据对象
     */
    default StorageObject uploadString(String path, String content) {
        return uploadString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * 上传纯文本字符串（指定字符集）
     *
     * @param path    目标相对路径
     * @param content 文本内容
     * @param charset 字符编码
     * @return 存储元数据对象
     */
    StorageObject uploadString(String path, String content, Charset charset);

    /**
     * 下载对象为纯文本字符串（默认 UTF-8 编码）
     *
     * @param path 目标相对路径
     * @return 文本内容
     */
    default String downloadString(String path) {
        return downloadString(path, StandardCharsets.UTF_8);
    }

    /**
     * 下载对象为纯文本字符串（指定字符集）
     *
     * @param path    目标相对路径
     * @param charset 字符编码
     * @return 文本内容
     */
    String downloadString(String path, Charset charset);

    /**
     * 上传字节数组（自动推导 MIME）
     *
     * @param path  目标相对路径
     * @param bytes 字节数据
     * @return 存储元数据对象
     */
    default StorageObject uploadBytes(String path, byte[] bytes) {
        return uploadBytes(path, bytes, null);
    }

    /**
     * 上传字节数组（指定 MIME 类型）
     *
     * @param path        目标相对路径
     * @param bytes       字节数据
     * @param contentType MIME 内容类型
     * @return 存储元数据对象
     */
    StorageObject uploadBytes(String path, byte[] bytes, String contentType);

    /**
     * 下载对象为完整字节数组
     *
     * @param path 目标相对路径
     * @return 字节数组
     */
    byte[] downloadBytes(String path);

    /**
     * 上传 POJO 为 JSON 存储对象
     *
     * @param path 目标相对路径
     * @param pojo 业务对象
     * @return 存储元数据对象
     */
    StorageObject uploadJson(String path, Object pojo);

    /**
     * 下载 JSON 存储对象并反序列化为指定类型 POJO
     *
     * @param path  目标相对路径
     * @param clazz 目标类型 Class
     * @param <T>   目标泛型
     * @return 业务对象
     */
    <T> T downloadJson(String path, Class<T> clazz);

    /**
     * 平铺列举指定前缀下的存储对象（默认非递归，仅返回当前层级文件及直接子目录）
     *
     * @param prefix 路径前缀（若为 null 或空白表示根目录）
     * @return 存储对象列表（默认过滤系统隐藏项）
     */
    default List<StorageObject> list(String prefix) {
        return list(prefix, false);
    }

    /**
     * 列举指定前缀下的存储对象
     *
     * @param prefix    路径前缀（若为 null 或空白表示根目录）
     * @param recursive 是否递归遍历子目录
     * @return 存储对象列表（默认过滤系统隐藏项）
     */
    List<StorageObject> list(String prefix, boolean recursive);

    /**
     * 分页检索指定前缀下的存储对象（默认非递归，平铺返回当前层级）
     *
     * @param prefix    路径前缀（若为 null 或空白表示根目录）
     * @param pageQuery 分页参数（页码与页大小）
     * @return 标准分页结果对象
     */
    default PageResult<StorageObject> listPage(String prefix, PageQuery pageQuery) {
        return listPage(prefix, pageQuery, false);
    }

    /**
     * 分页检索指定前缀下的存储对象
     *
     * @param prefix    路径前缀（若为 null 或空白表示根目录）
     * @param pageQuery 分页参数（页码与页大小）
     * @param recursive 是否递归遍历子目录
     * @return 标准分页结果对象
     */
    PageResult<StorageObject> listPage(String prefix, PageQuery pageQuery, boolean recursive);

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

    /**
     * 编程式链式切源：获取绑定了指定命名存储源的不可变操作门面句柄
     * 局部作用域线程安全，不污染全局上下文
     *
     * @param sourceKey 目标存储源名称（如 "local", "oss-public", "minio-secure"）
     * @return 绑定目标存储驱动的操作句柄
     */
    default StorageOperations use(String sourceKey) {
        throw new UnsupportedOperationException("Current StorageOperations implementation does not support multi-source routing");
    }
}
