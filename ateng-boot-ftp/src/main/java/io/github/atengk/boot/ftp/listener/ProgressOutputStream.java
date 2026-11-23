package io.github.atengk.boot.ftp.listener;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 挂载进度节流器的受管输出流装饰器
 *
 * @author Ateng
 * @since 2026-10-30
 */
public class ProgressOutputStream extends FilterOutputStream {

    private final FtpProgressThrottler throttler;

    /**
     * 构建受管进度输出流
     *
     * @param out       底层真实输出流
     * @param throttler 进度节流器
     */
    public ProgressOutputStream(OutputStream out, FtpProgressThrottler throttler) {
        super(out);
        this.throttler = throttler;
    }

    @Override
    public void write(int b) throws IOException {
        super.write(b);
        if (throttler != null) {
            throttler.update(1);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        if (throttler != null && len > 0) {
            throttler.update(len);
        }
    }
}
