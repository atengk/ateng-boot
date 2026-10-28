package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.util.StorageHashUtil;
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
     * 完整参数上传输入流（携带用户自定义元数据）
     *
     * @param path         目标相对路径
     * @param in           输入流
     * @param contentType  MIME 内容类型
     * @param size         数据流字节大小（未知时传 -1）
     * @param userMetadata 用户自定义元数据键值对（S3 映射为 x-amz-meta-*，Local 驱动映射为对象元数据）
     * @return 存储元数据对象
     */
    StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata);

    /**
     * 携带明确 MIME 内容类型与元数据上传输入流
     *
     * @param path         目标相对路径
     * @param in           输入流
     * @param contentType  MIME 内容类型
     * @param userMetadata 用户自定义元数据键值对
     * @return 存储元数据对象
     */
    default StorageObject upload(String path, InputStream in, String contentType, Map<String, String> userMetadata) {
        return upload(path, in, contentType, -1, userMetadata);
    }

    /**
     * 携带用户自定义元数据上传输入流（自动推导 MIME 与计算大小）
     *
     * @param path         目标相对路径
     * @param in           输入流
     * @param userMetadata 用户自定义元数据键值对
     * @return 存储元数据对象
     */
    default StorageObject upload(String path, InputStream in, Map<String, String> userMetadata) {
        return upload(path, in, null, -1, userMetadata);
    }

    /**
     * 上传 Spring Web MultipartFile 表单文件
     *
     * @param path 目标相对路径
     * @param file 表单上传文件
     * @return 存储元数据对象
     */
    StorageObject upload(String path, MultipartFile file);

    /**
     * 上传 Spring Web MultipartFile 表单文件（携带用户自定义元数据）
     *
     * @param path         目标相对路径
     * @param file         表单上传文件
     * @param userMetadata 用户自定义元数据键值对
     * @return 存储元数据对象
     */
    StorageObject upload(String path, MultipartFile file, Map<String, String> userMetadata);

    /**
     * 完整参数上传输入流（携带用户自定义元数据与公共读访问权限控制）
     *
     * @param path         目标相对路径
     * @param in           输入流
     * @param contentType  MIME 内容类型
     * @param size         数据流字节大小（未知时传 -1）
     * @param userMetadata 用户自定义元数据键值对
     * @param publicRead   是否赋予公共读取权限（S3 协议下注入 ObjectCannedACL.PUBLIC_READ）
     * @return 存储元数据对象
     */
    StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata, boolean publicRead);

    /**
     * 上传公开可读对象（自动推导 MIME 与计算大小，赋予公共读权限）
     *
     * @param path 目标相对路径
     * @param in   输入流
     * @return 存储元数据对象
     */
    default StorageObject uploadPublic(String path, InputStream in) {
        return uploadPublic(path, in, null, -1);
    }

    /**
     * 携带明确 MIME 内容类型上传公开可读对象
     *
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @return 存储元数据对象
     */
    default StorageObject uploadPublic(String path, InputStream in, String contentType) {
        return uploadPublic(path, in, contentType, -1);
    }

    /**
     * 完整参数上传公开可读对象
     *
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @param size        数据流字节大小（未知时传 -1）
     * @return 存储元数据对象
     */
    default StorageObject uploadPublic(String path, InputStream in, String contentType, long size) {
        return upload(path, in, contentType, size, null, true);
    }

    /**
     * 上传 Spring Web MultipartFile 表单文件为公开可读对象
     *
     * @param path 目标相对路径
     * @param file 表单上传文件
     * @return 存储元数据对象
     */
    StorageObject uploadPublic(String path, MultipartFile file);

    /**
     * 下载目标对象内容输入流
     *
     * @param path 目标相对路径
     * @return 输入流（调用方负责关闭）
     */
    InputStream download(String path);

    /**
     * 分段下载存储对象指定字节范围内容输入流 (Byte-Range Read)
     *
     * @param path        目标相对路径
     * @param startOffset 起始字节偏移量（0-based，必须 >= 0）
     * @param length      需读取的字节长度（必须 > 0）
     * @return 字节切片输入流（调用方负责关闭）
     */
    InputStream download(String path, long startOffset, long length);

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
     * 获取公开存储桶或具有公开读取权限对象的永久直接访问链接 (Public URL)
     *
     * @param path 目标相对路径
     * @return 永久直接访问 URL
     */
    String getUrl(String path);

    /**
     * 获取预签名只读下载外链凭证
     *
     * @param path     目标相对路径
     * @param duration 有效时长
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUrl(String path, Duration duration);

    /**
     * 获取预签名只读下载外链凭证（指定自定义友好下载文件名）
     * 自动按照 RFC 5987 / RFC 6266 编码 Content-Disposition 响应头
     *
     * @param path             目标相对路径
     * @param duration         有效时长
     * @param downloadFilename 自定义下载文件名（如 "年度财务报表.xlsx"）
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUrl(String path, Duration duration, String downloadFilename) {
        return getPresignedUrl(PresignedDownloadRequest.builder()
                .path(path)
                .duration(duration)
                .downloadFilename(downloadFilename)
                .build());
    }

    /**
     * 基于强类型配置请求构建预签名只读下载外链凭证
     * 支持定制有效时长、友好下载文件名、响应 Content-Type 与 Content-Disposition
     *
     * @param request 下载配置请求
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUrl(PresignedDownloadRequest request);

    /**
     * 获取预签名直传写入外链凭证（极简重载，默认向后兼容）
     *
     * @param path     目标相对路径
     * @param duration 有效时长
     * @return 预签名外链模型
     */
    default PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        return getPresignedUploadUrl(PresignedUploadRequest.builder()
                .path(path)
                .duration(duration)
                .build());
    }

    /**
     * 基于强类型配置请求构建预签名直传写入外链凭证
     *
     * @param request 直传配置请求
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUploadUrl(PresignedUploadRequest request);

    /**
     * 获取大文件分片预签名直传外链凭证
     *
     * @param path       目标相对路径
     * @param uploadId   分片上传会话标识
     * @param chunkIndex 分片正序编号（从 1 开始，1-based）
     * @param duration   有效时长
     * @return 预签名外链模型
     */
    PresignedUrl getPresignedUploadPartUrl(String path, String uploadId, int chunkIndex, Duration duration);

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

    /**
     * 扫描并清理超时未完成的孤儿分片碎片会话（运维与生命周期治理）
     *
     * @param maxAge 分片碎片最大存活保留寿命（超过此时长的未决分片被判定为孤儿碎片进行物理清理）
     * @return 成功清理释放的孤儿分片会话数量
     */
    int cleanExpiredChunkSessions(Duration maxAge);

    /**
     * 扫描并清理超时未完成的孤儿分片碎片会话（默认超时寿命 24 小时）
     *
     * @return 成功清理释放的孤儿分片会话数量
     */
    default int cleanExpiredChunkSessions() {
        return cleanExpiredChunkSessions(Duration.ofHours(24));
    }

    /**
     * 计算已存储对象的散列哈希值（流式零拷贝计算）
     *
     * @param path      目标存储对象相对路径
     * @param algorithm 散列算法
     * @return 标准小写十六进制字符串
     */
    default String calculateHash(String path, HashAlgorithm algorithm) {
        return StorageHashUtil.hashHex(getResource(path), algorithm);
    }

    /**
     * 计算已存储对象的 SHA-256 散列哈希值（秒传唯一标识标配，流式零拷贝计算）
     *
     * @param path 目标存储对象相对路径
     * @return 64 位小写十六进制 SHA-256 字符串
     */
    default String calculateHash(String path) {
        return calculateHash(path, HashAlgorithm.SHA256);
    }
}
