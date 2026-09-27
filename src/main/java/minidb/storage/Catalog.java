package minidb.storage;

import minidb.common.Bytes;
import minidb.common.MiniDbException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据目录：持久化在页链上（根 = StorageEngine.CATALOG_PAGE）。
 * 每页 [0]=类型 CATALOG, [1..3)=负载长度 short, [3..7)=nextPage int, 负载从 32 开始。
 * 保存时全量重写页链，多余页归还给存储引擎。
 */
public final class Catalog {
    /** 目录条目 */
    public record Entry(String name, Schema schema, int firstPage, List<IndexMeta> indexes) {
        public Entry {
            indexes = List.copyOf(indexes);
        }
    }

    private static final int PAYLOAD_OFF = TablePageHeader.HDR_SIZE;
    private static final int PAYLOAD_MAX = Page.SIZE - PAYLOAD_OFF;

    private final StorageEngine engine;
    private final List<Integer> chain = new ArrayList<>();

    public Catalog(StorageEngine engine) {
        this.engine = engine;
        chain.add(StorageEngine.CATALOG_PAGE);
        // 校验根页类型
        Page p = engine.pool().getPage(StorageEngine.CATALOG_PAGE);
        try {
            if (p.type() != Page.Type.FREE) { // 新文件读出全零 → FREE
                if (p.type() != Page.Type.CATALOG)
                    throw new MiniDbException(MiniDbException.Code.CATALOG,
                            "目录页类型错误: " + p.type());
            }
        } finally {
            engine.pool().unpin(StorageEngine.CATALOG_PAGE, false);
        }
    }

    public synchronized List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        // 先跟随页链收集全部负载
        List<byte[]> payloads = new ArrayList<>();
        int cur = StorageEngine.CATALOG_PAGE;
        List<Integer> pages = new ArrayList<>();
        while (cur != 0) {
            Page p = engine.pool().getPage(cur);
            int next;
            try {
                pages.add(cur);
                int len = Bytes.getShort(p.data(), 1);
                byte[] pl = new byte[len];
                System.arraycopy(p.data(), PAYLOAD_OFF, pl, 0, len);
                payloads.add(pl);
                next = Bytes.getInt(p.data(), 3);
            } finally {
                engine.pool().unpin(cur, false);
            }
            cur = next;
        }
        chain.clear();
        chain.addAll(pages);
        // 拼接解析条目
        byte[] all = concat(payloads);
        int off = 0;
        while (off < all.length) {
            int nameLen = Bytes.getShort(all, off); off += 2;
            String name = str(all, off, nameLen); off += nameLen;
            int schemaLen = Bytes.getInt(all, off); off += 4;
            Schema schema = Schema.decode(slice(all, off, schemaLen)); off += schemaLen;
            int firstPage = Bytes.getInt(all, off); off += 4;
            int idxCount = Bytes.getShort(all, off); off += 2;
            List<IndexMeta> idx = new ArrayList<>();
            for (int i = 0; i < idxCount; i++) {
                int inLen = Bytes.getShort(all, off); off += 2;
                String idxName = str(all, off, inLen); off += inLen;
                int cnLen = Bytes.getShort(all, off); off += 2;
                String colName = str(all, off, cnLen); off += cnLen;
                int root = Bytes.getInt(all, off); off += 4;
                idx.add(new IndexMeta(idxName, colName, root));
            }
            out.add(new Entry(name, schema, firstPage, idx));
        }
        return out;
    }

    public synchronized void save(List<Entry> entries) {
        // 序列化并按页切分（条目不跨页）
        List<byte[]> chunks = new ArrayList<>();
        byte[] cur = new byte[PAYLOAD_MAX];
        int used = 0;
        for (Entry e : entries) {
            byte[] raw = encodeEntry(e);
            if (raw.length > PAYLOAD_MAX)
                throw new MiniDbException(MiniDbException.Code.CATALOG, "目录条目过大: " + e.name());
            if (used + raw.length > PAYLOAD_MAX) {
                byte[] done = new byte[used];
                System.arraycopy(cur, 0, done, 0, used);
                chunks.add(done);
                used = 0;
            }
            System.arraycopy(raw, 0, cur, used, raw.length);
            used += raw.length;
        }
        byte[] done = new byte[used];
        System.arraycopy(cur, 0, done, 0, used);
        chunks.add(done);

        // 需要的页数 = chunks 数；不足则分配，多余则回收（从链尾开始）
        while (chain.size() < chunks.size()) {
            int pid = engine.allocPage();
            chain.add(pid);
        }
        while (chain.size() > chunks.size()) {
            int pid = chain.remove(chain.size() - 1);
            engine.freePage(pid);
        }
        // 写链
        for (int i = 0; i < chunks.size(); i++) {
            int pid = chain.get(i);
            int next = i + 1 < chain.size() ? chain.get(i + 1) : 0;
            Page p = engine.pool().getPage(pid);
            try {
                java.util.Arrays.fill(p.data(), (byte) 0);
                p.data()[0] = Page.Type.CATALOG.id;
                Bytes.putShort(p.data(), 1, (short) chunks.get(i).length);
                Bytes.putInt(p.data(), 3, next);
                System.arraycopy(chunks.get(i), 0, p.data(), PAYLOAD_OFF, chunks.get(i).length);
                p.setDirty(true);
            } finally {
                engine.pool().unpin(pid, true);
            }
        }
    }

    private byte[] encodeEntry(Entry e) {
        byte[] nameB = e.name().getBytes(StandardCharsets.UTF_8);
        byte[] schemaB = e.schema().encode();
        List<byte[]> idxParts = new ArrayList<>();
        int idxTotal = 2;
        for (IndexMeta im : e.indexes()) {
            byte[] in = im.name().getBytes(StandardCharsets.UTF_8);
            byte[] cn = im.column().getBytes(StandardCharsets.UTF_8);
            byte[] part = new byte[2 + in.length + 2 + cn.length + 4];
            Bytes.putShort(part, 0, (short) in.length);
            System.arraycopy(in, 0, part, 2, in.length);
            Bytes.putShort(part, 2 + in.length, (short) cn.length);
            System.arraycopy(cn, 0, part, 4 + in.length, cn.length);
            Bytes.putInt(part, 4 + in.length + cn.length, im.rootPage());
            idxParts.add(part);
            idxTotal += part.length;
        }
        byte[] out = new byte[2 + nameB.length + 4 + schemaB.length + 4 + idxTotal];
        int off = 0;
        Bytes.putShort(out, off, (short) nameB.length); off += 2;
        System.arraycopy(nameB, 0, out, off, nameB.length); off += nameB.length;
        Bytes.putInt(out, off, schemaB.length); off += 4;
        System.arraycopy(schemaB, 0, out, off, schemaB.length); off += schemaB.length;
        Bytes.putInt(out, off, e.firstPage()); off += 4;
        Bytes.putShort(out, off, (short) e.indexes().size()); off += 2;
        for (byte[] part : idxParts) {
            System.arraycopy(part, 0, out, off, part.length);
            off += part.length;
        }
        return out;
    }

    private static byte[] concat(List<byte[]> parts) {
        int total = 0;
        for (byte[] b : parts) total += b.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] b : parts) {
            System.arraycopy(b, 0, out, off, b.length);
            off += b.length;
        }
        return out;
    }

    private static byte[] slice(byte[] b, int off, int len) {
        byte[] out = new byte[len];
        System.arraycopy(b, off, out, 0, len);
        return out;
    }

    private static String str(byte[] b, int off, int len) {
        return new String(b, off, len, StandardCharsets.UTF_8);
    }
}
