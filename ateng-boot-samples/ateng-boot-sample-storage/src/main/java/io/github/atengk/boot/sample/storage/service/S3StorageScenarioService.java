package io.github.atengk.boot.sample.storage.service;

import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Duration;
import java.util.Map;

/**
 * 云原生 S3 企业级对象存储场景服务
 * 演示并编排 S3 生产级高阶业务场景：
 * 1. 未知流长自适应流式分片上传（双阶内存缓冲 <= 5MB 防 OOM）
 * 2. 公开桶永久直链访问 (getUrl) 与公共读权限直传 (uploadPublic)
 * 3. 字节范围切片读取 (download byte-range)
 * 4. 预签名临时安全下载链接生成与友好中文文件名响应头重写
 * 5. 对象标签 Tagging 完整生命周期治理（设置、获取、清空）
 * 6. 存储类别分层 (StorageClass) 与服务端加密 (ServerSideEncryption) 元数据
 * 7. S3 存储源健康探测 (ping) 与连接池/配置诊断 (diagnostics)
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class S3StorageScenarioService {

    private static final String S3_SOURCE = "s3";

    private final StorageTemplate storageTemplate;

    /**
     * 场景 1：未知流长自适应流式分片上传（双阶内存缓冲 <= 5MB 防 OOM）
     * 适用于大报表流式导出、上游实时流转储等无法预知文件精确字节大小的业务场景
     *
     * @param path        目标对象相对路径
     * @param in          输入流（内部自适应 <=5MB 直传或 >5MB 分片上传）
     * @param contentType MIME 内容类型
     * @return 存储元数据对象
     */
    public StorageObject uploadAdaptiveStream(String path, InputStream in, String contentType) {
        return storageTemplate.use(S3_SOURCE).upload(path, in, contentType, -1L);
    }

    /**
     * 场景 2.1：公共读权限直传（挂载 Canned ACL: public-read）
     *
     * @param path        目标对象相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @param size        文件字节大小
     * @return 存储元数据对象
     */
    public StorageObject uploadPublicRead(String path, InputStream in, String contentType, long size) {
        return storageTemplate.use(S3_SOURCE).uploadPublic(path, in, contentType, size);
    }

    /**
     * 场景 2.2：获取公开桶永久直连访问 URL（解耦临时外链，适于 CDN 缓存与静态资源分发）
     *
     * @param path 对象相对路径
     * @return 永久直接访问 URL 字符串
     */
    public String getPermanentPublicUrl(String path) {
        return storageTemplate.use(S3_SOURCE).getUrl(path);
    }

    /**
     * 场景 3：分段切片字节范围读取（用于大文件断点续传、音视频流式 Seek 播放）
     *
     * @param path        对象相对路径
     * @param startOffset 起始字节偏移量 (>= 0)
     * @param length      拉取字节长度 (> 0)
     * @return 局部字节数组
     */
    public byte[] downloadByteRange(String path, long startOffset, long length) {
        try (InputStream stream = storageTemplate.use(S3_SOURCE).download(path, startOffset, length)) {
            return stream.readAllBytes();
        } catch (Exception e) {
            throw new RuntimeException("读取字节范围失败: " + e.getMessage(), e);
        }
    }

    /**
     * 场景 4：预签名临时安全下载链接生成（定制 Content-Disposition 友好中文文件名与 Content-Type）
     *
     * @param path             对象相对路径
     * @param duration         外链有效时长
     * @param downloadFilename 浏览器下载弹出的友好文件名（支持中文）
     * @return 预签名 URL 契约
     */
    public PresignedUrl getPresignedDownloadUrlWithFriendlyFilename(String path, Duration duration, String downloadFilename) {
        return storageTemplate.use(S3_SOURCE).getPresignedUrl(
                PresignedDownloadRequest.builder()
                        .path(path)
                        .duration(duration)
                        .downloadFilename(downloadFilename)
                        .build()
        );
    }

    /**
     * 场景 5.1：设置或更新对象业务标签（打通对象生命周期治理与部门归属审计）
     *
     * @param path 目标对象路径
     * @param tags 键值对标签
     */
    public void setObjectTags(String path, Map<String, String> tags) {
        storageTemplate.use(S3_SOURCE).setObjectTags(path, tags);
    }

    /**
     * 场景 5.2：获取对象关联的全部业务标签
     *
     * @param path 目标对象路径
     * @return 标签映射字典
     */
    public Map<String, String> getObjectTags(String path) {
        return storageTemplate.use(S3_SOURCE).getObjectTags(path);
    }

    /**
     * 场景 5.3：清空对象关联的所有业务标签
     *
     * @param path 目标对象路径
     */
    public void deleteObjectTags(String path) {
        storageTemplate.use(S3_SOURCE).deleteObjectTags(path);
    }

    /**
     * 场景 6.1：标准对象上传（由底层驱动根据配置自动挂载 StorageClass 与 ServerSideEncryption 治理策略）
     *
     * @param path        目标对象路径
     * @param in          输入流
     * @param contentType MIME 类型
     * @param size        文件字节大小
     * @return 存储元数据对象
     */
    public StorageObject uploadStandard(String path, InputStream in, String contentType, long size) {
        return storageTemplate.use(S3_SOURCE).upload(path, in, contentType, size);
    }

    /**
     * 场景 6.2：获取存储对象详情（用于提取存储类别与服务端加密状态等元数据）
     *
     * @param path 目标对象路径
     * @return 包含 storageClass 与 serverSideEncryption 的元数据
     */
    public StorageObject getObjectDetails(String path) {
        return storageTemplate.use(S3_SOURCE).getMetadata(path);
    }

    /**
     * 场景 7.1：S3 存储源连通性探测 (ping)
     *
     * @return 是否健康可达
     */
    public boolean pingS3Source() {
        return storageTemplate.use(S3_SOURCE).ping();
    }

    /**
     * 场景 7.2：提取 S3 存储源连接池与配置诊断指标字典
     *
     * @return 诊断参数字典
     */
    public Map<String, Object> getS3Diagnostics() {
        return storageTemplate.use(S3_SOURCE).getDiagnostics();
    }

    /**
     * 辅助清理方法：删除指定的测试对象（幂等）
     *
     * @param path 目标对象路径
     */
    public void deleteObject(String path) {
        storageTemplate.use(S3_SOURCE).delete(path);
    }
}
