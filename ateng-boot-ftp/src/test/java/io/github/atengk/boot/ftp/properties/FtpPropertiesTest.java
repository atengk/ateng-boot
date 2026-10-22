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
}
