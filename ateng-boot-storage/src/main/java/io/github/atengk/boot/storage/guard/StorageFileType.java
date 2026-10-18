package io.github.atengk.boot.storage.guard;

import lombok.Getter;

import java.util.Collections;
import java.util.Set;

/**
 * 存储文件内容特征嗅探识别类型定义
 *
 * @author Ateng
 * @since 2026-10-18
 */
@Getter
public enum StorageFileType {

    // === 图片类型 ===
    PNG("png", "image/png", Set.of("png"), false),
    JPEG("jpeg", "image/jpeg", Set.of("jpg", "jpeg"), false),
    GIF("gif", "image/gif", Set.of("gif"), false),
    WEBP("webp", "image/webp", Set.of("webp"), false),
    BMP("bmp", "image/bmp", Set.of("bmp"), false),

    // === 文档与标记语言 ===
    PDF("pdf", "application/pdf", Set.of("pdf"), false),
    XML("xml", "application/xml", Set.of("xml"), false),
    SVG("svg", "image/svg+xml", Set.of("svg", "xml"), false),
    OLE2("ole2", "application/x-ole-storage", Set.of("doc", "xls", "ppt"), false),

    // === 压缩与打包 ===
    ZIP("zip", "application/zip", Set.of("zip", "jar", "docx", "xlsx", "pptx"), false),
    GZIP("gzip", "application/gzip", Set.of("gz", "gzip"), false),
    SEVEN_Z("7z", "application/x-7z-compressed", Set.of("7z"), false),
    TAR("tar", "application/x-tar", Set.of("tar"), false),

    // === 音视频多媒体 ===
    MP4("mp4", "video/mp4", Set.of("mp4", "m4v"), false),
    MP3("mp3", "audio/mpeg", Set.of("mp3"), false),
    WAV("wav", "audio/wav", Set.of("wav"), false),
    AVI("avi", "video/x-msvideo", Set.of("avi"), false),

    // === 高危可执行与脚本二进制（防伪装重点拦截） ===
    PE_EXE("exe", "application/x-dosexec", Set.of("exe", "dll", "sys", "com", "scr"), true),
    ELF("elf", "application/x-elf", Set.of("elf", "bin", "so"), true),
    SHELL("sh", "text/x-shellscript", Set.of("sh", "bash", "zsh"), true),
    JAVA_CLASS("class", "application/java-vm", Set.of("class"), true);

    private final String name;
    private final String mimeType;
    private final Set<String> extensions;
    private final boolean dangerous;

    StorageFileType(String name, String mimeType, Set<String> extensions, boolean dangerous) {
        this.name = name;
        this.mimeType = mimeType;
        this.extensions = Collections.unmodifiableSet(extensions);
        this.dangerous = dangerous;
    }
}
