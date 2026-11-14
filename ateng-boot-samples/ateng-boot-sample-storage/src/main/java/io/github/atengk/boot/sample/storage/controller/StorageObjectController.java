package io.github.atengk.boot.sample.storage.controller;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.storage.model.ContractAuditSnapshot;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业电子合同与档案核心对象存取控制器
 * 演示表单上传、自定义业务元数据注入、纯文本批注与审计快照 JSON 便捷读写、跨路径零拷贝复制与归档移动、目录树分页检索及天然幂等防误删清理
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/storage/object")
@RequiredArgsConstructor
public class StorageObjectController {

    private final StorageTemplate storageTemplate;
    private final TransactionTemplate transactionTemplate;

    /**
     * 上传电子合同或档案文件到默认存储源（自动执行路径归一化与魔数防伪装嗅探）
     *
     * @param file 上传的合同表单文件
     * @param path 可选自定义存储路径（为空则使用清洗后的原始文件名）
     * @return 存储元数据强类型契约
     */
    @PostMapping("/upload")
    public Result<StorageObject> upload(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam(value = "path", required = false) String path) {
        String targetPath = (path != null && !path.isBlank()) ? path : file.getOriginalFilename();
        StorageObject object = storageTemplate.upload(targetPath, file);
        return Result.success(object);
    }

    /**
     * 上传电子合同并注入自定义业务扩展元数据（如合同编号 contractNo、签署人 signer、归属部门 dept）
     *
     * @param file         上传的表单文件
     * @param path         目标存储相对路径
     * @param userMetadata 用户自定义业务元数据字典
     * @return 包含扩展元数据的存储对象契约
     */
    @PostMapping("/upload-with-metadata")
    public Result<StorageObject> uploadWithMetadata(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam Map<String, String> userMetadata) {
        Map<String, String> customMeta = new HashMap<>(userMetadata);
        customMeta.remove("path");
        StorageObject object = storageTemplate.upload(path, file, customMeta);
        return Result.success(object);
    }

    /**
     * 便捷写入合同审批批注纯文本字符串（默认 UTF-8 编码）
     *
     * @param path    目标存储路径
     * @param content 审批批注文本内容
     * @return 存储对象元数据契约
     */
    @PostMapping("/write-string")
    public Result<StorageObject> writeString(
            @RequestParam("path") @NotBlank(message = "存储路径不能为空") String path,
            @RequestParam("content") @NotNull(message = "文本内容不能为空") String content) {
        StorageObject object = storageTemplate.uploadString(path, content);
        return Result.success(object);
    }

    /**
     * 便捷读取合同审批批注纯文本内容
     *
     * @param path 目标存储路径
     * @return 纯文本字符串响应契约
     */
    @GetMapping("/read-string")
    public Result<String> readString(@RequestParam("path") @NotBlank(message = "读取路径不能为空") String path) {
        String content = storageTemplate.downloadString(path);
        return Result.success(content);
    }

    /**
     * 便捷写入强类型电子合同归档审计快照 JSON 对象
     *
     * @param path     目标存储路径
     * @param snapshot 合同审计快照载荷（挂载 JSR-303 校验）
     * @return 存储对象元数据契约
     */
    @PostMapping("/write-json")
    public Result<StorageObject> writeJson(
            @RequestParam("path") @NotBlank(message = "存储路径不能为空") String path,
            @Valid @NotNull(message = "合同审计快照载荷不能为空") @RequestBody ContractAuditSnapshot snapshot) {
        StorageObject object = storageTemplate.uploadJson(path, snapshot);
        return Result.success(object);
    }

    /**
     * 便捷读取并反序列化电子合同归档审计快照 JSON 对象
     *
     * @param path 目标存储路径
     * @return 强类型合同审计快照响应契约
     */
    @GetMapping("/read-json")
    public Result<ContractAuditSnapshot> readJson(@RequestParam("path") @NotBlank(message = "读取路径不能为空") String path) {
        ContractAuditSnapshot snapshot = storageTemplate.downloadJson(path, ContractAuditSnapshot.class);
        return Result.success(snapshot);
    }

