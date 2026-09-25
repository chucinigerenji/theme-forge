package com.dsh.themeforge;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;

/**
 * 7z 支持。
 *
 * 主题作者经常把 .mtz 打包成 7z 发布，甚至做成「自解压 exe」（PE 壳 + 7z 数据），
 * 所以这里先找 7z 签名（普通 7z 就在开头，SFX 的要跳过 PE 壳），再从里面取出 .mtz。
 * 用 Apache Commons Compress + XZ for Java（都是纯 Java，无需 native 库）。
 */
public class SevenZ {

    public interface Log {
        void log(String msg);
    }

    private static final byte[] SIG = {0x37, 0x7A, (byte) 0xBC, (byte) 0xAF, 0x27, 0x1C};

    /** 找出 7z 数据起点；找不到返回 -1。 */
    public static long findOffset(File f) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "r");
            long len = raf.length();
            byte[] head = new byte[6];
            if (len < 32) return -1;
            raf.readFully(head);
            if (match(head, 0)) return 0;
            // 不是标准 7z：多半是自解压 exe，扫前面的 PE 壳（一般 < 2MB）
            long limit = Math.min(len, 4L << 20);
            byte[] buf = new byte[1 << 16];
            long pos = 0;
            while (pos < limit) {
                raf.seek(pos);
                int n = raf.read(buf);
                if (n <= 0) break;
                for (int i = 0; i + 6 <= n; i++) {
                    if (match(buf, i)) return pos + i;
                }
                if (n < buf.length) break;
                pos += n - 5;
            }
            return -1;
        } catch (Throwable t) {
            return -1;
        } finally {
            Img.closeQuietly(raf);
        }
    }

    private static boolean match(byte[] b, int off) {
        for (int i = 0; i < 6; i++) if (b[off + i] != SIG[i]) return false;
        return true;
    }

    public static boolean is7z(File f) {
        return f != null && f.isFile() && findOffset(f) >= 0;
    }

    /**
     * 从 7z / 自解压 exe 里取出 .mtz 写到 out。
     * 没有 .mtz 结尾的条目时，取体积最大的那个条目（有些作者会把名字改成别的）。
     */
    public static File extractMtz(File archive, File out, Log log) throws Exception {
        long base = findOffset(archive);
        if (base < 0) throw new IOException("不是 7z 文件（找不到 7z 签名）");

        File tmp = new File(out.getAbsolutePath() + ".part");
        if (tmp.exists()) tmp.delete();

        RandomAccessFile raf = null;
        SevenZFile z = null;
        try {
            raf = new RandomAccessFile(archive, "r");
            z = new SevenZFile(new OffsetChannel(raf.getChannel(), base));
            byte[] buf = new byte[1 << 16];
            SevenZArchiveEntry e;
            SevenZArchiveEntry hit = null;
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase();
                if (n.endsWith(".mtz") || n.endsWith(".zip")) {
                    hit = e;
                    break;
                }
                // 不是目标就先读掉，才能继续下一个条目
                while (z.read(buf) > 0) {
                }
            }
            if (hit == null) throw new IOException("压缩包里没有 .mtz（只支持里面放主题包的 7z）");

            if (log != null) {
                log.log("正在解包 " + hit.getName() + "（"
                        + (hit.getSize() / 1048576) + " MB）…");
            }
            FileOutputStream fos = new FileOutputStream(tmp);
            try {
                long done = 0, total = Math.max(1, hit.getSize());
                int r;
                while ((r = z.read(buf)) > 0) {
                    fos.write(buf, 0, r);
                    done += r;
                    if (log != null && done % (8L << 20) < r) {
                        log.log("解包中… " + (done * 100 / total) + "%");
                    }
                }
            } finally {
                Img.closeQuietly(fos);
            }
        } finally {
            Img.closeQuietly(z);
            Img.closeQuietly(raf);
        }

        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) {
            throw new IOException("写入失败：" + out.getName());
        }
        if (log != null) log.log("已解包：" + out.getName());
        return out;
    }

    /** 从 7z 起点开始读的通道（跳过自解压的 PE 壳）。 */
    private static class OffsetChannel implements SeekableByteChannel {
        private final SeekableByteChannel in;
        private final long base;
        private long pos = 0;

        OffsetChannel(SeekableByteChannel in, long base) {
            this.in = in;
            this.base = base;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            in.position(base + pos);
            int n = in.read(dst);
            if (n > 0) pos += n;
            return n;
        }

        @Override
        public int write(ByteBuffer src) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return pos;
        }

        @Override
        public SeekableByteChannel position(long p) {
            pos = p;
            return this;
        }

        @Override
        public long size() throws IOException {
            return in.size() - base;
        }

        @Override
        public SeekableByteChannel truncate(long s) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return in.isOpen();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
