package minidb.btree;

import minidb.common.Bytes;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.BufferPool;
import minidb.storage.Page;

/**
 * B+ 树节点页视图（变长键布局，pin 状态下使用）。
 *
 * 页布局（键区自前向后增长，值/子指针区自页尾向前增长）：
 * <pre>
 *  [0]      type（BTREE_LEAF / BTREE_INTERNAL）
 *  [1..3)   numKeys short
 *  [3..7)   next（叶链后继；内部节点不用）
 *  [7..11)  prev（叶链前继）
 *  [11]     布局标志 = 1（变长键）
 *  [12..16) 保留
 *  目录:    int[i] @ 16 + 4i —— 键 i 条目（[len short][bytes]）的起始偏移
 *  键区:    紧跟目录之后紧凑排布
 *  叶值区:  value[i]（Rid，8 字节）@ PAGE - 8*(numKeys - i)
 *  内部子指针: child[i]（int，4 字节）@ PAGE - 4*(numKeys + 1 - i)，共 numKeys+1 个
 * </pre>
 * 键长度上限 KEY_MAX=255 字节（UTF-8）。该上限保证：页满时至少 15 键
 * （单条目开销 ≤ 269 字节），字节均衡分裂后每侧 ≥ 7 键，合并后 ≤ 14 键必能
 * 放回一页——因此经典 B+ 树不变量（minKeys=7、分裂/借位/合并）全部成立，
 * 无需为大键页做任何特例。
 */
final class BTreeNode {
    static final int KEY_MAX = 255;
    static final int LMIN = 7, IMIN = 7;
    static final int OFF_TYPE = 0, OFF_NUM = 1, OFF_NEXT = 3, OFF_PREV = 7, OFF_FLAG = 11;
    static final int DIR_OFF = 16;
    /** [12..16)：键数据区物理末端（含目录空槽时也准确），避免依赖末目录槽的陈旧值。 */
    static final int OFF_END = 12;
    private static final int PAGE = Page.SIZE;

    private final BufferPool pool;
    private final Page page;

    BTreeNode(BufferPool pool, Page page) {
        this.pool = pool;
        this.page = page;
    }

    int pageId() { return page.pageId(); }
    boolean isLeaf() { return page.type() == Page.Type.BTREE_LEAF; }
    int minKeys() { return LMIN; }

    int numKeys() { return Bytes.getShort(page.data(), OFF_NUM); }

    void numKeys(int n) {
        if (n < 0 || n > 500)
            throw new MiniDbException(MiniDbException.Code.BTREE, "节点键数越界: " + n);
        Bytes.putShort(page.data(), OFF_NUM, (short) n);
    }

    // ---------- 无符号字典序比较 ----------

