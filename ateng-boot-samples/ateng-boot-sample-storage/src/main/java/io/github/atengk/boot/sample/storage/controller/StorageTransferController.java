package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 流式传输、断点续传、批量归档与极速秒传控制器
 * 演示 Spring StorageResource HTTP Range 206 断点续传、Byte-Range 字节范围切片读取、未知流长双阶自适应流式上传防 OOM、
 * 多份财务凭证零磁盘临时文件纯流式 ZIP 打包以及基于 StorageHashUtil 的知识库文档极速秒传
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/storage/transfer")
@RequiredArgsConstructor
public class StorageTransferController {

    private final StorageTemplate storageTemplate;

    /**
     * 流式下载存储对象（桥接 Spring StorageResource，原生支持 HTTP Range 206 Partial Content 断点续传）
     *
     * @param path 目标相对路径
     * @return Spring Resource 流式响应实体
     * @throws IOException IO 读取异常
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam("path") @NotBlank(message = "下载路径不能为空") String path) throws IOException {
        StorageResource resource = storageTemplate.getResource(path);
        String filename = resource.getFilename();
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename)
                .contentType(MediaType.parseMediaType(resource.getContentType()))
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * 分段切片字节范围读取（Byte-Range Read，适用于大文件断点续传与音视频流式 Seek 定位）
     *
     * @param path        目标对象相对路径
     * @param startOffset 起始字节偏移量（0-based，>= 0）
     * @param length      读取字节长度（> 0）
     * @param source      目标存储源标识（默认 s3，可选 default / backup / s3）
     * @return 局部切片文本响应契约
     */
    @GetMapping("/download-range")
    public Result<String> downloadRange(
            @RequestParam("path") @NotBlank(message = "下载路径不能为空") String path,
            @RequestParam("startOffset") @Min(value = 0, message = "起始偏移量不能为负数") long startOffset,
            @RequestParam("length") @Min(value = 1, message = "读取长度必须大于 0") long length,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        try (InputStream in = storageTemplate.use(source).download(path, startOffset, length)) {
            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (StorageException se) {
            throw se;
        } catch (Exception e) {
            throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "读取分段字节范围失败: " + path, e);
        }
    }

    /**
     * 未知流长双阶自适应流式分片缓冲上传（size 传 -1，内存恒定 <= 5MB 防 OOM）
     *
     * @param path        目标对象相对路径
     * @param content     模拟动态生成的流式报表内容
     * @param contentType MIME 内容类型（默认 text/plain）
     * @param source      目标存储源（默认 s3）
     * @return 存储对象元数据契约
     */
    @PostMapping("/upload-adaptive")
    public Result<StorageObject> uploadAdaptive(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam("content") @NotNull(message = "流数据内容不能为空") String content,
            @RequestParam(value = "contentType", defaultValue = "text/plain") String contentType,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream in = new ByteArrayInputStream(payload)) {
            return Result.success(storageTemplate.use(source).upload(path, in, contentType, -1L));
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "自适应流式上传失败: " + path, e);
        }
    }

    /**
     * 多份财务凭证零磁盘临时文件纯流式打包 ZIP 响应下载（内置同名条目自动递增消解与 RFC 5987 中文编码）
     *
     * @param paths       待打包的存储对象相对路径列表
     * @param zipFilename 客户端下载的 ZIP 归档文件名
     * @param response    HTTP 响应对象
     */
    @GetMapping("/download-zip")
    public void downloadZip(
            @RequestParam("paths") @NotEmpty(message = "打包路径列表不能为空") List<@NotBlank(message = "打包路径不能为空") String> paths,
            @RequestParam(value = "zipFilename", defaultValue = "2026Q1_vouchers.zip") String zipFilename,
            HttpServletResponse response) {
        storageTemplate.downloadAsZip(paths, response, zipFilename);
    }

    /**
     * 流式零拷贝计算已存储对象的散列哈希指纹（支持 MD5, SHA1, SHA256, CRC32）
     *
     * @param path      目标存储对象相对路径
     * @param algorithm 散列算法（默认 SHA256）
     * @return 小写十六进制散列指纹字符串
     */
    @GetMapping("/hash")
    public Result<String> calculateHash(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "algorithm", defaultValue = "SHA256") HashAlgorithm algorithm) {
        String hash = storageTemplate.calculateHash(path, algorithm);
        return Result.success(hash);
    }

    /**
     * 企业知识库文档极速秒传接口：若哈希指纹池中已存在对应数据块，直接执行零拷贝 copy 毫秒级落盘
     *
     * @param targetPath 目标存放相对路径
     * @param hash       文件 SHA-256 散列指纹
     * @return 秒传命中返回 200 与元数据；未命中返回 40404 引导前端降级走普通直传
     */
    @PostMapping("/fast-upload")
    public Result<StorageObject> fastUpload(
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath,
            @RequestParam("hash") @NotBlank(message = "散列哈希不能为空") String hash) {
        String poolSourcePath = "hash-pool/" + hash + ".dat";
        if (!storageTemplate.exists(poolSourcePath)) {
            return Result.fail(TransferResultCode.FAST_UPLOAD_MISSED);
        }
        storageTemplate.copy(poolSourcePath, targetPath);
        StorageObject metadata = storageTemplate.getMetadata(targetPath);
        return Result.success(metadata);
    }

    /**
     * 传输与秒传业务响应状态码
     */
    public enum TransferResultCode implements IResultCode {
        FAST_UPLOAD_MISSED(40404, "文件哈希未命中秒传池，请走普通上传流");

        private final Integer code;
        private final String msg;

        TransferResultCode(Integer code, String msg) {
            this.code = code;
            this.msg = msg;
        }

        @Override
        public Integer getCode() {
            return code;
        }

        @Override
        public String getMsg() {
            return msg;
        }
    }
}
