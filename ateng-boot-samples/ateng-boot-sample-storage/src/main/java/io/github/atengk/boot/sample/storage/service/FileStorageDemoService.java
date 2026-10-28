package io.github.atengk.boot.sample.storage.service;

import io.github.atengk.boot.storage.annotation.Storage;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 存储业务示例服务
 * 演示声明式注解切源 @Storage、编程式 use(...) 切源以及业务层组合调度能力
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageDemoService {

    private final StorageTemplate storageTemplate;

    /**
     * 通过声明式注解切源到 backup 备份源
     *
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @param size        数据流字节大小
     * @return 存储元数据对象
     */
    @Storage("backup")
    public StorageObject uploadToBackup(String path, InputStream in, String contentType, long size) {
        return storageTemplate.upload(path, in, contentType, size);
    }

    /**
     * 通过编程式 use(...) 链式切源到指定命名源
     *
     * @param sourceKey   目标存储源标识
     * @param path        目标相对路径
     * @param in          输入流
     * @param contentType MIME 内容类型
     * @param size        数据流字节大小
     * @return 存储元数据对象
     */
    public StorageObject uploadViaProgrammaticRouting(String sourceKey, String path, InputStream in, String contentType, long size) {
        return storageTemplate.use(sourceKey).upload(path, in, contentType, size);
    }

    /**
     * 业务编排：将文件跨源备份（从默认源读取并写入备份源）
     *
     * @param sourcePath 源路径
     * @param backupPath 备份路径
     * @return 备份后的存储元数据
     */
    public StorageObject backupToSecondarySource(String sourcePath, String backupPath) {
        byte[] data = storageTemplate.downloadBytes(sourcePath);
        return storageTemplate.use("backup").uploadBytes(backupPath, data);
    }

    /**
     * 业务编排：列举备份源下的所有文件
     *
     * @param prefix 前缀
     * @return 备份源文件列表
     */
    @Storage("backup")
    public List<StorageObject> listBackupFiles(String prefix) {
        return storageTemplate.list(prefix, true);
    }
}
