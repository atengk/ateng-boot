package io.github.atengk.boot.storage.autoconfigure;

import io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider;
import io.github.atengk.boot.storage.controller.LocalStorageDownloadController;
import io.github.atengk.boot.storage.controller.LocalStorageUploadController;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.properties.LocalStorageProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * 本地存储预签名端点与 Sa-Token 免登录白名单可选协同配置类
 * <p>
 * 独立隔离 {@link SaTokenWhitelistProvider} 类加载引用，确保在未引入 {@code ateng-boot-satoken} 时
 * 不会引发 {@link NoClassDefFoundError}；默认严格关闭（{@code false}），仅在上层显式开启时才按需放行对应端点。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-24
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "io.github.atengk.boot.satoken.spi.SaTokenWhitelistProvider")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ateng.boot.storage.local", name = "endpoint-enabled", havingValue = "true", matchIfMissing = true)
@Conditional(LocalStorageEndpointCondition.class)
class LocalStorageSaTokenWhitelistConfiguration {

    /**
     * 按需将本地存储预签名只读下载端点注册到 Sa-Token 动态白名单
     *
     * @param properties             存储配置属性
     * @param uploadControllerProvider 上传端点控制器可选提供者（用于检测同路径冲突）
     * @return 下载端点白名单提供者
     */
    @Bean(name = "localStorageDownloadWhitelistProvider")
    @ConditionalOnBean(LocalStorageDownloadController.class)
    @ConditionalOnMissingBean(name = "localStorageDownloadWhitelistProvider")
    @ConditionalOnProperty(prefix = "ateng.boot.storage.local", name = "whitelist-presigned-download", havingValue = "true", matchIfMissing = false)
    public SaTokenWhitelistProvider localStorageDownloadWhitelistProvider(
            StorageProperties properties,
            ObjectProvider<LocalStorageUploadController> uploadControllerProvider) {
        LocalStorageProperties local = properties.getLocal() != null ? properties.getLocal() : new LocalStorageProperties();
        String downloadPath = StorageSecurityUtil.normalizeEndpointPath(
                local.getDownloadEndpoint(), LocalDriver.DEFAULT_DOWNLOAD_ENDPOINT);
        String uploadPath = StorageSecurityUtil.normalizeEndpointPath(
                local.getUploadEndpoint(), LocalDriver.DEFAULT_UPLOAD_ENDPOINT);
        validateSamePathConflict(local, true, uploadControllerProvider.getIfAvailable() != null, downloadPath, uploadPath);
        return () -> Set.of(downloadPath);
    }

    /**
     * 按需将本地存储预签名直传写入端点注册到 Sa-Token 动态白名单
     *
     * @param properties               存储配置属性
     * @param downloadControllerProvider 下载端点控制器可选提供者（用于检测同路径冲突）
     * @return 上传端点白名单提供者
     */
    @Bean(name = "localStorageUploadWhitelistProvider")
    @ConditionalOnBean(LocalStorageUploadController.class)
    @ConditionalOnMissingBean(name = "localStorageUploadWhitelistProvider")
    @ConditionalOnProperty(prefix = "ateng.boot.storage.local", name = "whitelist-presigned-upload", havingValue = "true", matchIfMissing = false)
    public SaTokenWhitelistProvider localStorageUploadWhitelistProvider(
            StorageProperties properties,
            ObjectProvider<LocalStorageDownloadController> downloadControllerProvider) {
        LocalStorageProperties local = properties.getLocal() != null ? properties.getLocal() : new LocalStorageProperties();
        String downloadPath = StorageSecurityUtil.normalizeEndpointPath(
                local.getDownloadEndpoint(), LocalDriver.DEFAULT_DOWNLOAD_ENDPOINT);
        String uploadPath = StorageSecurityUtil.normalizeEndpointPath(
                local.getUploadEndpoint(), LocalDriver.DEFAULT_UPLOAD_ENDPOINT);
        validateSamePathConflict(local, downloadControllerProvider.getIfAvailable() != null, true, downloadPath, uploadPath);
        return () -> Set.of(uploadPath);
    }

    private void validateSamePathConflict(
            LocalStorageProperties local,
            boolean downloadControllerPresent,
            boolean uploadControllerPresent,
            String downloadPath,
            String uploadPath) {
        if (!downloadControllerPresent || !uploadControllerPresent) {
            return;
        }
        if (local.isWhitelistPresignedDownload() == local.isWhitelistPresignedUpload()) {
            return;
        }
        if (downloadPath.equals(uploadPath)) {
            throw new IllegalStateException(
                    "本地存储下载端点与上传端点同路径 [" + downloadPath + "] 冲突："
                            + "Sa-Token 白名单仅按请求路径放行而不区分 HTTP Method，当前 whitelist-presigned-download="
                            + local.isWhitelistPresignedDownload() + ", whitelist-presigned-upload="
                            + local.isWhitelistPresignedUpload() + " 会导致未开启白名单的端点也被匿名放行，"
                            + "请为 download-endpoint 与 upload-endpoint 配置不同路径，或停用上传端点后自建接口");
        }
    }
}
