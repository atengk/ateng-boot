package io.github.atengk.boot.ftp.registry;

import io.github.atengk.boot.ftp.exception.FtpException;
import io.github.atengk.boot.ftp.exception.FtpResultCode;
import io.github.atengk.boot.ftp.properties.FtpProperties;
import io.github.atengk.boot.ftp.template.FtpTemplate;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.pool2.ObjectPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * FtpClientRegistry 多节点注册表单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FtpClientRegistry 多节点注册表单元测试")
class FtpClientRegistryTest {

    @Test
    @DisplayName("测试具名节点注册、寻址与默认兜底回退")
    @SuppressWarnings("unchecked")
    void testRegisterAndRetrieve() {
        FtpClientRegistry registry = new FtpClientRegistry();

        ObjectPool<FTPClient> defaultPool = Mockito.mock(ObjectPool.class);
        FtpProperties defaultProps = new FtpProperties();
        FtpTemplate defaultTemplate = new FtpTemplate(defaultPool, defaultProps, registry, FtpClientRegistry.DEFAULT_SERVER);

        ObjectPool<FTPClient> archivePool = Mockito.mock(ObjectPool.class);
        FtpProperties archiveProps = new FtpProperties();
        FtpTemplate archiveTemplate = new FtpTemplate(archivePool, archiveProps, registry, "archive");

        registry.register(FtpClientRegistry.DEFAULT_SERVER, defaultTemplate, defaultPool);
        registry.register("archive", archiveTemplate, archivePool);

        // 验证精确寻址
        assertThat(registry.getTemplate("archive")).isSameAs(archiveTemplate);
        assertThat(registry.getPool("archive")).isSameAs(archivePool);

        // 验证默认寻址与空回退
        assertThat(registry.getDefaultTemplate()).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate(null)).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate("")).isSameAs(defaultTemplate);
        assertThat(registry.getTemplate("   ")).isSameAs(defaultTemplate);

        // 验证节点集合与包含性
        assertThat(registry.contains("archive")).isTrue();
        assertThat(registry.contains("not_exist")).isFalse();
        assertThat(registry.getServerNames()).containsExactlyInAnyOrder("default", "archive");
    }

    @Test
    @DisplayName("测试获取未注册服务节点抛出 SERVER_NOT_FOUND 异常")
    void testGetNonExistentServer() {
        FtpClientRegistry registry = new FtpClientRegistry();

        assertThatThrownBy(() -> registry.getTemplate("unknown_node"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

        assertThatThrownBy(() -> registry.getPool("unknown_node"))
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("测试 close 回调确定性关闭释放所有底层连接池")
    @SuppressWarnings("unchecked")
    void testCloseDestroysAllPools() {
        FtpClientRegistry registry = new FtpClientRegistry();

        ObjectPool<FTPClient> pool1 = Mockito.mock(ObjectPool.class);
        ObjectPool<FTPClient> pool2 = Mockito.mock(ObjectPool.class);

        FtpTemplate template1 = new FtpTemplate(pool1, new FtpProperties(), registry, "default");
        FtpTemplate template2 = new FtpTemplate(pool2, new FtpProperties(), registry, "node2");

        registry.register("default", template1, pool1);
        registry.register("node2", template2, pool2);

        registry.close();

        verify(pool1).close();
        verify(pool2).close();
        assertThat(registry.getServerNames()).isEmpty();
    }

    @Test
    @DisplayName("测试空注册表获取默认模板或连接池抛出受管异常")
    void testEmptyRegistryThrowsException() {
        FtpClientRegistry registry = new FtpClientRegistry();

        assertThatThrownBy(registry::getDefaultTemplate)
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());

        assertThatThrownBy(registry::getDefaultPool)
                .isInstanceOf(FtpException.class)
                .extracting(e -> ((FtpException) e).getCode())
                .isEqualTo(FtpResultCode.SERVER_NOT_FOUND.getCode());
    }
}
