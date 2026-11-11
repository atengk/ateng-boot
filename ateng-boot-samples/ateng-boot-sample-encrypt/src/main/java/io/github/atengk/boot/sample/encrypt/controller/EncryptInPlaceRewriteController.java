package io.github.atengk.boot.sample.encrypt.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.tool.InPlaceRewriter;
import io.github.atengk.boot.sample.encrypt.dto.InPlaceRewriteRequest;
import io.github.atengk.boot.sample.encrypt.vo.RewriteResultVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 配置文件就地加解密回写沙箱体验控制器
 * <p>
 * 提供在受控瞬态临时沙箱目录中对 YAML 与 Properties 配置文件文本执行就地智能加密与逆向还原自验，
 * 返回处理前后文本、修改行数、.bak 备份状态以及行级 Diff 差异，操作完毕后自动清理临时沙箱。
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@RestController
@RequestMapping("/sample/encrypt/rewrite")
@RequiredArgsConstructor
public class EncryptInPlaceRewriteController {

    /**
     * 1. YAML 格式配置文件就地智能加密体验
     *
     * @param request 回写请求入参
     * @return 就地回写结果展示对象
     */
    @PostMapping("/encrypt-yaml")
    public Result<RewriteResultVO> encryptYaml(@Valid @RequestBody InPlaceRewriteRequest request) {
        log.info("[EncryptInPlaceRewriteController] 执行 YAML 沙箱就地加密");
        RewriteResultVO vo = executeSandboxRewrite(
                request.getContent(),
                "yml",
                request.getPassword(),
                request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM,
                true
        );
        return Result.success(vo);
    }

    /**
     * 2. YAML 格式配置文件就地智能解密还原体验
     *
     * @param request 回写请求入参
     * @return 就地回写结果展示对象
     */
    @PostMapping("/decrypt-yaml")
    public Result<RewriteResultVO> decryptYaml(@Valid @RequestBody InPlaceRewriteRequest request) {
        log.info("[EncryptInPlaceRewriteController] 执行 YAML 沙箱就地解密还原");
        RewriteResultVO vo = executeSandboxRewrite(
                request.getContent(),
                "yml",
                request.getPassword(),
                request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM,
                false
        );
        return Result.success(vo);
    }

    /**
     * 3. Properties 格式配置文件就地智能加密体验
     *
     * @param request 回写请求入参
     * @return 就地回写结果展示对象
     */
    @PostMapping("/encrypt-properties")
    public Result<RewriteResultVO> encryptProperties(@Valid @RequestBody InPlaceRewriteRequest request) {
        log.info("[EncryptInPlaceRewriteController] 执行 Properties 沙箱就地加密");
        RewriteResultVO vo = executeSandboxRewrite(
                request.getContent(),
                "properties",
                request.getPassword(),
                request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM,
                true
        );
        return Result.success(vo);
    }

    /**
     * 4. Properties 格式配置文件就地智能解密还原体验
     *
     * @param request 回写请求入参
     * @return 就地回写结果展示对象
     */
    @PostMapping("/decrypt-properties")
    public Result<RewriteResultVO> decryptProperties(@Valid @RequestBody InPlaceRewriteRequest request) {
        log.info("[EncryptInPlaceRewriteController] 执行 Properties 沙箱就地解密还原");
        RewriteResultVO vo = executeSandboxRewrite(
                request.getContent(),
                "properties",
                request.getPassword(),
                request.getAlgorithm() != null ? request.getAlgorithm() : EncryptAlgorithm.AES_GCM,
                false
        );
        return Result.success(vo);
    }

    /**
     * 在隔离受控的临时沙箱目录中安全执行就地文件加解密回写并计算 Diff
     */
    private RewriteResultVO executeSandboxRewrite(String content, String fileType, String password,
                                                  EncryptAlgorithm algorithm, boolean isEncrypt) {
        String ext = ("properties".equalsIgnoreCase(fileType)) ? ".properties" : ".yml";
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("encrypt-sandbox-");
            Path tempFile = tempDir.resolve("sample-config" + ext);
            Files.writeString(tempFile, content, StandardCharsets.UTF_8);

            InPlaceRewriter rewriter = new InPlaceRewriter(password, algorithm);
            int modifiedCount = isEncrypt ? rewriter.rewriteEncrypt(tempFile) : rewriter.rewriteDecrypt(tempFile);

            String rewritten = Files.readString(tempFile, StandardCharsets.UTF_8);
            Path backupFile = tempDir.resolve("sample-config" + ext + ".bak");
            boolean backupCreated = Files.exists(backupFile);

            List<String> diffLines = computeDiffLines(content, rewritten);

            return RewriteResultVO.builder()
                    .originalContent(content)
                    .rewrittenContent(rewritten)
                    .modifiedCount(modifiedCount)
                    .backupCreated(backupCreated)
                    .fileType(ext.replace(".", ""))
                    .diffLines(diffLines)
                    .build();
        } catch (IOException e) {
            log.error("[EncryptInPlaceRewriteController] 沙箱就地重写失败: {}", e.getMessage(), e);
            throw new IllegalStateException("Sandbox in-place rewrite failed: " + e.getMessage(), e);
        } finally {
            if (tempDir != null && Files.exists(tempDir)) {
                try (Stream<Path> stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 逐行比对原始文本与重写后文本，提取差异变更行
     */
    private List<String> computeDiffLines(String original, String rewritten) {
        List<String> diff = new ArrayList<>();
        String[] origLines = original.split("\\R");
        String[] rewLines = rewritten.split("\\R");
        int max = Math.max(origLines.length, rewLines.length);

        for (int i = 0; i < max; i++) {
            String o = i < origLines.length ? origLines[i] : null;
            String r = i < rewLines.length ? rewLines[i] : null;
            if (o != null && !o.equals(r)) {
                diff.add("- " + o);
                if (r != null) {
                    diff.add("+ " + r);
                }
            } else if (o == null && r != null) {
                diff.add("+ " + r);
            }
        }
        return diff;
    }
}
