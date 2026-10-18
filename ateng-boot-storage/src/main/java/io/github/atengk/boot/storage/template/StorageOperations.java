package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    /**
     * 多文件纯流式打包为 ZIP 压缩包写出（零磁盘占用）
     * 若包含不存在的文件，将抛出 StorageException(OBJECT_NOT_FOUND) 严格阻断
     *
     * @param paths 存储对象相对路径列表
     * @param out   输出流
     */
    void downloadAsZip(List<String> paths, OutputStream out);

    /**
     * 多文件纯流式打包为 ZIP 压缩包写出（支持显式指定 ZIP 内部条目名称，零磁盘占用）
     * 若包含不存在的文件，将抛出 StorageException(OBJECT_NOT_FOUND) 严格阻断
     *
     * @param pathMap 路径映射表（Key: 存储路径, Value: 压缩包内相对路径/名称）
     * @param out     输出流
     */
    void downloadAsZip(Map<String, String> pathMap, OutputStream out);

    /**
     * 多文件纯流式打包为 ZIP 并通过 HTTP 响应写出下载（自动设置 Content-Type 与 Content-Disposition）
     *
     * @param paths       存储对象相对路径列表
     * @param response    HTTP 响应对象
     * @param zipFilename 客户端下载的 ZIP 文件名（若未提供 .zip 后缀将自动补齐）
     */
    void downloadAsZip(List<String> paths, HttpServletResponse response, String zipFilename);

    /**
     * 多文件纯流式打包为 ZIP 并通过 HTTP 响应写出下载（支持显式指定 ZIP 内部条目名称）
     *
     * @param pathMap     路径映射表（Key: 存储路径, Value: 压缩包内相对路径/名称）
     * @param response    HTTP 响应对象
     * @param zipFilename 客户端下载的 ZIP 文件名（若未提供 .zip 后缀将自动补齐）
     */
    void downloadAsZip(Map<String, String> pathMap, HttpServletResponse response, String zipFilename);

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
     * 上传单个分片数据（流式极简重载）
     *
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始，1-based）
     * @param in         分片数据输入流
     */
    default void uploadChunk(String uploadId, int chunkIndex, InputStream in) {
        uploadChunk(uploadId, chunkIndex, in, -1);
    }

    /**
     * 上传单个分片数据（字节数组便捷重载）
     *
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始，1-based）
     * @param bytes      分片字节数组
     */
    default void uploadChunk(String uploadId, int chunkIndex, byte[] bytes) {
        uploadChunk(uploadId, chunkIndex, new java.io.ByteArrayInputStream(bytes != null ? bytes : new byte[0]), bytes != null ? bytes.length : 0);
    }

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
