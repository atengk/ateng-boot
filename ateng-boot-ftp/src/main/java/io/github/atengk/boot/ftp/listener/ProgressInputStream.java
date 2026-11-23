package io.github.atengk.boot.ftp.listener;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 挂载进度节流器的受管输入流装饰器
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class ProgressInputStream extends FilterInputStream {

    private final FtpProgressThrottler throttler;

    /**
     * 构建受管进度输入流
     *
     * @param in        底层真实输入流
     * @param throttler 进度节流器
     */
    public ProgressInputStream(InputStream in, FtpProgressThrottler throttler) {
        super(in);
        this.throttler = throttler;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1 && throttler != null) {
            throttler.update(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = super.read(b, off, len);
        if (read > 0 && throttler != null) {
            throttler.update(read);
        }
        return read;
    }
}
