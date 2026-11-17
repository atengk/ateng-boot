package io.github.atengk.boot.ftp.properties;

import io.github.atengk.boot.ftp.enums.FtpFileType;
import io.github.atengk.boot.ftp.enums.FtpMode;
import io.github.atengk.boot.ftp.enums.FtpsDataChannelProtection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FTP 配置属性单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("FTP 配置属性单元测试")
class FtpPropertiesTest {

    @Test
    @DisplayName("测试 FTP 默认配置参数生产就绪不变量")
    void testDefaultProperties() {
        FtpProperties properties = new FtpProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getHost()).isEqualTo("localhost");
        assertThat(properties.getPort()).isEqualTo(21);
        assertThat(properties.getUsername()).isEqualTo("anonymous");
        assertThat(properties.getPassword()).isEmpty();
        assertThat(properties.getMode()).isEqualTo(FtpMode.PASSIVE);
        assertThat(properties.getFileType()).isEqualTo(FtpFileType.BINARY);
        assertThat(properties.getControlEncoding()).isEqualTo("UTF-8");
        assertThat(properties.getConnectTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getDataTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getControlKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getBufferSize()).isEqualTo(DataSize.ofKilobytes(64));
        assertThat(properties.isAtomicUpload()).isTrue();
        assertThat(properties.getTempFileSuffix()).isEqualTo(".tmp");
        assertThat(properties.isAutoCreateDirectories()).isTrue();
        assertThat(properties.getServerTimeZone()).isEqualTo("Asia/Shanghai");

        // FTPS 默认配置
        FtpsProperties ftps = properties.getFtps();
        assertThat(ftps).isNotNull();
        assertThat(ftps.isEnabled()).isFalse();
        assertThat(ftps.getProtocol()).isEqualTo("TLSv1.3");
        assertThat(ftps.isImplicit()).isFalse();
        assertThat(ftps.getDataChannelProtection()).isEqualTo(FtpsDataChannelProtection.PRIVATE);
        assertThat(ftps.isTrustAll()).isFalse();

        // Pool 默认配置
        FtpPoolProperties pool = properties.getPool();
        assertThat(pool).isNotNull();
        assertThat(pool.getMaxTotal()).isEqualTo(16);
        assertThat(pool.getMaxIdle()).isEqualTo(8);
        assertThat(pool.getMinIdle()).isEqualTo(2);
        assertThat(pool.getMaxWait()).isEqualTo(Duration.ofSeconds(5));
        assertThat(pool.isTestOnBorrow()).isTrue();
        assertThat(pool.isTestOnReturn()).isFalse();
        assertThat(pool.isTestWhileIdle()).isTrue();
        assertThat(pool.getTimeBetweenEvictionRuns()).isEqualTo(Duration.ofMinutes(1));
        assertThat(pool.getMinEvictableIdleTime()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("测试 mergeFrom 继承父级连接池与 FTPS 缺省配置且隔离可变引用")
    void testMergeFromInheritsDefaultsWithoutOverwritingExplicitChildSettings() {
        FtpProperties parent = new FtpProperties();
        parent.setConnectTimeout(Duration.ofSeconds(5));
        parent.setDataTimeout(Duration.ofSeconds(12));
        parent.setBufferSize(DataSize.ofKilobytes(128));
        parent.getPool().setMaxTotal(32);
        parent.getPool().setMaxWait(Duration.ofSeconds(2));
        parent.getFtps().setEnabled(true);
        parent.getFtps().setTrustStorePath("classpath:trust.p12");

        FtpProperties child = new FtpProperties();
        child.setHost("10.0.0.2");
        child.setPort(2122);
        child.getPool().setMaxTotal(6);

        child.mergeFrom(parent);

        assertThat(child.getHost()).isEqualTo("10.0.0.2");
        assertThat(child.getPort()).isEqualTo(2122);
        assertThat(child.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(child.getDataTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(child.getBufferSize()).isEqualTo(DataSize.ofKilobytes(128));
        assertThat(child.getPool().getMaxTotal()).isEqualTo(6);
        assertThat(child.getPool().getMaxWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(child.getFtps().isEnabled()).isTrue();
        assertThat(child.getFtps().getTrustStorePath()).isEqualTo("classpath:trust.p12");

        // 修改子节点不应污染父节点
        child.getPool().setMaxTotal(99);
        child.getFtps().setTrustStorePath("classpath:other.p12");
        assertThat(parent.getPool().getMaxTotal()).isEqualTo(32);
        assertThat(parent.getFtps().getTrustStorePath()).isEqualTo("classpath:trust.p12");

        // 子节点显式设置恰好等于类默认值的属性 (false / 16) 时，严禁被父级配置覆盖
        FtpProperties explicitChild = new FtpProperties();
        explicitChild.getPool().setMaxTotal(16);
        explicitChild.getFtps().setEnabled(false);
        explicitChild.mergeFrom(parent);

        assertThat(explicitChild.getPool().getMaxTotal()).isEqualTo(16);
        assertThat(explicitChild.getPool().getMaxWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(explicitChild.getFtps().isEnabled()).isFalse();
        assertThat(explicitChild.getFtps().getTrustStorePath()).isEqualTo("classpath:trust.p12");
    }
}
