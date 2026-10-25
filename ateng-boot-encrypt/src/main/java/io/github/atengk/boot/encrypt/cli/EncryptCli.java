package io.github.atengk.boot.encrypt.cli;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;

import java.io.InputStream;
import java.io.PrintStream;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * 终端脱机安全加解密与运维 CLI 工具
 * 纯原生 JDK 标准库实现，支持命令行批处理参数与交互式控制台菜单，支持单值快速加解密及 RSA/SM2 密钥对生成
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class EncryptCli {

    private final PrintStream out;
    private final Scanner scanner;
    private final CiphertextParser parser;

    public EncryptCli(PrintStream out, InputStream in) {
        this.out = (out != null) ? out : System.out;
        this.scanner = new Scanner((in != null) ? in : System.in);
        this.parser = new CiphertextParser();
    }

    public EncryptCli() {
        this(System.out, System.in);
    }

    public static void main(String[] args) {
        new EncryptCli().run(args);
    }

    /**
     * 执行 CLI 命令
     *
     * @param args 命令行入参
     * @return 执行退出码（0 成功，非 0 失败）
     */
    public int run(String[] args) {
        if (args == null || args.length == 0) {
            return runInteractive();
        }

        Map<String, String> params = parseArgs(args);
        if (params.containsKey("help") || params.containsKey("h")) {
            printUsage();
            return 0;
        }

        if (params.containsKey("interactive") || params.containsKey("i")) {
            return runInteractive();
        }

        if (params.containsKey("keygen") || params.containsKey("k")) {
            return handleKeygen(params);
        }

        if (params.containsKey("encrypt") || params.containsKey("e")) {
            return handleEncrypt(params);
        }

        if (params.containsKey("decrypt") || params.containsKey("d")) {
            return handleDecrypt(params);
        }

        out.println("[ERROR] 未识别的命令。使用 --help 查看完整使用说明。");
        return 1;
    }

    /**
     * 交互式控制台交互循环
     */
    private int runInteractive() {
        printBanner();
        while (true) {
            printMenu();
            out.print("请选择操作编号 [1-5]: ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1" -> interactiveEncrypt();
                case "2" -> interactiveDecrypt();
                case "3" -> interactiveRsaKeygen();
                case "4" -> interactiveSm2Keygen();
                case "5", "q", "exit" -> {
                    out.println("退出安全工具箱，再见！");
                    return 0;
                }
                default -> out.println("[WARN] 无效的选择，请重新输入。\n");
            }
        }
        return 0;
    }

    private void interactiveEncrypt() {
        out.println("\n--- [单值快速加密] ---");
        out.print("请输入明文字符串: ");
        String value = readLine();
        if (value.isEmpty()) {
            out.println("[WARN] 明文不能为空！\n");
            return;
        }

        out.print("请输入加密密钥口令 (或公钥): ");
        String password = readLine();
        if (password.isEmpty()) {
            out.println("[WARN] 密钥不能为空！\n");
            return;
        }

        out.print("请输入加密算法 [AES-GCM (默认) / AES-CBC / SM4-CBC / SM4-ECB / RSA / SM2]: ");
        String algoStr = readLine();
        EncryptAlgorithm algo = algoStr.isEmpty() ? EncryptAlgorithm.AES_GCM : EncryptAlgorithm.fromCode(algoStr);
        if (algo == null) {
            out.println("[ERROR] 不支持的算法类型: " + algoStr + "\n");
            return;
        }

        out.print("请输入 KeyId 别名 (留空为默认): ");
        String keyId = readLine();

        try {
            String ciphertext = ConfigEncryptor.encrypt(value, password, algo);
            String encToken = formatEncToken(keyId, algo, ciphertext);
            out.println("\n[SUCCESS] 加密完成：");
            out.println("-----------------------------------------------------------------");
            out.println(encToken);
            out.println("-----------------------------------------------------------------\n");
        } catch (Exception e) {
            out.println("[ERROR] 加密失败: " + e.getMessage() + "\n");
        }
    }

    private void interactiveDecrypt() {
        out.println("\n--- [单值快速解密] ---");
        out.print("请输入待解密密文字符串 (支持 ENC(...) 或纯 Base64): ");
        String value = readLine();
        if (value.isEmpty()) {
            out.println("[WARN] 密文不能为空！\n");
            return;
        }

        out.print("请输入解密主密钥口令 (或私钥): ");
        String password = readLine();
        if (password.isEmpty()) {
            out.println("[WARN] 密钥不能为空！\n");
            return;
        }

        try {
            if (parser.hasEncrypted(value)) {
                String decrypted = parser.replaceTokens(value, token -> {
                    EncryptAlgorithm algo = token.getAlgorithm() != null ? token.getAlgorithm() : EncryptAlgorithm.AES_GCM;
                    ConfigDecryptor decryptor = getDecryptor(algo);
                    try {
                        return decryptor.decrypt(token.getCiphertext(), password);
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
                out.println("\n[SUCCESS] 解密成功：");
                out.println("-----------------------------------------------------------------");
                out.println(decrypted);
                out.println("-----------------------------------------------------------------\n");
            } else {
                out.print("请输入解密算法 [AES-GCM (默认) / AES-CBC / SM4-CBC / SM4-ECB / RSA / SM2]: ");
                String algoStr = readLine();
                EncryptAlgorithm algo = algoStr.isEmpty() ? EncryptAlgorithm.AES_GCM : EncryptAlgorithm.fromCode(algoStr);
                ConfigDecryptor decryptor = getDecryptor(algo);
                String plaintext = decryptor.decrypt(value.trim(), password);
                out.println("\n[SUCCESS] 解密成功：");
                out.println("-----------------------------------------------------------------");
                out.println(plaintext);
                out.println("-----------------------------------------------------------------\n");
            }
        } catch (Exception e) {
            out.println("[ERROR] 解密失败: " + e.getMessage() + "\n");
        }
    }

    private void interactiveRsaKeygen() {
        out.println("\n--- [一键生成非对称 RSA 密钥对] ---");
        out.print("请选择密钥位数 [2048 (默认) / 4096]: ");
        String sizeStr = readLine();
        int keySize = "4096".equals(sizeStr.trim()) ? 4096 : 2048;
        try {
            KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(keySize);
            String privatePem = RsaConfigDecryptor.encodePrivateKeyToPem(keyPair.getPrivate());
            String publicPem = RsaConfigDecryptor.encodePublicKeyToPem(keyPair.getPublic());
            out.println("\n[SUCCESS] RSA-" + keySize + " 密钥对生成成功：");
            out.println("========================== [私钥 (解密/仅生产)] ==========================");
            out.println(privatePem);
            out.println("========================== [公钥 (加密/CI/开发)] ==========================");
            out.println(publicPem);
            out.println("=========================================================================\n");
        } catch (Exception e) {
            out.println("[ERROR] 密钥对生成失败: " + e.getMessage() + "\n");
        }
    }

    private void interactiveSm2Keygen() {
        out.println("\n--- [一键生成纯原生国密 SM2 密钥对] ---");
        try {
            Sm2ConfigDecryptor.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
            out.println("\n[SUCCESS] 纯原生国密 SM2 密钥对生成成功：");
            out.println("========================== [私钥 (32字节 Hex / 私密保管)] ==========================");
            out.println(keyPair.privateKeyHex());
            out.println("========================== [公钥 (65字节未压缩 Hex / 公开)] ========================");
            out.println(keyPair.publicKeyHex());
            out.println("===================================================================================\n");
        } catch (Exception e) {
            out.println("[ERROR] SM2 密钥对生成失败: " + e.getMessage() + "\n");
        }
    }

    private int handleEncrypt(Map<String, String> params) {
        String value = getParam(params, "value", "v");
        String password = getParam(params, "password", "p");
        if (value == null || password == null) {
            out.println("[ERROR] 加密模式必须提供 --value (-v) 与 --password (-p) 参数！");
            return 1;
        }

        String algoStr = getParam(params, "algorithm", "a");
        EncryptAlgorithm algo = (algoStr != null) ? EncryptAlgorithm.fromCode(algoStr) : EncryptAlgorithm.AES_GCM;
        if (algo == null) {
            out.println("[ERROR] 不支持的算法类型: " + algoStr);
            return 1;
        }

        String keyId = params.get("key-id");
        try {
            String ciphertext = ConfigEncryptor.encrypt(value, password, algo);
            out.println(formatEncToken(keyId, algo, ciphertext));
            return 0;
        } catch (Exception e) {
            out.println("[ERROR] 加密执行失败: " + e.getMessage());
            return 1;
        }
    }

    private int handleDecrypt(Map<String, String> params) {
        String value = getParam(params, "value", "v");
        String password = getParam(params, "password", "p");
        if (value == null || password == null) {
            out.println("[ERROR] 解密模式必须提供 --value (-v) 与 --password (-p) 参数！");
            return 1;
        }

        String algoStr = getParam(params, "algorithm", "a");
        EncryptAlgorithm algo = (algoStr != null) ? EncryptAlgorithm.fromCode(algoStr) : EncryptAlgorithm.AES_GCM;

        try {
            if (parser.hasEncrypted(value)) {
                String decrypted = parser.replaceTokens(value, token -> {
                    EncryptAlgorithm effectiveAlgo = token.getAlgorithm() != null ? token.getAlgorithm() : algo;
                    try {
                        return getDecryptor(effectiveAlgo).decrypt(token.getCiphertext(), password);
                    } catch (Exception e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                });
                out.println(decrypted);
            } else {
                ConfigDecryptor decryptor = getDecryptor(algo);
                out.println(decryptor.decrypt(value.trim(), password));
            }
            return 0;
        } catch (Exception e) {
            out.println("[ERROR] 解密执行失败: " + e.getMessage());
            return 1;
        }
    }

    private int handleKeygen(Map<String, String> params) {
        String algoStr = getParam(params, "algorithm", "a");
        String algo = (algoStr != null) ? algoStr.trim().toUpperCase() : "RSA";
        try {
            if ("SM2".equalsIgnoreCase(algo)) {
                Sm2ConfigDecryptor.Sm2KeyPair keyPair = Sm2ConfigDecryptor.generateKeyPair();
                out.println("SM2_PRIVATE_KEY=" + keyPair.privateKeyHex());
                out.println("SM2_PUBLIC_KEY=" + keyPair.publicKeyHex());
                return 0;
            } else {
                String sizeStr = params.get("key-size");
                int keySize = "4096".equals(sizeStr) ? 4096 : 2048;
                KeyPair keyPair = RsaConfigDecryptor.generateKeyPair(keySize);
                out.println(RsaConfigDecryptor.encodePrivateKeyToPem(keyPair.getPrivate()));
                out.println(RsaConfigDecryptor.encodePublicKeyToPem(keyPair.getPublic()));
                return 0;
            }
        } catch (Exception e) {
            out.println("[ERROR] 密钥对生成失败: " + e.getMessage());
            return 1;
        }
    }

    private static String formatEncToken(String keyId, EncryptAlgorithm algo, String ciphertext) {
        return CiphertextParser.formatToken(keyId, algo, ciphertext);
    }

    private ConfigDecryptor getDecryptor(EncryptAlgorithm algorithm) {
        return switch (algorithm) {
            case AES_GCM -> new AesGcmConfigDecryptor();
            case AES_CBC -> new AesCbcConfigDecryptor();
            case SM4_CBC, SM4_ECB -> new Sm4ConfigDecryptor();
            case RSA -> new RsaConfigDecryptor();
            case SM2 -> new Sm2ConfigDecryptor();
            default -> throw new UnsupportedOperationException("不支持解密的算法: " + algorithm);
        };
    }

    private String readLine() {
        return scanner.hasNextLine() ? scanner.nextLine().trim() : "";
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                if (eq > 0) {
                    map.put(body.substring(0, eq).trim().toLowerCase(), body.substring(eq + 1).trim());
                } else {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        map.put(body.trim().toLowerCase(), args[++i].trim());
                    } else {
                        map.put(body.trim().toLowerCase(), "true");
                    }
                }
            } else if (arg.startsWith("-")) {
                String body = arg.substring(1);
                int eq = body.indexOf('=');
                if (eq > 0) {
                    map.put(body.substring(0, eq).trim().toLowerCase(), body.substring(eq + 1).trim());
                } else {
                    if (i + 1 < args.length && !args[i + 1].startsWith("-")) {
                        map.put(body.trim().toLowerCase(), args[++i].trim());
                    } else {
                        map.put(body.trim().toLowerCase(), "true");
                    }
                }
            }
        }
        return map;
    }

    private static String getParam(Map<String, String> map, String longName, String shortName) {
        String val = map.get(longName);
        return val != null ? val : map.get(shortName);
    }

    private void printBanner() {
        out.println("=================================================================");
        out.println("*              ateng-boot-encrypt 终端脱机安全工具箱             *");
        out.println("=================================================================");
    }

    private void printMenu() {
        out.println("[1] 单值快速加密 (AES-GCM / SM4 / RSA / SM2)");
        out.println("[2] 单值快速解密");
        out.println("[3] 一键生成非对称 RSA 密钥对 (2048 / 4096 位)");
        out.println("[4] 一键生成纯原生国密 SM2 密钥对");
        out.println("[5] 退出工具箱");
        out.println("-----------------------------------------------------------------");
    }

    private void printUsage() {
        out.println("ateng-boot-encrypt 终端脱机安全工具箱使用说明：");
        out.println("用法:");
        out.println("  1. 交互式菜单模式: 直接运行命令（不带参数）");
        out.println("  2. 单值加密模式:   --encrypt -v <明文> -p <密钥> [-a <算法>] [--key-id <别名>]");
        out.println("  3. 单值解密模式:   --decrypt -v <密文> -p <密钥> [-a <算法>]");
        out.println("  4. 密钥对生成模式: --keygen [-a RSA|SM2] [--key-size 2048|4096]");
        out.println("选项:");
        out.println("  -e, --encrypt         执行加密");
        out.println("  -d, --decrypt         执行解密");
        out.println("  -k, --keygen          生成密钥对");
        out.println("  -v, --value           待处理的明文或密文字符串");
        out.println("  -p, --password        主密钥口令或公私钥");
        out.println("  -a, --algorithm       指定加密算法 (默认 AES-GCM，可选 AES-CBC, SM4-CBC, SM4-ECB, RSA, SM2)");
        out.println("  --key-id              指定命名密钥别名 (输出 ENC[keyId](...))");
        out.println("  --key-size            RSA 密钥位数 (默认 2048，可选 4096)");
        out.println("  -h, --help            显示帮助说明");
    }
}
