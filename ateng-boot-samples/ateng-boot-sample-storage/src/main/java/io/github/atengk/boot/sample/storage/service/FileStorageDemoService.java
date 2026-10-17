package io.github.atengk.boot.sample.storage.service;

import io.github.atengk.boot.storage.annotation.Storage;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * 存储业务示例服务
 * 演示声明式注解切源 @Storage 与编程式 use(...) 切源
 *
 * @author Ateng
 * @since 2026-10-17
 */
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
}
