package minidb.storage;

import minidb.common.MiniDbException;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 磁盘管理器：以 4KB 页为单位读写数据库文件。
 * 文件就是页的线性序列：pageId * 4096 为页内偏移。
 */
public final class DiskManager implements AutoCloseable {
    private final Path file;
    private final RandomAccessFile raf;
    private long reads, writes;

    public DiskManager(Path file, boolean createIfMissing) {
        this.file = file;
        try {
            if (createIfMissing && !Files.exists(file)) {
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.createFile(file);
            }
            if (!Files.exists(file))
                throw new MiniDbException(MiniDbException.Code.IO, "数据库文件不存在: " + file);
            this.raf = new RandomAccessFile(file.toFile(), "rw");
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "打开数据库文件失败: " + file, e);
        }
    }

    public Path file() { return file; }
    public synchronized long reads() { return reads; }
    public synchronized long writes() { return writes; }

    /** 文件当前覆盖的页数（按文件长度推算，未写过的已分配页不算在内） */
    public synchronized long filePages() {
        try {
            return raf.length() / Page.SIZE;
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "获取文件长度失败", e);
        }
    }

    /** 读一页；页号非法或超出已分配范围由上层负责校验。文件未覆盖到的页补零（新页尚未写过）。 */
    public synchronized void readPage(int pageId, byte[] dst) {
        checkArgs(pageId, dst);
        try {
            long off = (long) pageId * Page.SIZE;
            if (off >= raf.length()) {
                java.util.Arrays.fill(dst, 0, Page.SIZE, (byte) 0);
                return;
            }
            raf.seek(off);
            int n = raf.read(dst, 0, Page.SIZE);
            if (n >= 0 && n < Page.SIZE) java.util.Arrays.fill(dst, n, Page.SIZE, (byte) 0);
            reads++;
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "读页失败: " + pageId, e);
        }
    }

    public synchronized void writePage(int pageId, byte[] src) {
        checkArgs(pageId, src);
        try {
            long off = (long) pageId * Page.SIZE;
            raf.seek(off);
            raf.write(src, 0, Page.SIZE);
            writes++;
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "写页失败: " + pageId, e);
        }
    }

    public synchronized void sync() {
        try {
            raf.getFD().sync();
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "fsync 失败", e);
        }
    }

    private void checkArgs(int pageId, byte[] buf) {
        if (pageId < 0)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "非法页号: " + pageId);
        if (buf == null || buf.length < Page.SIZE)
            throw new MiniDbException(MiniDbException.Code.PAGE_INVALID, "页缓冲区必须 >= " + Page.SIZE);
    }

    @Override
    public synchronized void close() {
        try {
            raf.close();
        } catch (IOException e) {
            throw new MiniDbException(MiniDbException.Code.IO, "关闭数据库文件失败", e);
        }
    }
}