    /**
     * 跨路径零拷贝复制合同存储对象（保留源文件）
     *
     * @param sourcePath 源对象相对路径
     * @param targetPath 目标副本相对路径
     * @return 复制结果契约
     */
    @PostMapping("/copy")
    public Result<Boolean> copy(
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
        boolean success = storageTemplate.copy(sourcePath, targetPath);
        return Result.success(success);
    }

    /**
     * 跨路径原子移动合同存储对象（如从 staging 暂存目录移入 archive 正式归档目录，自动移除源文件）
     *
     * @param sourcePath 源对象相对路径
     * @param targetPath 目标归档相对路径
     * @return 移动结果契约
     */
    @PostMapping("/move")
    public Result<Boolean> move(
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
        boolean success = storageTemplate.move(sourcePath, targetPath);
        return Result.success(success);
    }

    /**
     * 列举指定归档目录前缀下的存储对象（自动过滤系统隐藏项与 .chunks 碎片目录）
     *
     * @param prefix    目录路径前缀（为空表示列举根目录）
     * @param recursive 是否深度递归遍历子目录
     * @return 存储对象与虚拟目录列表契约
     */
    @GetMapping("/list")
    public Result<List<StorageObject>> list(
            @RequestParam(value = "prefix", required = false) String prefix,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        List<StorageObject> list = storageTemplate.list(prefix, recursive);
        return Result.success(list);
    }

    /**
     * 分页检索指定归档目录前缀下的存储对象
     *
     * @param prefix    目录路径前缀（为空表示检索根目录）
     * @param pageQuery 标准分页查询参数（current 与 size）
     * @param recursive 是否深度递归遍历子目录
     * @return 标准分页结果契约
     */
    @GetMapping("/list-page")
    public Result<PageResult<StorageObject>> listPage(
            @RequestParam(value = "prefix", required = false) String prefix,
            @Valid @ModelAttribute PageQuery pageQuery,
            @RequestParam(value = "recursive", defaultValue = "false") boolean recursive) {
        PageResult<StorageObject> pageResult = storageTemplate.listPage(prefix, pageQuery, recursive);
        return Result.success(pageResult);
    }

    /**
     * 删除指定存储对象（天然幂等，文件不存在亦静默返回 true）
     *
     * @param path 目标对象相对路径
     * @return 删除结果契约
     */
    @PostMapping("/delete")
    public Result<Boolean> delete(@RequestParam("path") @NotBlank(message = "删除路径不能为空") String path) {
        boolean success = storageTemplate.delete(path);
        return Result.success(success);
    }

    /**
     * 按前缀流式批量删除存储对象（内置空前缀、根路径与通配符防误删强行熔断）
     *
     * @param prefix 待清理的目标路径前缀
     * @return 批量清理结果契约
     */
    @PostMapping("/delete-prefix")
    public Result<Boolean> deletePrefix(@RequestParam("prefix") @NotBlank(message = "删除前缀不能为空") String prefix) {
        boolean success = storageTemplate.deletePrefix(prefix);
        return Result.success(success);
    }

    /**
     * 级联删除指定目录及其内部所有存储对象（天然幂等且受防误删熔断保护）
     *
     * @param dirPath 目标目录相对路径
     * @return 目录清理结果契约
     */
    @PostMapping("/delete-directory")
    public Result<Boolean> deleteDirectory(@RequestParam("dirPath") @NotBlank(message = "目录路径不能为空") String dirPath) {
        boolean success = storageTemplate.deleteDirectory(dirPath);
        return Result.success(success);
    }

    /**
     * 单参快捷上传电子合同（自动生成 yyyy/MM/dd/{uuid}.{ext} 日期归档安全路径）
     *
     * @param file 上传的合同表单文件
     * @return 存储元数据对象契约
     */
    @PostMapping("/upload-quick")
    public Result<StorageObject> uploadQuick(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file) {
        StorageObject object = storageTemplate.upload(file);
        return Result.success(object);
    }

