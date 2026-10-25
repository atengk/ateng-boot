package io.github.atengk.boot.tcp.properties;

import io.github.atengk.boot.tcp.enums.FramingMode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

/**
 * TCP 粘包拆包分帧配置属性
 *
 * @author Ateng
 * @since 2026-10-26
 */
@Getter
@Setter
public class TcpFramingProperties {

    /**
     * 数据帧切片模式（默认 NONE）
     */
    private FramingMode mode = FramingMode.NONE;

    /**
     * 分隔符字符串（当 mode = DELIMITER 时生效，默认换行符 "\r\n"，支持转义或特殊标记）
     */
    private String delimiter = "\r\n";

    /**
     * 是否在分帧后自动剔除分隔符字节（当 mode = DELIMITER 时生效，默认 true）
     */
    private boolean stripDelimiter = true;

    /**
     * 固定帧字节长度（当 mode = FIXED_LENGTH 时生效，默认 128 字节）
     */
    private int fixedLength = 128;

    /**
     * 基于长度字段的帧切片参数（当 mode = LENGTH_FIELD 时生效）
     */
    @NestedConfigurationProperty
    private TcpLengthFieldProperties lengthField = new TcpLengthFieldProperties();

    /**
     * 单帧允许的最大数据容量阈值（超出立即抛出 TooLongFrameException 截断断开，防 OOM，默认 64KB）
     */
    private DataSize maxFrameLength = DataSize.ofKilobytes(64);
}