    static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (d != 0) return d;
        }
        return a.length - b.length;
    }

    // ---------- 键访问 ----------

    private int dir(int i) { return Bytes.getInt(page.data(), DIR_OFF + 4 * i); }

    private void dir(int i, int off) { Bytes.putInt(page.data(), DIR_OFF + 4 * i, off); }

    int keyLen(int i) {
        if (i < 0 || i >= numKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "key 下标越界: " + i);
        return Bytes.getShort(page.data(), dir(i)) & 0xFFFF;
    }

    byte[] key(int i) {
        int len = keyLen(i);
        byte[] out = new byte[len];
        System.arraycopy(page.data(), dir(i) + 2, out, 0, len);
        return out;
    }

    private int keyEnd() {
        return Bytes.getInt(page.data(), OFF_END);
    }

    private int keyBytesUsed() {
        int nk = numKeys(), sum = 0;
        for (int i = 0; i < nk; i++) sum += 2 + keyLen(i);
        return sum;
    }

    /** 条目数据字节（目录 + 键条目，不含页头；合并可行性检查用）。 */
    int usedEntries() {
        return 4 * numKeys() + keyBytesUsed();
    }

    /** 页内实际占用字节（头 + 目录 + 键 + 尾区）。 */
    int usedPageBytes() {
        int nk = numKeys();
        return DIR_OFF + 4 * nk + keyBytesUsed() + (isLeaf() ? 8 * nk : 4 * (nk + 1));
    }

    /** 叶节点能否容纳一条新键（含目录 4B、条目头 2B、值区 8B 开销）。 */
    boolean fitsLeaf(int len) {
        int nk = numKeys();
        return DIR_OFF + 4 * (nk + 1) + keyBytesUsed() + 2 + len + 8 * (nk + 1) <= PAGE;
    }

    /** 内部节点能否容纳一条新键（含子指针 4B 开销）。 */
    boolean fitsInternal(int len) {
        int nk = numKeys();
        return DIR_OFF + 4 * (nk + 1) + keyBytesUsed() + 2 + len + 4 * (nk + 2) <= PAGE;
    }

    // ---------- 值 / 子指针（定长尾区） ----------

    Rid value(int i) {
        int base = PAGE - 8 * numKeys();
        return new Rid(Bytes.getInt(page.data(), base + 8 * i),
                Bytes.getInt(page.data(), base + 8 * i + 4));
    }

    int child(int i) {
        if (i < 0 || i > numKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "child 下标越界: " + i);
        int base = PAGE - 4 * (numKeys() + 1);
        return Bytes.getInt(page.data(), base + 4 * i);
    }

    /** 原位改写第 i 个孩子指针（根分裂补写左孩子等场景）。 */
    void setChild(int i, int v) {
        int base = PAGE - 4 * (numKeys() + 1);
        Bytes.putInt(page.data(), base + 4 * i, v);
        dirty();
    }

    // ---------- 条目插入 / 删除原语 ----------
    // 键数据操作与尾区操作分离；调用方保证 fits 已通过。

    /** 在 pos 处插入键数据（保序，且数据区始终紧贴目录压实——历史空隙一并归零）。 */
    private void insertKeyData(int pos, byte[] key) {
        int nk = numKeys();
        byte[] d = page.data();
        int es = 2 + key.length;
        int E = keyEnd();
        int H = nk > 0 ? dir(0) : E;          // 数据区实际起点
        int offPos = pos < nk ? dir(pos) : E; // 新条目逻辑后继的数据起点
        int headBytes = offPos - H;
        int D = DIR_OFF + 4 * nk;             // 扩位后的目录末尾
        // 尾部先移：[offPos, E) → [D+4+headBytes+es, ...)
        System.arraycopy(d, offPos, d, D + 4 + headBytes + es, E - offPos);
        // 头部后移：[H, offPos) → [D+4, ...)（目录扩位 + 消灭历史空隙）
        System.arraycopy(d, H, d, D + 4, headBytes);
        // 目录槽 [pos..nk) 上移一位
        System.arraycopy(d, DIR_OFF + 4 * pos, d, DIR_OFF + 4 * (pos + 1), 4 * (nk - pos));
        // 修偏移：头部 +（D+4-H）；尾部 +（D+4+headBytes+es-offPos）
        int headDelta = D + 4 - H;
        int tailDelta = D + 4 + headBytes + es - offPos;
        for (int i = 0; i < pos; i++) dir(i, dir(i) + headDelta);
        for (int i = pos + 1; i <= nk; i++) dir(i, dir(i) + tailDelta);
        dir(pos, D + 4 + headBytes);
        Bytes.putShort(d, D + 4 + headBytes, (short) key.length);
        System.arraycopy(key, 0, d, D + 4 + headBytes + 2, key.length);
        Bytes.putInt(d, OFF_END, D + 4 + headBytes + es + (E - offPos));
    }

    /** 删除 pos 处键数据（关键据洞 + 关目录洞，保持物理序=逻辑序）。 */
    private void removeKeyData(int pos) {
        int nk = numKeys();
        byte[] d = page.data();
        int off = dir(pos);
        int es = 2 + keyLen(pos);
        int end = keyEnd();
        int dataStart = DIR_OFF + 4 * nk;
        // 关键据洞：pos 之后的数据前移 es
        System.arraycopy(d, off + es, d, off, end - off - es);
        // 删目录槽 pos
        System.arraycopy(d, DIR_OFF + 4 * (pos + 1), d, DIR_OFF + 4 * pos, 4 * (nk - 1 - pos));
        // 关目录洞：整个数据区（头+尾）前移 4
        System.arraycopy(d, dataStart, d, dataStart - 4, end - es - dataStart);
        // 修偏移：全体 -4（目录缩一位）；pos 之后的条目额外 -es
        for (int i = 0; i < nk - 1; i++) {
            int v = dir(i);
            dir(i, i < pos ? v - 4 : v - 4 - es);
        }
    }

    /** 原位替换第 i 个键是否放得下（变长键下新分隔键可能更长；借位前检查）。 */
    boolean fitsReplace(int i, byte[] newKey) {
        int delta = 2 + newKey.length - (2 + keyLen(i));
        if (delta <= 0) return true;
        return usedPageBytes() + delta <= PAGE;
    }

    /** 原位替换第 i 个键：尾部条目数据搬移 delta，其余不动（不产生陈旧目录槽）。 */
    void replaceKey(int i, byte[] newKey) {
        int nk = numKeys();
        byte[] d = page.data();
        int off = dir(i);
        int esOld = 2 + keyLen(i);
        int esNew = 2 + newKey.length;
        int end = keyEnd();
        int delta = esNew - esOld;
        if (delta != 0) {
            System.arraycopy(d, off + esOld, d, off + esNew, end - off - esOld);
            Bytes.putInt(d, OFF_END, end + delta);
            for (int j = i + 1; j < nk; j++) dir(j, dir(j) + delta);
        }
        Bytes.putShort(d, off, (short) newKey.length);
        System.arraycopy(newKey, 0, d, off + 2, newKey.length);
        dirty();
    }

    /** 叶：在 pos 插入键值。 */
    void insertLeafEntry(int pos, byte[] key, Rid v) {
        int nk = numKeys();
        byte[] d = page.data();
        insertKeyData(pos, key);
        // 值区：仅 [0..pos) 下移 8（pos 之后的旧值地址与目标位天然重合，不得搬移）
        int oldBase = PAGE - 8 * nk;
        System.arraycopy(d, oldBase, d, oldBase - 8, 8 * pos);
        int base = PAGE - 8 * (nk + 1);
        Bytes.putInt(d, base + 8 * pos, v.pageId());
        Bytes.putInt(d, base + 8 * pos + 4, v.slot());
        numKeys(nk + 1);
        dirty();
    }

    /** 叶：删除 pos 条目。 */
    void removeLeafEntry(int pos) {
        int nk = numKeys();
        removeKeyData(pos);
        // 值区：[0..pos) 上移 8（pos 之后的旧值地址与目标位天然重合）
        int oldBase = PAGE - 8 * nk;
        System.arraycopy(page.data(), oldBase, page.data(), oldBase + 8, 8 * pos);
        numKeys(nk - 1);
        dirty();
    }

    /** 内部：在 pos 插入分隔键 + 右孩子。 */
    void insertInternalEntry(int pos, byte[] key, int childPtr) {
        int nk = numKeys();
        byte[] d = page.data();
        insertKeyData(pos, key);
        // 子指针区：仅 [0..pos] 下移 4（新指针写 pos+1 槽；其后旧指针地址与目标位天然重合）
        int oldBase = PAGE - 4 * (nk + 1);
        System.arraycopy(d, oldBase, d, oldBase - 4, 4 * (pos + 1));
        int base = PAGE - 4 * (nk + 2);
        Bytes.putInt(d, base + 4 * (pos + 1), childPtr);
        numKeys(nk + 1);
        dirty();
    }

    /** 内部：删除 pos 分隔键及其右孩子（child[pos+1]，合并场景）。 */
    void removeInternalEntry(int pos) {
        int nk = numKeys();
        byte[] d = page.data();
        int oldBase = PAGE - 4 * (nk + 1);
        // child[0..pos] 上移 4（child[pos+1] 被丢弃，其后各指针自然对位）
        System.arraycopy(d, oldBase, d, oldBase + 4, 4 * (pos + 1));
        removeKeyData(pos);
        numKeys(nk - 1);
        dirty();
    }

    /** 内部：删除 pos 分隔键及其左孩子（child[pos]，借位场景）。 */
    void removeInternalKeyLeftChild(int pos) {
        int nk = numKeys();
        byte[] d = page.data();
        int oldBase = PAGE - 4 * (nk + 1);
        // child[0..pos-1] 上移 4（child[pos] 被丢弃，其后各指针自然对位）
        System.arraycopy(d, oldBase, d, oldBase + 4, 4 * pos);
        removeKeyData(pos);
        numKeys(nk - 1);
        dirty();
    }

    /** 清空并批量装载条目（分裂/合并重建用，O(n)）。 */
    void loadLeaf(byte[][] keys, Rid[] vals, int from, int count) {
        numKeys(0);
        Bytes.putInt(page.data(), OFF_END, DIR_OFF);
        java.util.Arrays.fill(page.data(), DIR_OFF, PAGE, (byte) 0);
        for (int i = 0; i < count; i++)
            insertLeafEntry(i, keys[from + i], vals[from + i]);
    }

    void loadInternal(byte[][] keys, int[] children, int from, int count) {
        // children 相对 keys 多一个条目：children[from] 为最左孩子，children[from+i+1] 为 key[from+i] 的右孩子
        numKeys(0);
        Bytes.putInt(page.data(), OFF_END, DIR_OFF);
        java.util.Arrays.fill(page.data(), DIR_OFF, PAGE, (byte) 0);
        for (int i = 0; i < count; i++)
            insertInternalEntry(i, keys[from + i], children[from + i + 1]);
        setChild(0, children[from]); // insertInternalEntry 只写右孩子，最左孩子须补写
    }

    int next() { return Bytes.getInt(page.data(), OFF_NEXT); }
    void next(int v) { Bytes.putInt(page.data(), OFF_NEXT, v); }
    int prev() { return Bytes.getInt(page.data(), OFF_PREV); }
    void prev(int v) { Bytes.putInt(page.data(), OFF_PREV, v); }

    void dirty() { page.setDirty(true); }

    /** 把一页初始化为节点（isLeaf 决定类型），内容清零并写入变长键布局标志。 */
    static void initNew(BufferPool pool, int pageId, boolean leaf) {
        Page p = pool.getPage(pageId);
        try {
            java.util.Arrays.fill(p.data(), (byte) 0);
            p.data()[OFF_TYPE] = (leaf ? Page.Type.BTREE_LEAF : Page.Type.BTREE_INTERNAL).id;
            p.data()[OFF_FLAG] = 1;
            Bytes.putInt(p.data(), OFF_END, DIR_OFF);
            p.setDirty(true);
        } finally {
            pool.unpin(pageId, true);
        }
    }

    static BTreeNode pin(BufferPool pool, int pageId) {
        Page p = pool.getPage(pageId);
        BTreeNode n = new BTreeNode(pool, p);
        Page.Type t = p.type();
        if (t != Page.Type.BTREE_LEAF && t != Page.Type.BTREE_INTERNAL)
            throw new MiniDbException(MiniDbException.Code.BTREE,
                    "页 " + pageId + " 不是 B+ 树节点: " + t);
        if (p.data()[OFF_FLAG] != 1)
            throw new MiniDbException(MiniDbException.Code.BTREE,
                    "页 " + pageId + " 不是变长键布局");
        return n;
    }

    void unpin(boolean dirty) {
        pool.unpin(pageId(), dirty);
    }
}
