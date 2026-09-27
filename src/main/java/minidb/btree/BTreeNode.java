package minidb.btree;

import minidb.common.Bytes;
import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.BufferPool;
import minidb.storage.Page;

/**
 * B+ 树节点页视图（pin 状态下使用）。
 *
 * 页布局：
 * <pre>
 *  [0]      type（BTREE_LEAF / BTREE_INTERNAL）
 *  [1..3)   numKeys short
 *  [3..7)   next（叶链后继；内部节点不用）
 *  [7..11)  prev（叶链前继）
 *  [11..16) 保留
 *  keys:    long[i] @ 16 + 8i
 *  内部节点 children: int[i] @ ICHILD_OFF + 4i，共 numKeys+1 个（children[n] 为最右子树）
 *  叶节点 values: Rid @ LVALUE_OFF + 8i（pageId int + slot int）
 * </pre>
 * 插入采用“提前分裂”（节点满即分裂），因此节点数永不超过 MAX。
 */
final class BTreeNode {
    static final int LMAX = 255, LMIN = 127;
    static final int IMAX = 338, IMIN = 168;
    static final int OFF_TYPE = 0, OFF_NUM = 1, OFF_NEXT = 3, OFF_PREV = 7;
    static final int KEY_OFF = 16;
    static final int ICHILD_OFF = KEY_OFF + 8 * IMAX;   // 2720
    static final int LVALUE_OFF = KEY_OFF + 8 * LMAX;   // 2056

    private final BufferPool pool;
    private final Page page;

    BTreeNode(BufferPool pool, Page page) {
        this.pool = pool;
        this.page = page;
    }

    int pageId() { return page.pageId(); }
    boolean isLeaf() { return page.type() == Page.Type.BTREE_LEAF; }
    int minKeys() { return isLeaf() ? LMIN : IMIN; }
    int maxKeys() { return isLeaf() ? LMAX : IMAX; }

    int numKeys() { return Bytes.getShort(page.data(), OFF_NUM); }

    void numKeys(int n) {
        if (n > maxKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "节点键数越界: " + n);
        Bytes.putShort(page.data(), OFF_NUM, (short) n);
    }

    long key(int i) {
        if (i < 0 || i >= numKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "key 下标越界: " + i);
        return Bytes.getLong(page.data(), KEY_OFF + 8 * i);
    }

    void key(int i, long v) {
        if (i < 0 || i >= maxKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "key 下标越界: " + i);
        Bytes.putLong(page.data(), KEY_OFF + 8 * i, v);
    }

    int child(int i) {
        if (i < 0 || i > numKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "child 下标越界: " + i);
        return Bytes.getInt(page.data(), ICHILD_OFF + 4 * i);
    }

    void child(int i, int v) {
        if (i < 0 || i > maxKeys())
            throw new MiniDbException(MiniDbException.Code.BTREE, "child 下标越界: " + i);
        Bytes.putInt(page.data(), ICHILD_OFF + 4 * i, v);
    }

    Rid value(int i) {
        return new Rid(Bytes.getInt(page.data(), LVALUE_OFF + 8 * i),
                Bytes.getInt(page.data(), LVALUE_OFF + 8 * i + 4));
    }

    void value(int i, Rid rid) {
        Bytes.putInt(page.data(), LVALUE_OFF + 8 * i, rid.pageId());
        Bytes.putInt(page.data(), LVALUE_OFF + 8 * i + 4, rid.slot());
    }

    int next() { return Bytes.getInt(page.data(), OFF_NEXT); }
    void next(int v) { Bytes.putInt(page.data(), OFF_NEXT, v); }
    int prev() { return Bytes.getInt(page.data(), OFF_PREV); }
    void prev(int v) { Bytes.putInt(page.data(), OFF_PREV, v); }

    void dirty() { page.setDirty(true); }

    /** 把一页初始化为节点（isLeaf 决定类型），内容清零。 */
    static void initNew(BufferPool pool, int pageId, boolean leaf) {
        Page p = pool.getPage(pageId);
        try {
            java.util.Arrays.fill(p.data(), (byte) 0);
            p.data()[OFF_TYPE] = (leaf ? Page.Type.BTREE_LEAF : Page.Type.BTREE_INTERNAL).id;
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
        return n;
    }

    void unpin(boolean dirty) {
        pool.unpin(pageId(), dirty);
    }
}
