package io.github.atengk.boot.ssh.interceptor;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import io.github.atengk.boot.ssh.model.SshServerInfo;
import io.github.atengk.boot.ssh.properties.SshProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内置高危毁灭性命令安全防火墙拦截器 (HighRiskCommandInterceptor)
 * <p>
 * 以最高优先级 ({@link Ordered#HIGHEST_PRECEDENCE}) 驻守在命令执行前置链路上，默认拦截针对系统根目录的递归强删、
 * 磁盘格式化、块设备清零覆写、Fork 炸弹及宿主机关机重启等毁灭性指令，并支持通过 {@code dangerous-command-patterns} 追加自定义黑名单正则。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
public class HighRiskCommandInterceptor implements SshCommandInterceptor, Ordered {

    private static final List<Pattern> BUILTIN_DANGEROUS_PATTERNS = List.of(
            // 针对根目录 / 或 /* 的递归强制删除（精确放行 /tmp/... 等具体子路径）
            Pattern.compile("(?i)(?:^|[;&|(\\s])rm\\s+(?:-(?:[a-zA-Z]*[rR][a-zA-Z]*|[a-zA-Z]*f[a-zA-Z]*)|--recursive|--force|--no-preserve-root)(?:\\s+(?:-(?:[a-zA-Z]*[rR][a-zA-Z]*|[a-zA-Z]*f[a-zA-Z]*)|--recursive|--force|--no-preserve-root))*\\s+(?:/\\*?(?:\\s|$|[;&|)])|--no-preserve-root)"),
            // 磁盘与分区格式化
            Pattern.compile("(?i)(?:^|[;&|(\\s])(?:mkfs(?:\\.[a-z0-9_+-]+)?|mke2fs)\\b"),
            // 块设备原始覆写清零（豁免 /dev/null）
            Pattern.compile("(?i)(?:^|[;&|(\\s])dd\\b[^;&|\\n]*\\bof=/dev/(?:[svhx]d[a-z]\\d*|nvme\\d+n\\d+(?:p\\d+)?|mmcblk\\d+(?:p\\d+)?)\\b"),
            Pattern.compile("(?i)(?:^|[;&|(\\s])dd\\b[^;&|\\n]*\\bif=/dev/(?:zero|u?random)\\b[^;&|\\n]*\\bof=/dev/(?!null\\b)\\S+"),
            // 经典 Fork 炸弹
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            // 宿主机停机、重启与切换至运行级别 0/6
            Pattern.compile("(?i)(?:^|[;&|(\\s])(?:shutdown|reboot|halt|poweroff|init\\s+[06])(?:\\s|$|[;&|)])")
    );

    private final List<Pattern> patterns;

    /**
     * 基于配置属性构造高危命令拦截器（默认开启内置规则并编译追加自定义规则）
     *
     * @param properties SSH 配置属性（可为 null）
     */
    public HighRiskCommandInterceptor(SshProperties properties) {
        List<Pattern> compiled = new ArrayList<>(BUILTIN_DANGEROUS_PATTERNS);
        if (properties != null && properties.getDangerousCommandPatterns() != null) {
            for (String rawPattern : properties.getDangerousCommandPatterns()) {
                if (rawPattern != null && !rawPattern.isBlank()) {
                    try {
                        compiled.add(Pattern.compile(rawPattern.trim()));
                    } catch (PatternSyntaxException e) {
                        throw new SshOperationException(SshResultCode.OPERATION_FAILED,
                                "高危命令拦截规则正则表达式非法 [" + rawPattern + "]: " + e.getMessage(), e);
                    }
                }
            }
        }
        this.patterns = Collections.unmodifiableList(compiled);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeExecute(SshServerInfo serverInfo, String command, SshExecOptions options) {
        if (command == null || command.isBlank()) {
            return;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(command).find()) {
                String target = serverInfo != null ? serverInfo.toSessionKey() : "unknown";
                log.warn("ateng-boot-ssh: 安全策略阻断高危命令下发 [target={}, rule={}, command={}]",
                        target, pattern.pattern(), command);
                throw new SshOperationException(SshResultCode.COMMAND_FORBIDDEN,
                        "SSH高危命令被安全策略阻断 [target=" + target + "]");
            }
        }
    }
}
