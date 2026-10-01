package minidb.btree;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.BufferPool;
import minidb.storage.Page;
import minidb.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * B+ 树索引：变长键（UTF-8 字节序），value 为行 RID。
 * 所有节点都是 4KB 页，通过 BufferPool 读写（绝不直接操作文件）。
 * 唯一索引：插入重复 key 返回 false。
 * 叶节点构成双向链表支持范围扫描。
 *
 * 键序：无符号字典序。long 门面用符号位翻转编码（8 字节大端），使数值序与
 * 字节序一致；String 门面直接存 UTF-8。
 *
 * 策略：插入用“按需预分裂”——叶/内部节点放不下新条目时先做字节均衡分裂，
 * 再落入对应半侧（分裂后每侧必有空间，不会连锁分裂）；删除后低于 LMIN=7
 * 时先向兄弟借位（需父分隔键可原位替换）、借不到则字节放得下才合并，
 * 都不行时容忍暂时 underflow（后续删除会自愈）。分隔键约定 push-right：
 * child[i] 子树的所有 key < keys[i] ≤ child[i+1] 子树的最小 key。
 */
public final class BPlusTree {
    /** 索引键字节上限（与 BTreeNode.KEY_MAX 一致，公开供建索引校验用）。 */
    public static final int MAX_KEY_BYTES = BTreeNode.KEY_MAX;

    private final StorageEngine engine;
    private final BufferPool pool;
    private int root;
    private int height;

    /** rootPage=0 表示创建新树（自动分配根叶页）。 */
    public BPlusTree(StorageEngine engine, int rootPage) {
        this.engine = engine;
        this.pool = engine.pool();
        if (rootPage == 0) {
            int pid = engine.allocPage();
            BTreeNode.initNew(pool, pid, true);
            this.root = pid;
            this.height = 1;
        } else {
            BTreeNode r = BTreeNode.pin(pool, rootPage);
            r.unpin(false);
            this.root = rootPage;
            this.height = computeHeight(rootPage);
        }
    }

    public int rootPage() { return root; }
    public int height() { return height; }

    // ---------- 键编码 ----------

