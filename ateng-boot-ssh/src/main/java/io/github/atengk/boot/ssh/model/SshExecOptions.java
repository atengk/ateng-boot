package io.github.atengk.boot.ssh.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSH 远程命令执行扩展参数选项结构体
 * <p>
 * 声明式支持工作目录（workingDir）、环境变量注入（env）、执行超时覆盖、伪终端（PTY）分配与解码字符集（如 GBK）。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public final class SshExecOptions {

    /**
     * 默认缺省选项单例
     */
    private static final SshExecOptions DEFAULT_OPTIONS = SshExecOptions.builder().build();

    /**
     * 远程工作目录（自动通过 cd 切换）
     */
    private final String workingDir;

    /**
     * 环境变量字典
     */
    @Builder.Default
    private final Map<String, String> env = Collections.emptyMap();

    /**
     * 自定义命令执行超时阈值（覆盖全局默认值）
     */
    private final Duration timeout;

    /**
     * 输出字符集编码（默认 UTF-8，针对 Windows 或老旧系统可设为 GBK）
     */
    @Builder.Default
    private final Charset charset = StandardCharsets.UTF_8;

    /**
     * 是否分配伪终端（PTY）
     */
    @Builder.Default
    private final boolean pty = false;

    public SshExecOptions(String workingDir,
                          Map<String, String> env,
                          Duration timeout,
                          Charset charset,
                          boolean pty) {
        this.workingDir = workingDir;
        this.env = env != null ? Collections.unmodifiableMap(new LinkedHashMap<>(env)) : Collections.emptyMap();
        this.timeout = timeout;
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        this.pty = pty;
    }

    /**
     * 获取默认配置参数实例
     *
     * @return 默认参数
     */
    public static SshExecOptions defaults() {
        return DEFAULT_OPTIONS;
    }

    /**
     * 快速构建仅指定工作目录的参数选项
     *
     * @param workingDir 目标工作目录路径
     * @return 参数选项
     */
    public static SshExecOptions ofWorkingDir(String workingDir) {
        return SshExecOptions.builder().workingDir(workingDir).build();
    }

    /**
     * 快速构建仅指定解码字符集的参数选项
     *
     * @param charset 目标字符集（如 GBK）
     * @return 参数选项
     */
    public static SshExecOptions ofCharset(Charset charset) {
        return SshExecOptions.builder().charset(charset).build();
    }

    /**
     * 快速构建仅指定超时时间的参数选项
     *
     * @param timeout 超时时长
     * @return 参数选项
     */
    public static SshExecOptions ofTimeout(Duration timeout) {
        return SshExecOptions.builder().timeout(timeout).build();
    }
}
