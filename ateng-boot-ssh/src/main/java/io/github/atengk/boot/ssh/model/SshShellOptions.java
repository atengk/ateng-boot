package io.github.atengk.boot.ssh.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.sshd.common.channel.PtyMode;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSH 交互式终端规格配置对象 (SshShellOptions)
 * <p>
 * 声明式封装 PTY 终端仿真类型（默认 {@code xterm-256color}）、字符行列视口尺寸（默认 80x24）、像素尺寸、环境变量注入与终端模式控制。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public final class SshShellOptions {

    /**
     * 默认终端仿真类型
     */
    public static final String DEFAULT_TERM = "xterm-256color";

    /**
     * 默认终端列宽（字符）
     */
    public static final int DEFAULT_COLS = 80;

    /**
     * 默认终端行高（字符）
     */
    public static final int DEFAULT_ROWS = 24;

    /**
     * 默认通道建立与开启超时时间
     */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 预设单例
     */
    private static final SshShellOptions DEFAULT_OPTIONS = SshShellOptions.builder().build();

    /**
     * 终端仿真类型 (如 xterm-256color, xterm, vt100)
     */
    @Builder.Default
    private final String term = DEFAULT_TERM;

    /**
     * 终端字符列数（宽度）
     */
    @Builder.Default
    private final int cols = DEFAULT_COLS;

    /**
     * 终端字符行数（高度）
     */
    @Builder.Default
    private final int rows = DEFAULT_ROWS;

    /**
     * 终端像素宽度（0 表示未指定）
     */
    @Builder.Default
    private final int width = 0;

    /**
     * 终端像素高度（0 表示未指定）
     */
    @Builder.Default
    private final int height = 0;

    /**
     * 环境变量字典
     */
    @Builder.Default
    private final Map<String, String> env = Collections.emptyMap();

    /**
     * PTY 终端模式映射
     */
    @Builder.Default
    private final Map<PtyMode, Integer> ptyModes = Collections.emptyMap();

    /**
     * 终端通道建立与开启超时阈值
     */
    @Builder.Default
    private final Duration timeout = DEFAULT_TIMEOUT;

    public SshShellOptions(String term,
                           int cols,
                           int rows,
                           int width,
                           int height,
                           Map<String, String> env,
                           Map<PtyMode, Integer> ptyModes,
                           Duration timeout) {
        String effectiveTerm = (term != null && !term.isBlank()) ? term.trim() : DEFAULT_TERM;
        int effectiveCols = cols > 0 ? cols : DEFAULT_COLS;
        int effectiveRows = rows > 0 ? rows : DEFAULT_ROWS;
        int effectiveWidth = Math.max(width, 0);
        int effectiveHeight = Math.max(height, 0);
        Duration effectiveTimeout = (timeout != null && !timeout.isNegative() && !timeout.isZero()) ? timeout : DEFAULT_TIMEOUT;

        this.term = effectiveTerm;
        this.cols = effectiveCols;
        this.rows = effectiveRows;
        this.width = effectiveWidth;
        this.height = effectiveHeight;
        this.env = (env != null && !env.isEmpty())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(env))
                : Collections.emptyMap();
        this.ptyModes = (ptyModes != null && !ptyModes.isEmpty())
                ? Collections.unmodifiableMap(new LinkedHashMap<>(ptyModes))
                : Collections.emptyMap();
        this.timeout = effectiveTimeout;
    }

    /**
     * 获取生产预设默认终端配置
     *
     * @return 默认终端规格实例
     */
    public static SshShellOptions defaults() {
        return DEFAULT_OPTIONS;
    }

    /**
     * 快速构建指定行列尺寸的终端配置
     *
     * @param cols 字符列宽
     * @param rows 字符行高
     * @return 终端规格实例
     */
    public static SshShellOptions ofSize(int cols, int rows) {
        Assert.isTrue(cols > 0, "cols must be greater than 0");
        Assert.isTrue(rows > 0, "rows must be greater than 0");
        return SshShellOptions.builder().cols(cols).rows(rows).build();
    }

    /**
     * 快速构建指定仿真类型的终端配置
     *
     * @param term 终端类型
     * @return 终端规格实例
     */
    public static SshShellOptions ofTerm(String term) {
        Assert.hasText(term, "term must not be blank");
        return SshShellOptions.builder().term(term).build();
    }
}