    /**
     * 幂等防覆盖上传电子合同（当目标路径已存在时抛出 STORAGE_OBJECT_ALREADY_EXISTS 40024 阻断）
     *
     * @param file 上传的合同表单文件
     * @param path 目标存储相对路径
     * @return 存储元数据对象契约
     */
    @PostMapping("/upload-if-absent")
    public Result<StorageObject> uploadIfAbsent(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path) {
        StorageObject object = storageTemplate.uploadIfAbsent(file, path);
        return Result.success(object);
    }

    /**
     * 跨存储源流式复制对象（支持从当前活动源复制到指定目标存储源）
     *
     * @param sourcePath      当前源对象相对路径
     * @param targetSourceKey 目标存储源标识（如 backup、s3）
     * @param targetPath      目标存储源内的目标路径
     * @return 目标存储源中新生成对象的元数据契约
     */
    @PostMapping("/copy-to")
    public Result<StorageObject> copyTo(
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetSourceKey") @NotBlank(message = "目标存储源标识不能为空") String targetSourceKey,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
        StorageObject copied = storageTemplate.copyTo(sourcePath, targetSourceKey, targetPath);
        return Result.success(copied);
    }

    /**
     * 跨存储源流式迁移对象（先复制到目标存储源并校验落盘，再幂等移除源对象）
     *
     * @param sourcePath      当前源对象相对路径
     * @param targetSourceKey 目标存储源标识（如 backup、s3）
     * @param targetPath      目标存储源内的目标路径
     * @return 目标存储源中新生成对象的元数据契约
     */
    @PostMapping("/move-to")
    public Result<StorageObject> moveTo(
            @RequestParam("sourcePath") @NotBlank(message = "源路径不能为空") String sourcePath,
            @RequestParam("targetSourceKey") @NotBlank(message = "目标存储源标识不能为空") String targetSourceKey,
            @RequestParam("targetPath") @NotBlank(message = "目标路径不能为空") String targetPath) {
        StorageObject moved = storageTemplate.moveTo(sourcePath, targetSourceKey, targetPath);
        return Result.success(moved);
    }

    /**
     * 事务感知上传电子合同（事务提交则保留文件；事务回滚则自动补偿删除孤儿文件）
     *
     * @param file            上传的表单文件
     * @param path            目标存储相对路径
     * @param simulateFailure 是否在上传后模拟业务异常以触发事务回滚补偿
     * @return 提交成功后的存储对象元数据契约
     */
    @PostMapping("/upload-with-rollback-delete")
    public Result<StorageObject> uploadWithRollbackDelete(
            @RequestParam("file") @NotNull(message = "上传文件不能为空") MultipartFile file,
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "simulateFailure", defaultValue = "false") boolean simulateFailure) {
        StorageObject uploaded = transactionTemplate.execute(status -> {
            StorageObject object = storageTemplate.uploadWithRollbackDelete(file, path);
            if (simulateFailure) {
                throw new StorageException(StorageResultCode.OPERATION_FAILED, "模拟业务事务失败，触发回滚删除孤儿文件: " + path);
            }
            return object;
        });
        return Result.success(uploaded);
    }

    /**
     * 事务感知提交后延迟删除对象（事务提交后才真正物理删除；若事务回滚则完整保留原对象）
     *
     * @param path            目标存储相对路径
     * @param simulateFailure 是否在注册延迟删除后模拟业务异常触发回滚
     * @return 延迟删除注册与提交执行结果
     */
    @PostMapping("/delete-after-commit")
    public Result<Boolean> deleteAfterCommit(
            @RequestParam("path") @NotBlank(message = "目标路径不能为空") String path,
            @RequestParam(value = "simulateFailure", defaultValue = "false") boolean simulateFailure) {
        transactionTemplate.executeWithoutResult(status -> {
            storageTemplate.deleteAfterCommit(path);
            if (simulateFailure) {
                throw new StorageException(StorageResultCode.OPERATION_FAILED, "模拟业务事务失败，取消提交后删除: " + path);
            }
        });
        return Result.success(Boolean.TRUE);
    }
}
