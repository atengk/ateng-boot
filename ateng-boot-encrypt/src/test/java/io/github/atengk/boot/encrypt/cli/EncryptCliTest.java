package io.github.atengk.boot.encrypt.cli;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 终端脱机安全加解密 CLI 工具单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class EncryptCliTest {

    @Test
    @DisplayName("测试命令行单值加解密完整闭环")
    void testCliEncryptAndDecryptArgs() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        // 1. 加密
        int encCode = cli.run(new String[]{
                "--encrypt",
                "-v", "RootDatabasePassword2026!",
                "-p", "MyMasterPasswordSecret",
                "-a", "AES-GCM",
                "--key-id", "prod-db"
        });
        assertEquals(0, encCode);
        String encOutput = out.toString(StandardCharsets.UTF_8).trim();
        assertTrue(encOutput.startsWith("ENC[prod-db]("), "应包含指定 key-id");
        assertTrue(encOutput.endsWith(")"));

        // 2. 解密
        out.reset();
        int decCode = cli.run(new String[]{
                "--decrypt",
                "-v", encOutput,
                "-p", "MyMasterPasswordSecret"
        });
        assertEquals(0, decCode);
        String decOutput = out.toString(StandardCharsets.UTF_8).trim();
        assertEquals("RootDatabasePassword2026!", decOutput);
    }

    @Test
    @DisplayName("测试命令行一键生成 RSA 与 SM2 密钥对")
    void testCliKeygenArgs() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        // 1. RSA
        int rsaCode = cli.run(new String[]{"--keygen", "-a", "RSA", "--key-size", "2048"});
        assertEquals(0, rsaCode);
        String rsaOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(rsaOutput.contains("-----BEGIN PRIVATE KEY-----"));
        assertTrue(rsaOutput.contains("-----BEGIN PUBLIC KEY-----"));

        // 2. SM2
        out.reset();
        int sm2Code = cli.run(new String[]{"--keygen", "-a", "SM2"});
        assertEquals(0, sm2Code);
        String sm2Output = out.toString(StandardCharsets.UTF_8);
        assertTrue(sm2Output.contains("SM2_PRIVATE_KEY="));
        assertTrue(sm2Output.contains("SM2_PUBLIC_KEY="));

        // 提取 SM2 密钥验证可加密解密
        String privHex = null;
        String pubHex = null;
        for (String line : sm2Output.split("\n")) {
            if (line.startsWith("SM2_PRIVATE_KEY=")) {
                privHex = line.substring("SM2_PRIVATE_KEY=".length()).trim();
            } else if (line.startsWith("SM2_PUBLIC_KEY=")) {
                pubHex = line.substring("SM2_PUBLIC_KEY=".length()).trim();
            }
        }
        assertNotNull(privHex);
        assertNotNull(pubHex);

        String cipher = Sm2ConfigDecryptor.encrypt("SM2-Test-Value", pubHex);
        String plain = new Sm2ConfigDecryptor().decrypt(cipher, privHex);
        assertEquals("SM2-Test-Value", plain);
    }

    @Test
    @DisplayName("测试帮助说明与错误参数防御")
    void testCliHelpAndValidation() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        int helpCode = cli.run(new String[]{"--help"});
        assertEquals(0, helpCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("终端脱机安全工具箱使用说明"));

        out.reset();
        int missingParamCode = cli.run(new String[]{"--encrypt", "-v", "only-val"});
        assertEquals(1, missingParamCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("必须提供"));
    }

    @Test
    @DisplayName("测试模拟交互式控制台菜单选择与退出")
    void testInteractiveConsoleFlow() {
        // 模拟用户交互输入：
        // 1 (加密) -> "plaintext123" -> "secretKey" -> "" (默认AES-GCM) -> "" (默认无keyId)
        // 5 (退出)
        String simulatedInput = """
                1
                plaintext123
                secretKey
                
                
                5
                """;

        ByteArrayInputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

        EncryptCli cli = new EncryptCli(printStream, in);
        int exitCode = cli.run(new String[0]);
        assertEquals(0, exitCode);

        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("ateng-boot-encrypt 终端脱机安全工具箱"));
        assertTrue(consoleOutput.contains("[SUCCESS] 加密完成"));
        assertTrue(consoleOutput.contains("ENC("));
        assertTrue(consoleOutput.contains("退出安全工具箱，再见！"));
    }
}
