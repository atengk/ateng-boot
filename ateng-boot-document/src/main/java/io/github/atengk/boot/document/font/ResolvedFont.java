package io.github.atengk.boot.document.font;

import java.io.File;
import java.util.Arrays;
import java.util.Objects;

/**
 * 已探测并解析的中文字体持有对象
 *
 * @param fontName     字体名称或候选名称
 * @param tier         解析阶梯级别
 * @param location     字体物理文件路径或资源地址
 * @param file         本地物理文件对象（若来源于类路径流则可能为 null）
 * @param fontBytes    字体原始二进制数据（已缓存至堆内存）
 * @param isCollection 是否为 TrueType Collection 字体集合（.ttc）
 * @author Ateng
 * @since 2026-10-23
 */
public record ResolvedFont(
        String fontName,
        FontResolutionTier tier,
        String location,
        File file,
        byte[] fontBytes,
        boolean isCollection
) {

    public ResolvedFont {
        Objects.requireNonNull(fontName, "fontName 不能为空");
        Objects.requireNonNull(tier, "tier 不能为空");
        Objects.requireNonNull(location, "location 不能为空");
        Objects.requireNonNull(fontBytes, "fontBytes 不能为空");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResolvedFont that)) {
            return false;
        }
        return isCollection == that.isCollection
                && Objects.equals(fontName, that.fontName)
                && tier == that.tier
                && Objects.equals(location, that.location)
                && Objects.equals(file, that.file)
                && Arrays.equals(fontBytes, that.fontBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(fontName, tier, location, file, isCollection);
        result = 31 * result + Arrays.hashCode(fontBytes);
        return result;
    }

    @Override
    public String toString() {
        return "ResolvedFont{"
                + "fontName='" + fontName + '\''
                + ", tier=" + tier
                + ", location='" + location + '\''
                + ", file=" + file
                + ", fontBytesLength=" + (fontBytes != null ? fontBytes.length : 0)
                + ", isCollection=" + isCollection
                + '}';
    }
}
