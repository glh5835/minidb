package minidb.wal;

import minidb.common.MiniDbException;

import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 预写日志（WAL，逻辑日志）：追加写文件，先于数据页落盘（fsync）。
 *
 * 记录布局（小端）：[lsn long][type byte][txnId long][payloadLen int][payload]，头 21 字节
 * payload：tableLen short + table utf8 + rid(pageId int, slot int) + 镜像（行字节：len int + bytes）
 * type：BEGIN/INSERT/DELETE/UPDATE/COMMIT/ABORT；INSERT 带 after；DELETE 带 before；
 * UPDATE 带 before+after；镜像 len<0 表示 NULL。
 */
public final class WalLog implements AutoCloseable {
    public static final byte BEGIN = 1, INSERT = 2, DELETE = 3, UPDATE = 4, COMMIT = 5, ABORT = 6;
    static final int HEAD = 21; // lsn 8 + type 1 + txnId 8 + len 4

    /** 一条解析后的日志 */
    public record Rec(long lsn, byte type, long txnId, String table, int pageId, int slot,
                      byte[] before, byte[] after) {}

    private final Path file;
    private final FileChannel ch;
    private long lsn;
    /** 关闭后 sync 仅刷 OS 缓冲语义（进程崩溃安全，断电不保证）——高吞吐批量导入/压测用。 */
    private volatile boolean fsyncEnabled = true;
    /** 已 fsync 的最大 LSN（含）与文件字节高水位：steal 协议下 FlushHook 的去重依据。 */
    private long flushedLsn;
    private long flushedBytes;

