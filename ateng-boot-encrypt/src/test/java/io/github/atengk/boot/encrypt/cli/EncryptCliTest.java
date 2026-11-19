package io.github.atengk.boot.encrypt.cli;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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

        out.reset();
        int invalidDecryptAlgoCode = cli.run(new String[]{"--decrypt", "-v", "ENC(abc)", "-p", "Secret", "-a", "UNKNOWN"});
        assertEquals(1, invalidDecryptAlgoCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("[ERROR] 不支持的算法类型: UNKNOWN"));
    }

    @Test
    @DisplayName("测试模拟交互式控制台菜单选择与退出")
    void testInteractiveConsoleFlow() {
        // 模拟用户交互输入：
        // 1 (加密) -> "plaintext123" -> "secretKey" -> "" (默认AES-GCM) -> "" (默认无keyId)
        // 7 (退出)
        String simulatedInput = """
                1
                plaintext123
                secretKey
                
                
                7
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

    @Test
    @DisplayName("测试 CLI 批处理 --encrypt-file 与 --decrypt-file 对 YAML 含多行 PEM 块标量整文件加解密")
    void testCliFileRewriteYamlBatch(@TempDir Path tempDir) throws Exception {
        String originalYaml = """
                spring:
                  datasource:
                    password: myDbSecretPassword # 数据库密码
                rsa:
                  # @encrypt
                  private-key: |
                    -----BEGIN PRIVATE KEY-----
                    MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC7
                    -----END PRIVATE KEY-----
                """;
        Path yamlFile = tempDir.resolve("app-prod.yml");
        Files.writeString(yamlFile, originalYaml, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        // 1. --encrypt-file
        int encCode = cli.run(new String[]{
                "--encrypt-file",
                "-f", yamlFile.toString(),
                "-p", "CliFileMasterKey2026!"
        });
        assertEquals(0, encCode);
        String encStdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(encStdout.contains("2"));
        assertTrue(encStdout.contains(".bak"));
        Path bakFile = tempDir.resolve("app-prod.yml.bak");
        assertTrue(Files.exists(bakFile));
        String encryptedContent = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertTrue(encryptedContent.contains("password: ENC("));
        assertTrue(encryptedContent.contains("private-key: ENC("));
        assertFalse(encryptedContent.contains("myDbSecretPassword"));

        // 2. --decrypt-file
        out.reset();
        int decCode = cli.run(new String[]{
                "--decrypt-file",
                "--file", yamlFile.toString(),
                "--password", "CliFileMasterKey2026!"
        });
        assertEquals(0, decCode);
        String decStdout = out.toString(StandardCharsets.UTF_8);
        assertTrue(decStdout.contains("2"));
        assertTrue(decStdout.contains(".bak"));
        String restoredContent = Files.readString(yamlFile, StandardCharsets.UTF_8);
        assertEquals(originalYaml, restoredContent);
    }

    @Test
    @DisplayName("测试 CLI 短参 -ef / -df 配合 -a SM4-CBC 与 --key-id 对 Properties 文件就地加解密")
    void testCliFileRewritePropertiesShortFlags(@TempDir Path tempDir) throws Exception {
        String originalProps = "spring.datasource.password=propPass2026! # @encrypt\nserver.port=8080\n";
        Path propFile = tempDir.resolve("app.properties");
        Files.writeString(propFile, originalProps, StandardCharsets.UTF_8);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        int encCode = cli.run(new String[]{
                "-ef",
                "-f", propFile.toString(),
                "-p", "Sm4MasterSecret2026!",
                "-a", "SM4-CBC",
                "--key-id", "prod-db"
        });
        assertEquals(0, encCode);
        String encryptedProps = Files.readString(propFile, StandardCharsets.UTF_8);
        assertTrue(encryptedProps.contains("spring.datasource.password=ENC[prod-db]("));
        assertFalse(encryptedProps.contains("propPass2026!"));

        int decCode = cli.run(new String[]{
                "-df",
                "-f", propFile.toString(),
                "-p", "Sm4MasterSecret2026!",
                "-a", "SM4-CBC",
                "--key-id", "prod-db"
        });
        assertEquals(0, decCode);
        assertEquals(originalProps, Files.readString(propFile, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("测试 CLI 文件加解密错误参数与非法文件路径返回非零退出码 1")
    void testCliFileRewriteValidationAndHelp(@TempDir Path tempDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        EncryptCli cli = new EncryptCli(printStream, new ByteArrayInputStream(new byte[0]));

        // 1. --help 包含文件回写选项
        assertEquals(0, cli.run(new String[]{"--help"}));
        String helpText = out.toString(StandardCharsets.UTF_8);
        assertTrue(helpText.contains("--encrypt-file"));
        assertTrue(helpText.contains("--decrypt-file"));
        assertTrue(helpText.contains("--file"));

        // 2. 缺失密码
        out.reset();
        Path validFile = tempDir.resolve("valid.yml");
        Files.writeString(validFile, "app.password: plain\n", StandardCharsets.UTF_8);
        assertEquals(1, cli.run(new String[]{"--encrypt-file", "-f", validFile.toString()}));

        // 3. 缺失文件路径
        out.reset();
        assertEquals(1, cli.run(new String[]{"--encrypt-file", "-p", "Secret"}));

        // 4. 文件不存在
        out.reset();
        assertEquals(1, cli.run(new String[]{"--encrypt-file", "-f", tempDir.resolve("missing.yml").toString(), "-p", "Secret"}));

        // 5. 路径为目录
        out.reset();
        assertEquals(1, cli.run(new String[]{"--decrypt-file", "-f", tempDir.toString(), "-p", "Secret"}));

        // 6. 不支持的算法
        out.reset();
        assertEquals(1, cli.run(new String[]{"--encrypt-file", "-f", validFile.toString(), "-p", "Secret", "-a", "UNKNOWN"}));

        // 7. 错误密码解密返回非零退出码 1
        assertEquals(0, cli.run(new String[]{"--encrypt-file", "-f", validFile.toString(), "-p", "RightPass"}));
        out.reset();
        assertEquals(1, cli.run(new String[]{"--decrypt-file", "-f", validFile.toString(), "-p", "WrongPass"}));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("[ERROR]"));
    }

    @Test
    @DisplayName("测试交互式控制台菜单 [5] 整文件加密与 [6] 整文件解密还原")
    void testInteractiveFileRewriteFlow(@TempDir Path tempDir) throws Exception {
        String originalYaml = "app:\n  secret: interactiveSecretValue\n";
        Path yamlFile = tempDir.resolve("interactive.yaml");
        Files.writeString(yamlFile, originalYaml, StandardCharsets.UTF_8);

        String simulatedInput = String.join("\n",
                "5",
                yamlFile.toString(),
                "InteractiveMaster2026!",
                "",
                "",
                "6",
                yamlFile.toString(),
                "InteractiveMaster2026!",
                "",
                "7",
                ""
        );

        ByteArrayInputStream in = new ByteArrayInputStream(simulatedInput.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(out, true, StandardCharsets.UTF_8);

        EncryptCli cli = new EncryptCli(printStream, in);
        int exitCode = cli.run(new String[0]);
        assertEquals(0, exitCode);

        String consoleOutput = out.toString(StandardCharsets.UTF_8);
        assertTrue(consoleOutput.contains("[5] 配置文件就地加密"));
        assertTrue(consoleOutput.contains("[6] 配置文件就地解密还原"));
        assertTrue(consoleOutput.contains("配置文件就地加密完成"));
        assertTrue(consoleOutput.contains("配置文件就地解密还原完成"));
        assertEquals(originalYaml, Files.readString(yamlFile, StandardCharsets.UTF_8));
        assertTrue(Files.exists(tempDir.resolve("interactive.yaml.bak")));
    }
}
