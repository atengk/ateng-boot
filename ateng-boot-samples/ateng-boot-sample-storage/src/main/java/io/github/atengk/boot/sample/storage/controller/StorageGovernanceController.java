package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 存储多源路由、跨源双活备份与云原生 S3 治理控制器
 * 演示声明式 @Storage 与编程式 use(...) 跨源双活备份、对象业务标签（Tagging）生命周期治理、
 * 冷热分层与服务端加密元数据探查、连通性探针（ping）与连接池运行诊断
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/storage/governance")
@RequiredArgsConstructor
public class StorageGovernanceController {

    private final StorageTemplate storageTemplate;
    private final FileStorageDemoService fileStorageDemoService;

    /**
     * 上传机密合同到 backup 隔离备份源（演示 @Storage 声明式切源）
     *
     * @param file 上传的表单文件
     * @param path 可选目标存储路径
     * @return 备份源存储元数据
     * @throws IOException IO 读取异常
     */
    @PostMapping("/upload-backup")
    public Result<StorageObject> uploadToBackup(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) throws IOException {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            StorageObject object = fileStorageDemoService.uploadToBackup(
                    targetPath,
                    in,
                    file.getContentType(),
                    file.getSize()
            );
            return Result.success(object);
        }
    }

    /**
     * 将文件从默认源同步备份至 backup 辅助备份源（跨源业务编排）
     *
     * @param sourcePath 默认源相对路径
     * @param backupPath 备份源相对路径
     * @return 备份后的存储元数据
     */
    @PostMapping("/sync-backup")
    public Result<StorageObject> syncBackup(
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("backupPath") @NotBlank(message = "备份路径不能为空") String backupPath) {
        StorageObject object = fileStorageDemoService.backupToSecondarySource(sourcePath, backupPath);
        return Result.success(object);
    }

    /**
     * 列举 backup 备份源下的所有归档对象（声明式切源检索）
     *
     * @param prefix 可选前缀
     * @return 备份源存储对象列表
     */
    @GetMapping("/list-backup")
    public Result<List<StorageObject>> listBackup(
            @RequestParam(value = "prefix", required = false) String prefix) {
        List<StorageObject> list = fileStorageDemoService.listBackupFiles(prefix);
        return Result.success(list);
    }

    /**
     * 设置或全量覆盖对象的业务标签（打通云端生命周期与部门财务分账审计）
     *
     * @param path   目标对象相对路径
     * @param tags   标签键值对字典
     * @param source 目标存储源（默认 s3）
     * @return 统一成功响应
     */
    @PostMapping("/tags")
    public Result<Void> setObjectTags(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestBody @NotNull(message = "标签字典不能为空") Map<String, String> tags,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        storageTemplate.use(source).setObjectTags(path, tags);
        return Result.success();
    }

    /**
     * 读取对象关联的全部业务标签
     *
     * @param path   目标对象相对路径
     * @param source 目标存储源（默认 s3）
     * @return 标签映射字典
     */
    @GetMapping("/tags")
    public Result<Map<String, String>> getObjectTags(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        Map<String, String> tags = storageTemplate.use(source).getObjectTags(path);
        return Result.success(tags);
    }

    /**
     * 清空对象关联的所有业务标签（天然幂等）
     *
     * @param path   目标对象相对路径
     * @param source 目标存储源（默认 s3）
     * @return 统一成功响应
     */
    @DeleteMapping("/tags")
    public Result<Void> deleteObjectTags(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        storageTemplate.use(source).deleteObjectTags(path);
        return Result.success();
    }

    /**
     * 标准对象上传（由底层驱动根据配置自动挂载 StorageClass 与 ServerSideEncryption）
     *
     * @param file   上传的表单文件
     * @param path   可选存储相对路径
     * @param source 目标存储源（默认 s3）
     * @return 存储元数据契约
     * @throws IOException IO 读取异常
     */
    @PostMapping("/upload-standard")
    public Result<StorageObject> uploadStandard(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam(value = "path", required = false) String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) throws IOException {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            StorageObject object = storageTemplate.use(source).upload(targetPath, in, file.getContentType(), file.getSize());
            return Result.success(object);
        }
    }

    /**
     * 获取存储对象元数据详情（用于反查 StorageClass 冷热分层与 ServerSideEncryption 加密状态）
     *
     * @param path   目标对象相对路径
     * @param source 目标存储源（默认 s3）
     * @return 强类型对象元数据
     */
    @GetMapping("/metadata")
    public Result<StorageObject> getMetadata(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        StorageObject object = storageTemplate.use(source).getMetadata(path);
        return Result.success(object);
    }

    /**
     * 探查指定存储源连通性 (ping)
     *
     * @param source 目标存储源（默认 s3）
     * @return 是否健康可达
     */
    @GetMapping("/ping")
    public Result<Boolean> ping(
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        boolean healthy = storageTemplate.use(source).ping();
        return Result.success(healthy);
    }

    /**
     * 提取指定存储源连接池与配置诊断指标字典
     *
     * @param source 目标存储源（默认 s3）
     * @return 诊断参数字典
     */
    @GetMapping("/diagnostics")
    public Result<Map<String, Object>> getDiagnostics(
            @RequestParam(value = "source", defaultValue = "s3") String source) {
        Map<String, Object> diagnostics = storageTemplate.use(source).getDiagnostics();
        return Result.success(diagnostics);
    }
}