    public WalLog(Path file) {
        this.file = file;
        try {
            boolean fresh = !Files.exists(file);
            this.ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (!fresh) {
                // 跳过完整记录恢复 lsn（容忍尾部截断）
                while (true) {
                    long pos = ch.position();
                    ByteBuffer head = ByteBuffer.allocate(HEAD);
                    if (ch.read(head) < HEAD) {
                        ch.position(pos);
                        ch.truncate(pos);
                        break;
                    }
                    head.flip();
                    head.getLong(); // lsn
                    int len = head.getInt(17);
                    if (len < 0) {
                        ch.position(pos);
                        ch.truncate(pos);
                        break;
                    }
                    ByteBuffer body = ByteBuffer.allocate(len);
                    if (ch.read(body) < len) {
                        ch.position(pos);
                        ch.truncate(pos);
                        break;
                    }
                    ch.position(pos + (long) HEAD + len);
                }
            }
            this.lsn = ch.position() == 0 ? 1 : lastLsnHint(ch);
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "打开 WAL 失败: " + file, e);
        }
    }

    private long lastLsnHint(FileChannel ch) throws IOException {
        // 扫描完整记录，取最后一条 lsn
        long best = 1;
        ch.position(0);
        while (true) {
            long pos = ch.position();
            ByteBuffer head = ByteBuffer.allocate(HEAD);
            if (ch.read(head) < HEAD) break;
            head.flip();
            long l = head.getLong();
            int len = head.getInt(17);
            if (len < 0) break;
            if (ch.position() + len > ch.size()) break;
            ch.position(ch.position() + len);
            best = Math.max(best, l + 1);
        }
        ch.position(validEnd(ch));
        return best;
    }

    private long validEnd(FileChannel ch) throws IOException {
        long pos = 0, last = 0;
        ch.position(0);
        while (true) {
            pos = ch.position();
            ByteBuffer head = ByteBuffer.allocate(HEAD);
            if (ch.read(head) < HEAD) break;
            head.flip();
            head.getLong();
            int len = head.getInt(17);
            if (len < 0 || pos + (long) HEAD + len > ch.size()) break;
            ch.position(pos + (long) HEAD + len);
            last = pos + (long) HEAD + len;
        }
        return last;
    }

    /** 追加一条记录（写到 OS 缓冲，未 fsync）。返回 lsn。 */
    public synchronized long append(byte type, long txnId, String table, int pageId, int slot,
                                    byte[] before, byte[] after) {
        if (type == UPDATE && (before == null || after == null))
            throw new MiniDbException(MiniDbException.Code.WAL, "UPDATE 日志需要 before/after 镜像");
        try {
            int plen = payloadSize(table, before, after);
            ByteBuffer buf = ByteBuffer.allocate(HEAD + plen);
            buf.putLong(lsn);
            buf.put(type);
            buf.putLong(txnId);
            buf.putInt(plen); // 必须顺序写入：绝对写 [17..21) 会被 writePayload 从 17 起覆盖
            writePayload(buf, table, pageId, slot, before, after);
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
            return lsn++;
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL 追加失败", e);
        }
    }

    private static int payloadSize(String table, byte[] before, byte[] after) {
        int n = 2 + table.getBytes(StandardCharsets.UTF_8).length + 8;
        n += mirrorSize(before) + mirrorSize(after);
        return n;
    }

    private static int mirrorSize(byte[] m) {
        return m == null ? 4 : 4 + m.length;
    }

    private static void writePayload(ByteBuffer buf, String table, int pageId, int slot,
                                     byte[] before, byte[] after) {
        byte[] tb = table.getBytes(StandardCharsets.UTF_8);
        buf.putShort((short) tb.length);
        buf.put(tb);
        buf.putInt(pageId);
        buf.putInt(slot);
        writeMirror(buf, before);
        writeMirror(buf, after);
    }

    private static void writeMirror(ByteBuffer buf, byte[] m) {
        if (m == null) {
            buf.putInt(-1);
        } else {
            buf.putInt(m.length);
            buf.put(m);
        }
    }

    public void setFsyncEnabled(boolean on) {
        this.fsyncEnabled = on;
    }

    /** fsync：保证日志落盘（写数据页之前必须调用）。 */
    public synchronized void sync() {
        if (!fsyncEnabled) return;
        try {
            ch.force(true);
            flushedLsn = lsn - 1;
            flushedBytes = ch.position();
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL fsync 失败", e);
        }
    }

    /** 读出全部完整记录（容忍尾部截断）。 */
    /** 保证 lsn 及之前的记录已落盘；已 sync 过则 no-op（steal 淘汰路径高频调用）。 */
    public synchronized void syncUpTo(long lsn) {
        if (fsyncEnabled && lsn > flushedLsn) sync();
    }

    /** 已 fsync 的最大 LSN（含）。 */
    public synchronized long flushedLsn() { return flushedLsn; }

    /** 已 fsync 的文件字节高水位（断电模拟测试的"安全线"）。 */
    public synchronized long flushedBytes() { return flushedBytes; }

    /** 断电语义关闭：不 sync 直接关通道（配合 flushedBytes 模拟 OS 缓冲丢失）。 */
    public void abruptClose() {
        try {
            ch.close();
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL 关闭失败", e);
        }
    }

    public synchronized List<Rec> readAll() {
        List<Rec> out = new ArrayList<>();
        try {
            ch.position(0);
            ByteBuffer head = ByteBuffer.allocate(HEAD);
            while (true) {
                long pos = ch.position();
                head.clear();
                if (ch.read(head) < HEAD) break;
                head.flip();
                long lsn = head.getLong();
                byte type = head.get();
                long txnId = head.getLong();
                int len = head.getInt(17);
                if (len < 0 || pos + 17L + len > ch.size()) break;
                ByteBuffer body = ByteBuffer.allocate(len);
                if (ch.read(body) < len) break;
                body.flip();
                int tlen = body.getShort() & 0xFFFF;
                byte[] tb = new byte[tlen];
                body.get(tb);
                String table = new String(tb, StandardCharsets.UTF_8);
                int pageId = body.getInt();
                int slot = body.getInt();
                byte[] before = readMirror(body);
                byte[] after = readMirror(body);
                out.add(new Rec(lsn, type, txnId, table, pageId, slot, before, after));
            }
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL 读取失败", e);
        }
        return out;
    }

    private static byte[] readMirror(ByteBuffer b) {
        int len = b.getInt();
        if (len < 0) return null;
        byte[] m = new byte[len];
        b.get(m);
        return m;
    }

    /** 截断日志（checkpoint）。 */
    public synchronized void truncate() {
        try {
            ch.truncate(0);
            ch.position(0);
            lsn = 1;
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL 截断失败", e);
        }
    }

    public synchronized long size() {
        try {
            return ch.size();
        } catch (IOException e) {
            return -1;
        }
    }

    @Override
    public synchronized void close() {
        try {
            sync();
            ch.close();
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.WAL, "WAL 关闭失败", e);
        }
    }
}