    /** long → 8 字节大端 + 符号位翻转（无符号字典序 = 有符号数值序）。 */
    public static byte[] encodeLong(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (56 - 8 * i));
        b[0] ^= 0x80;
        return b;
    }

    public static long decodeLong(byte[] stored) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int b = stored[i] & 0xFF;
            if (i == 0) b ^= 0x80;
            v = (v << 8) | b;
        }
        return v;
    }

    /** String → UTF-8 字节（超长抛 BTREE）。 */
    public static byte[] encodeString(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_KEY_BYTES)
            throw new MiniDbException(MiniDbException.Code.BTREE,
                    "索引键超长（" + b.length + " > " + MAX_KEY_BYTES + " 字节）: " + s);
        return b;
    }

    private static byte[] checkKey(byte[] k) {
        if (k.length > MAX_KEY_BYTES)
            throw new MiniDbException(MiniDbException.Code.BTREE,
                    "索引键超长（" + k.length + " > " + MAX_KEY_BYTES + " 字节）");
        return k;
    }

    private int computeHeight(int pageId) {
        int h = 1;
        int cur = pageId;
        while (true) {
            BTreeNode n = BTreeNode.pin(pool, cur);
            try {
                if (n.isLeaf()) return h;
                cur = n.child(0);
            } finally {
                n.unpin(false);
            }
            h++;
        }
    }

    private int allocNode(boolean leaf) {
        int pid = engine.allocPage();
        BTreeNode.initNew(pool, pid, leaf);
        return pid;
    }

    // ---------- long 门面 ----------

    public Rid search(long key) { return searchBytes(encodeLong(key)); }
    public boolean insert(long key, Rid value) { return insertBytes(encodeLong(key), value); }
    public boolean delete(long key) { return deleteBytes(encodeLong(key)); }

    public List<Rid> rangeScan(long from, long to) {
        return rangeScan(from, true, to, true);
    }

    public List<Rid> rangeScan(long from, boolean fromInc, long to, boolean toInc) {
        return rangeScanBytes(encodeLong(from), fromInc, encodeLong(to), toInc);
    }

    // ---------- String 门面 ----------

    public Rid search(String key) { return searchBytes(encodeString(key)); }
    public boolean insert(String key, Rid value) { return insertBytes(encodeString(key), value); }
    public boolean delete(String key) { return deleteBytes(encodeString(key)); }

    /** from/to 为 null 表示无界。 */
    public List<Rid> rangeScan(String from, boolean fromInc, String to, boolean toInc) {
        return rangeScanBytes(from == null ? null : encodeString(from), fromInc,
                to == null ? null : encodeString(to), toInc);
    }

    // ---------- 字节键核心 ----------

    /** 等值查找；未命中返回 null。 */
    public Rid searchBytes(byte[] key) {
        int leafId = findLeaf(key);
        BTreeNode leaf = BTreeNode.pin(pool, leafId);
        try {
            int i = lowerBound(leaf, key, false);
            if (i < leaf.numKeys() && BTreeNode.compare(leaf.key(i), key) == 0) return leaf.value(i);
            return null;
        } finally {
            leaf.unpin(false);
        }
    }

    /** 下行到 key 应所在的叶节点页。 */
    private int findLeaf(byte[] key) {
        int cur = root;
        while (true) {
            BTreeNode n = BTreeNode.pin(pool, cur);
            try {
                if (n.isLeaf()) return cur;
                cur = n.child(childIndex(n, key));
            } finally {
                n.unpin(false);
            }
        }
    }

    /** 内部节点下行：child[i] 子树所有 key < keys[i]；child[n] 子树 keys ≥ keys[n-1]。 */
    private static int childIndex(BTreeNode n, byte[] key) {
        int lo = 0, hi = n.numKeys();
        while (lo < hi) { // upperBound：第一个 keys[i] > key 的下标
            int mid = (lo + hi) >>> 1;
            if (BTreeNode.compare(n.key(mid), key) > 0) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** 叶内 lowerBound：第一个 keys[i] >= key（strict 时 > key）的下标。 */
    private static int lowerBound(BTreeNode leaf, byte[] key, boolean strict) {
        int lo = 0, hi = leaf.numKeys();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            int c = BTreeNode.compare(leaf.key(mid), key);
            if (c > 0 || (c == 0 && !strict)) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    // ---------- 插入 ----------

    /** 插入；key 已存在返回 false（唯一索引）。 */
    public boolean insertBytes(byte[] key, Rid value) {
        checkKey(key);
        boolean[] inserted = {false};
        byte[][] upKey = new byte[1][];
        int[] newRight = {0};
        insertRec(root, key, value, inserted, upKey, newRight);
        if (newRight[0] != 0) {
            // 根分裂：新建内部根（insertInternalEntry 保留 pos 槽左孩子——新页为空，须补写旧根）
            int newRoot = allocNode(false);
            BTreeNode rn = BTreeNode.pin(pool, newRoot);
            try {
                rn.insertInternalEntry(0, upKey[0], newRight[0]);
                rn.setChild(0, root);
            } finally {
                rn.unpin(true);
            }
            root = newRoot;
            height++;
        }
        return inserted[0];
    }

    /**
     * 在 node 子树插入。若本层发生预分裂，upKey[0]=上推键、newRight[0]=新右节点页号
     * （否则 newRight[0]=0）。调用约定：本方法内部持有 node 的 pin 直至返回。
     */
    private void insertRec(int nodeId, byte[] key, Rid value,
                           boolean[] inserted, byte[][] upKey, int[] newRight) {
        BTreeNode n = BTreeNode.pin(pool, nodeId);
        try {
            if (n.isLeaf()) {
                int i = lowerBound(n, key, false);
                if (i < n.numKeys() && BTreeNode.compare(n.key(i), key) == 0) {
                    inserted[0] = false;
                    return;
                }
                if (!n.fitsLeaf(key.length)) {
                    // 预分裂：本叶放不下 → 字节均衡分裂，落入对应半侧（必有空间，不再分裂）。
                    // 重复键在上方全叶 lowerBound 已捕获，分裂后无需复查（否则会孤儿化已分裂的右页）。
                    splitLeaf(n, upKey, newRight);
                    if (BTreeNode.compare(key, upKey[0]) >= 0) {
                        int rightId = newRight[0];
                        n.unpin(true);
                        n = BTreeNode.pin(pool, rightId);
                        i = lowerBound(n, key, false);
                    }
                }
                n.insertLeafEntry(i, key, value);
                inserted[0] = true;
            } else {
                int ci = childIndex(n, key);
                int childId = n.child(ci);
                byte[][] cUp = new byte[1][];
                int[] cRight = {0};
                insertRec(childId, key, value, inserted, cUp, cRight);
                if (cRight[0] != 0) {
                    // child 分裂：需要在本节点插入分隔键 cUp[0] 与指针 cRight[0]
                    if (!n.fitsInternal(cUp[0].length)) {
                        splitInternal(n, upKey, newRight);
                        int rightId = newRight[0];
                        // cUp 与本层上推键比较决定落点；两侧分裂后必有空间
                        if (BTreeNode.compare(cUp[0], upKey[0]) >= 0) {
                            n.unpin(true);
                            n = BTreeNode.pin(pool, rightId);
                            ci = childIndex(n, cUp[0]);
                        }
                        n.insertInternalEntry(ci, cUp[0], cRight[0]);
                    } else {
                        n.insertInternalEntry(ci, cUp[0], cRight[0]);
                    }
                }
            }
        } finally {
            if (n != null) n.unpin(true);
        }
    }

    /** 叶分裂（push-right），字节均衡；n 收缩为左半，右半为新页。 */
    private void splitLeaf(BTreeNode n, byte[][] upKey, int[] newRight) {
        int nk = n.numKeys();
        byte[][] keys = new byte[nk][];
        Rid[] vals = new Rid[nk];
        for (int i = 0; i < nk; i++) {
            keys[i] = n.key(i);
            vals[i] = n.value(i);
        }
        int leftN = splitIndex(keys);
        int rightN = nk - leftN;
        int rightId = allocNode(true);
        BTreeNode r = BTreeNode.pin(pool, rightId);
        try {
            r.loadLeaf(keys, vals, leftN, rightN);
            r.prev(n.pageId());
            r.next(n.next());
            r.dirty();
        } finally {
            r.unpin(true);
        }
        if (n.next() != 0) {
            int oldNext = n.next();
            BTreeNode nn = BTreeNode.pin(pool, oldNext);
            try {
                nn.prev(rightId);
                nn.dirty();
            } finally {
                nn.unpin(true);
            }
        }
        n.loadLeaf(keys, vals, 0, leftN);
        n.next(rightId); // 关键：左节点链向新右节点
        n.dirty();
        upKey[0] = keys[leftN]; // 右半最小键上推
        newRight[0] = rightId;
    }

    /** 内部节点分裂（中间键上推，不保留在任何子节点），字节均衡。 */
    private void splitInternal(BTreeNode n, byte[][] upKey, int[] newRight) {
        int nk = n.numKeys();
        byte[][] keys = new byte[nk][];
        int[] children = new int[nk + 1];
        for (int i = 0; i < nk; i++) keys[i] = n.key(i);
        for (int i = 0; i <= nk; i++) children[i] = n.child(i);
        int mid = splitIndex(keys); // keys[mid] 上推
        int rightN = nk - mid - 1;
        int rightId = allocNode(false);
        BTreeNode r = BTreeNode.pin(pool, rightId);
        try {
            // 右半：keys[mid+1..]，children[mid+1..]（children[mid+1] 为其最左孩子）
            int[] rc = new int[rightN + 1];
            byte[][] rk = new byte[rightN][];
            for (int i = 0; i < rightN; i++) rk[i] = keys[mid + 1 + i];
            for (int i = 0; i <= rightN; i++) rc[i] = children[mid + 1 + i];
            r.loadInternal(rk, rc, 0, rightN);
        } finally {
            r.unpin(true);
        }
        // 左半：keys[0..mid)，children[0..mid]
        byte[][] lk = new byte[mid][];
        int[] lc = new int[mid + 1];
        for (int i = 0; i < mid; i++) lk[i] = keys[i];
        for (int i = 0; i <= mid; i++) lc[i] = children[i];
        n.loadInternal(lk, lc, 0, mid);
        n.dirty();
        upKey[0] = keys[mid];
        newRight[0] = rightId;
    }

    /**
     * 字节均衡分裂点：左侧条目字节累计过半即停；钳位到 [LMIN, nk-LMIN]。
     * 调用前提：nk ≥ 15（页满才有分裂），钳位恒可行。
     */
    private static int splitIndex(byte[][] keys) {
        int nk = keys.length;
        long total = 0;
        for (byte[] k : keys) total += 2 + k.length;
        long acc = 0;
        int idx = 0;
        while (idx < nk) {
            acc += 2 + keys[idx].length;
            idx++;
            if (acc >= (total + 1) / 2 && idx >= BTreeNode.LMIN) break;
        }
        if (idx < BTreeNode.LMIN) idx = BTreeNode.LMIN;
        if (idx > nk - BTreeNode.LMIN) idx = nk - BTreeNode.LMIN;
        return idx;
    }

    // ---------- 删除 ----------

    /** 删除；key 不存在返回 false。 */
    public boolean deleteBytes(byte[] key) {
        boolean[] removed = {false};
        deleteRec(root, key, removed);
        if (removed[0]) demoteRootIfNeeded();
        return removed[0];
    }

    private void demoteRootIfNeeded() {
        while (true) {
            BTreeNode r = BTreeNode.pin(pool, root);
            boolean leaf = r.isLeaf();
            int nk = r.numKeys();
            int onlyChild = nk == 0 && !leaf ? r.child(0) : 0;
            r.unpin(false);
            if (leaf || nk > 0) return;
            int oldRoot = root;
            root = onlyChild;
            height--;
            engine.freePage(oldRoot); // 空内部根页归还引擎（pin 已释放）
        }
    }

    /** 调用约定：持有 nodeId 的 pin 直至返回；removed[0] 后沿途检查孩子 underflow。 */
    private void deleteRec(int nodeId, byte[] key, boolean[] removed) {
        BTreeNode n = BTreeNode.pin(pool, nodeId);
        try {
            if (n.isLeaf()) {
                int i = lowerBound(n, key, false);
                if (i < n.numKeys() && BTreeNode.compare(n.key(i), key) == 0) {
                    n.removeLeafEntry(i);
                    removed[0] = true;
                }
                return;
            }
            int ci = childIndex(n, key);
            int childId = n.child(ci);
            deleteRec(childId, key, removed);
            if (removed[0]) {
                BTreeNode c = BTreeNode.pin(pool, childId);
                int ck = c.numKeys();
                c.unpin(false);
                if (ck < c.minKeys()) fixUnderflow(n, ci);
            }
        } finally {
            n.unpin(true);
        }
    }

    /** 修复 parent.child(ci) 的 underflow（parent 处于 pin 状态）。 */
    private void fixUnderflow(BTreeNode parent, int ci) {
        int childId = parent.child(ci);
        BTreeNode child = BTreeNode.pin(pool, childId);
        int[] toFree = {0}; // 合并产生的待回收页（须等 child unpin 后再 free）
        try {
            // 1) 向左兄借（需父分隔键可原位替换）
            if (ci > 0) {
                int leftId = parent.child(ci - 1);
                BTreeNode left = BTreeNode.pin(pool, leftId);
                try {
                    if (left.numKeys() > left.minKeys()) {
                        byte[] sep = left.key(left.numKeys() - 1);
                        if (parent.fitsReplace(ci - 1, sep)) {
                            borrowFromLeft(parent, ci, left, child);
                            return;
                        }
                    }
                } finally {
                    left.unpin(true);
                }
            }
            // 2) 向右兄借
            if (ci < parent.numKeys()) {
                int rightId = parent.child(ci + 1);
                BTreeNode right = BTreeNode.pin(pool, rightId);
                try {
                    if (right.numKeys() > right.minKeys()) {
                        byte[] sep = right.key(0);
                        if (parent.fitsReplace(ci, sep)) {
                            borrowFromRight(parent, ci, child, right);
                            return;
                        }
                    }
                } finally {
                    right.unpin(true);
                }
            }
            // 3) 合并（优先与左兄；合并后节点放不下一页时容忍 underflow，后续删除自愈）
            if (ci > 0 && mergedFits(parent, ci - 1)) {
                toFree[0] = mergeChildren(parent, ci - 1);
            } else if (ci < parent.numKeys() && mergedFits(parent, ci)) {
                toFree[0] = mergeChildren(parent, ci);
            }
            // 两侧都放不进一页：保持 underflow（键仍在树中，查找/扫描正确）
        } finally {
            child.unpin(true);
        }
        if (toFree[0] != 0) engine.freePage(toFree[0]);
    }

    /** 合并 parent.child(sepIdx) 与 child(sepIdx+1) 后能否放回一页。 */
    private boolean mergedFits(BTreeNode parent, int sepIdx) {
        BTreeNode a = BTreeNode.pin(pool, parent.child(sepIdx));
        BTreeNode b = BTreeNode.pin(pool, parent.child(sepIdx + 1));
        try {
            int entries = a.numKeys() + b.numKeys();
            int bytes = a.usedEntries() + b.usedEntries();
            boolean leaf = a.isLeaf();
            int need = BTreeNode.DIR_OFF + 4 * entries + bytes
                    + (leaf ? 8 * entries : 4 * (entries + 1));
            return need <= Page.SIZE;
        } finally {
            a.unpin(false);
            b.unpin(false);
        }
    }

    private void borrowFromLeft(BTreeNode parent, int ci, BTreeNode left, BTreeNode child) {
        int lk = left.numKeys();
        if (child.isLeaf()) {
            byte[] k = left.key(lk - 1);
            Rid v = left.value(lk - 1);
            left.removeLeafEntry(lk - 1);
            child.insertLeafEntry(0, k, v);
            parent.replaceKey(ci - 1, k); // 分隔键 = 移过去的键
        } else {
            byte[] downKey = parent.key(ci - 1);
            int movedChild = left.child(lk);
            byte[] upKey = left.key(lk - 1);
            left.removeInternalEntry(lk - 1);
            child.insertInternalEntry(0, downKey, movedChild);
            parent.replaceKey(ci - 1, upKey);
        }
        parent.dirty();
    }

    private void borrowFromRight(BTreeNode parent, int ci, BTreeNode child, BTreeNode right) {
        int rk = right.numKeys();
        if (child.isLeaf()) {
            byte[] k = right.key(0);
            Rid v = right.value(0);
            right.removeLeafEntry(0);
            child.insertLeafEntry(child.numKeys(), k, v);
            parent.replaceKey(ci, right.key(0)); // 分隔键 = 右兄新的最小键
        } else {
            byte[] downKey = parent.key(ci);
            int movedChild = right.child(0);
            byte[] upKey = right.key(0);
            right.removeInternalKeyLeftChild(0); // 右兄失去 key[0] 与左孩子 child[0]
            child.insertInternalEntry(child.numKeys(), downKey, movedChild);
            parent.replaceKey(ci, upKey);
        }
        parent.dirty();
    }

    /** 把 parent.child(sepIdx) 与 parent.child(sepIdx+1) 合并（右并入左），父删分隔键；返回被清空的右节点页号。 */
    private int mergeChildren(BTreeNode parent, int sepIdx) {
        int leftId = parent.child(sepIdx);
        int rightId = parent.child(sepIdx + 1);
        BTreeNode left = BTreeNode.pin(pool, leftId);
        BTreeNode right = BTreeNode.pin(pool, rightId);
        try {
            if (left.isLeaf()) {
                int lk = left.numKeys(), rk = right.numKeys();
                byte[][] keys = new byte[lk + rk][];
                Rid[] vals = new Rid[lk + rk];
                for (int j = 0; j < lk; j++) {
                    keys[j] = left.key(j);
                    vals[j] = left.value(j);
                }
                for (int j = 0; j < rk; j++) {
                    keys[lk + j] = right.key(j);
                    vals[lk + j] = right.value(j);
                }
                left.loadLeaf(keys, vals, 0, lk + rk);
                int rightNext = right.next();
                left.next(rightNext);
                if (rightNext != 0) {
                    BTreeNode nn = BTreeNode.pin(pool, rightNext);
                    try {
                        nn.prev(leftId);
                        nn.dirty();
                    } finally {
                        nn.unpin(true);
                    }
                }
            } else {
                int lk = left.numKeys(), rk = right.numKeys();
                byte[][] keys = new byte[lk + 1 + rk][];
                int[] children = new int[lk + 1 + rk + 1];
                keys[lk] = parent.key(sepIdx); // 分隔键下坠
                for (int j = 0; j < lk; j++) keys[j] = left.key(j);
                for (int j = 0; j < rk; j++) keys[lk + 1 + j] = right.key(j);
                for (int j = 0; j <= lk; j++) children[j] = left.child(j);
                for (int j = 0; j <= rk; j++) children[lk + 1 + j] = right.child(j);
                left.loadInternal(keys, children, 0, lk + 1 + rk);
            }
            left.dirty();
        } finally {
            left.unpin(true);
            right.unpin(true);
        }
        parent.removeInternalEntry(sepIdx);
        return rightId;
    }

    // ---------- 范围扫描 ----------

    /** from/to 为 null 表示无界；inc 控制开闭。 */
    public List<Rid> rangeScanBytes(byte[] from, boolean fromInc, byte[] to, boolean toInc) {
        List<Rid> out = new ArrayList<>();
        int leafId;
        int idx;
        BTreeNode leaf;
        if (from == null) {
            leafId = leftmostLeaf();
            leaf = BTreeNode.pin(pool, leafId);
            idx = 0;
        } else {
            leafId = findLeaf(from);
            leaf = BTreeNode.pin(pool, leafId);
            idx = lowerBound(leaf, from, !fromInc);
        }
        try {
            while (true) {
                if (idx >= leaf.numKeys()) {
                    int nextId = leaf.next();
                    leaf.unpin(false);
                    leaf = null;
                    if (nextId == 0) return out;
                    leaf = BTreeNode.pin(pool, nextId);
                    idx = 0;
                    continue;
                }
                byte[] k = leaf.key(idx);
                if (to != null) {
                    int c = BTreeNode.compare(k, to);
                    if (c > 0 || (c == 0 && !toInc)) return out;
                }
                out.add(leaf.value(idx));
                idx++;
            }
        } finally {
            if (leaf != null) leaf.unpin(false);
        }
    }

    private int leftmostLeaf() {
        int cur = root;
        while (true) {
            BTreeNode n = BTreeNode.pin(pool, cur);
            try {
                if (n.isLeaf()) return cur;
                cur = n.child(0);
            } finally {
                n.unpin(false);
            }
        }
    }

    /** 全表（全索引）有序扫描。 */
    public List<Rid> scanAll() {
        return rangeScanBytes(null, true, null, true);
    }

    // ---------- 统计 ----------

    /** 释放整棵树的所有节点页（drop 索引用）。调用后本树不可再使用。 */
    public void drop() {
        dropRec(root);
    }

    private void dropRec(int pageId) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            if (!n.isLeaf()) {
                for (int i = 0; i <= n.numKeys(); i++) dropRec(n.child(i));
            }
        } finally {
            n.unpin(false);
        }
        engine.freePage(pageId);
    }

    public record BTreeStats(int height, int internalNodes, int leafNodes,
                             double avgLeafUtilization, int totalKeys) {}

    /** 树高、节点数与叶节点平均字节利用率（含目录/头开销，用于调试与测试）。 */
    public BTreeStats stats() {
        int[] internal = {0}, leaves = {0}, keys = {0}, used = {0};
        collectStats(root, internal, leaves, keys, used);
        double util = leaves[0] == 0 ? 0 : (double) used[0] / ((double) leaves[0] * Page.SIZE);
        return new BTreeStats(height, internal[0], leaves[0], util, keys[0]);
    }

    private void collectStats(int pageId, int[] internal, int[] leaves, int[] keys, int[] used) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            if (n.isLeaf()) {
                leaves[0]++;
                keys[0] += n.numKeys();
                used[0] += n.usedPageBytes();
            } else {
                internal[0]++;
                for (int i = 0; i <= n.numKeys(); i++)
                    collectStats(n.child(i), internal, leaves, keys, used);
            }
        } finally {
            n.unpin(false);
        }
    }

    /** 校验树结构不变量（测试用）：键有序、孩子数=键数+1、叶链双向一致、
     *  非根节点键数 ≥ 1 且尽量 ≥ LMIN（变长键下极端字节分布可暂时 underflow，自愈）。 */
    public void validate() {
        validateRec(root, null, null);
        // 叶链双向一致
        int leftmost = leftmostLeaf();
        int prev = 0;
        for (int cur = leftmost; cur != 0; ) {
            BTreeNode n = BTreeNode.pin(pool, cur);
            try {
                if (n.prev() != prev)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "叶链 prev 不一致: 页 " + cur + " prev=" + n.prev() + " 期望 " + prev);
                prev = cur;
                cur = n.next();
            } finally {
                n.unpin(false);
            }
        }
    }

    /** loInclusive/hiExclusive：本子树所有键必须满足 lo ≤ key < hi（push-right：分隔键属于右子树）。 */
    private void validateRec(int pageId, byte[] loInclusive, byte[] hiExclusive) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            int nk = n.numKeys();
            for (int i = 0; i < nk; i++) {
                byte[] k = n.key(i);
                if (i > 0 && BTreeNode.compare(n.key(i - 1), k) >= 0)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键无序: 页 " + pageId + " @" + i);
                if (loInclusive != null && BTreeNode.compare(k, loInclusive) < 0)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键低于下界: 页 " + pageId);
                if (hiExclusive != null && BTreeNode.compare(k, hiExclusive) >= 0)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键高于上界: 页 " + pageId);
            }
            if (pageId != root) {
                if (nk < 1)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "非根节点空: 页 " + pageId + " keys=" + nk);
            }
            if (!n.isLeaf()) {
                for (int i = 0; i <= nk; i++) {
                    byte[] lo;
                    byte[] hi;
                    if (i == 0) lo = loInclusive;
                    else lo = n.key(i - 1);
                    if (i == nk) hi = hiExclusive;
                    else hi = n.key(i);
                    validateRec(n.child(i), lo, hi);
                }
            }
        } finally {
            n.unpin(false);
        }
    }
}
