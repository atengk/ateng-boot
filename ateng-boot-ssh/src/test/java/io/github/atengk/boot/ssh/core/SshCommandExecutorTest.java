package io.github.atengk.boot.ssh.core;

import io.github.atengk.boot.ssh.exception.SshOperationException;
import io.github.atengk.boot.ssh.exception.SshResultCode;
import io.github.atengk.boot.ssh.model.SshExecOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SshCommandExecutor 核心调度引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("SshCommandExecutor 核心执行引擎单元测试")
class SshCommandExecutorTest {

    @Test
    @DisplayName("测试 buildSudoCommand 各种命令模式格式规整")
    void testBuildSudoCommandPatterns() {
        // 1. 普通命令添加前缀
        assertThat(SshCommandExecutor.buildSudoCommand("whoami"))
                .isEqualTo("sudo -S -p '' whoami");
        assertThat(SshCommandExecutor.buildSudoCommand("systemctl restart nginx"))
                .isEqualTo("sudo -S -p '' systemctl restart nginx");

        // 2. 原本携带 sudo 的命令替换为标准非交互式格式
        assertThat(SshCommandExecutor.buildSudoCommand("sudo whoami"))
                .isEqualTo("sudo -S -p '' whoami");
        assertThat(SshCommandExecutor.buildSudoCommand("sudo -u postgres psql"))
                .isEqualTo("sudo -S -p '' -u postgres psql");

        // 3. 已经包含 -S -p '' 的命令幂等保持
        assertThat(SshCommandExecutor.buildSudoCommand("sudo -S -p '' whoami"))
                .isEqualTo("sudo -S -p '' whoami");

        // 4. 首尾空白字符安全清洗
        assertThat(SshCommandExecutor.buildSudoCommand("   cat /etc/shadow   "))
                .isEqualTo("sudo -S -p '' cat /etc/shadow");
        assertThat(SshCommandExecutor.buildSudoCommand("   sudo    systemctl status sshd   "))
                .isEqualTo("sudo -S -p '' systemctl status sshd");

        // 5. 空白命令防御性拦截
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SshCommandExecutor.buildSudoCommand(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 workingDir POSIX 单引号安全转义与非法控制字符防注入拦截")
    void testWorkingDirPosixEscapingAndControlCharGuard() {
        // 1. 普通路径与含空格路径均采用标准单引号包裹
        assertThat(SshCommandExecutor.buildEffectiveCommand("pwd", SshExecOptions.ofWorkingDir("/var/log/nginx")))
                .isEqualTo("cd '/var/log/nginx' && ( pwd )");
        assertThat(SshCommandExecutor.buildEffectiveCommand("pwd", SshExecOptions.ofWorkingDir("/opt/my app")))
                .isEqualTo("cd '/opt/my app' && ( pwd )");

        // 2. 内嵌单引号与特殊符号遵循 POSIX 单引号转义 (' -> '\'')
        assertThat(SshCommandExecutor.buildEffectiveCommand("ls -la", SshExecOptions.ofWorkingDir("/opt/it's/$app")))
                .isEqualTo("cd '/opt/it'\\''s/$app' && ( ls -la )");

        // 3. 包含 \n、\r、\0 控制字符立即 Fail-Fast 阻断（含纯控制字符边界）
        for (String badDir : new String[]{"/tmp\nrm -rf /", "/tmp\rid", "/tmp\0id", "\n", "\r\n", "\0"}) {
            assertThatThrownBy(() -> SshCommandExecutor.buildEffectiveCommand("pwd", SshExecOptions.ofWorkingDir(badDir)))
                    .isInstanceOf(SshOperationException.class)
                    .satisfies(ex -> assertThat(((SshOperationException) ex).getResultCode()).isEqualTo(SshResultCode.COMMAND_EXEC_FAILED));
        }
    }
}
