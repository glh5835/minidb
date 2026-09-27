package minidb.btree;

import minidb.common.MiniDbException;
import minidb.common.Rid;
import minidb.storage.BufferPool;
import minidb.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

/**
 * B+ 树索引：key 为 long，value 为行 RID。
 * 所有节点都是 4KB 页，通过 BufferPool 读写（绝不直接操作文件）。
 * 唯一索引：插入重复 key 返回 false。
 * 叶节点构成双向链表支持范围扫描。
 *
 * 策略：插入用“提前分裂”（节点写满即分裂，任何节点键数都不超过 MAX）；
 * 删除后低于 MIN 时先向兄弟借位、借不到则合并，父节点递归处理，根只剩一个孩子时降级。
 * 分隔键约定：child[i] 子树的所有 key < keys[i] ≤ child[i+1] 子树的最小 key（push-right）。
 */
public final class BPlusTree {
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

    // ---------- 查找 ----------

    /** 等值查找；未命中返回 null。 */
    public Rid search(long key) {
        int leafId = findLeaf(key);
        BTreeNode leaf = BTreeNode.pin(pool, leafId);
        try {
            int i = lowerBound(leaf, key);
            if (i < leaf.numKeys() && leaf.key(i) == key) return leaf.value(i);
            return null;
        } finally {
            leaf.unpin(false);
        }
    }

    /** 下行到 key 应所在的叶节点页。 */
    private int findLeaf(long key) {
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
    private static int childIndex(BTreeNode n, long key) {
        int lo = 0, hi = n.numKeys();
        while (lo < hi) { // upperBound：第一个 keys[i] > key 的下标
            int mid = (lo + hi) >>> 1;
            if (n.key(mid) > key) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /** 叶内 lowerBound：第一个 keys[i] >= key 的下标。 */
    private static int lowerBound(BTreeNode leaf, long key) {
        int lo = 0, hi = leaf.numKeys();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (leaf.key(mid) >= key) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    // ---------- 插入 ----------

    /** 插入；key 已存在返回 false（唯一索引）。 */
    public boolean insert(long key, Rid value) {
        boolean[] inserted = {false};
        long[] upKey = {0};
        int[] newRight = {0};
        insertRec(root, key, value, inserted, upKey, newRight);
        if (newRight[0] != 0) {
            // 根分裂：新建内部根
            int newRoot = allocNode(false);
            BTreeNode rn = BTreeNode.pin(pool, newRoot);
            try {
                rn.numKeys(1);
                rn.key(0, upKey[0]);
                rn.child(0, root);
                rn.child(1, newRight[0]);
                rn.dirty();
            } finally {
                rn.unpin(true);
            }
            root = newRoot;
            height++;
        }
        return inserted[0];
    }

    /**
     * 在 node 子树插入。若发生分裂，upKey[0]=上推键、newRight[0]=新右节点页号（否则为 0）。
     * 调用约定：本方法内部持有 node 的 pin 直至返回。
     */
    private void insertRec(int nodeId, long key, Rid value,
                           boolean[] inserted, long[] upKey, int[] newRight) {
        BTreeNode n = BTreeNode.pin(pool, nodeId);
        try {
            if (n.isLeaf()) {
                int i = lowerBound(n, key);
                if (i < n.numKeys() && n.key(i) == key) {
                    inserted[0] = false;
                    return;
                }
                insertAt(n, i, key, value);
                inserted[0] = true;
                if (n.numKeys() >= BTreeNode.LMAX) splitLeaf(n, upKey, newRight);
            } else {
                int ci = childIndex(n, key);
                int childId = n.child(ci);
                long[] cUp = {0};
                int[] cRight = {0};
                insertRec(childId, key, value, inserted, cUp, cRight);
                if (cRight[0] != 0) {
                    // 上推键落在 child(ci) 的键域内，插入位置恰为 ci
                    int nk = n.numKeys();
                    for (int j = nk; j > ci; j--) n.key(j, n.key(j - 1));
                    for (int j = nk + 1; j > ci + 1; j--) n.child(j, n.child(j - 1));
                    n.key(ci, cUp[0]);
                    n.child(ci + 1, cRight[0]);
                    n.numKeys(nk + 1);
                    n.dirty();
                    if (n.numKeys() >= BTreeNode.IMAX) splitInternal(n, upKey, newRight);
                }
            }
        } finally {
            n.unpin(true);
        }
    }

    private void insertAt(BTreeNode n, int i, long key, Rid value) {
        int nk = n.numKeys();
        for (int j = nk; j > i; j--) {
            n.key(j, n.key(j - 1));
            n.value(j, n.value(j - 1));
        }
        n.key(i, key);
        n.value(i, value);
        n.numKeys(nk + 1);
        n.dirty();
    }

    /** 叶分裂（push-right：右节点保留其最小键并作为上推键），维护叶链。 */
    private void splitLeaf(BTreeNode n, long[] upKey, int[] newRight) {
        int nk = n.numKeys();
        int leftN = (nk + 1) / 2;
        int rightN = nk - leftN;
        int rightId = allocNode(true);
        BTreeNode r = BTreeNode.pin(pool, rightId);
        try {
            for (int i = 0; i < rightN; i++) {
                r.key(i, n.key(leftN + i));
                r.value(i, n.value(leftN + i));
            }
            r.numKeys(rightN);
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
        n.numKeys(leftN);
        n.next(rightId); // 关键：左节点链向新右节点
        n.dirty();
        upKey[0] = readKey(rightId, 0);
        newRight[0] = rightId;
    }

    private long readKey(int pageId, int i) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            return n.key(i);
        } finally {
            n.unpin(false);
        }
    }

    /** 内部节点分裂（中间键上推，不保留在任何子节点）。 */
    private void splitInternal(BTreeNode n, long[] upKey, int[] newRight) {
        int nk = n.numKeys();
        int mid = nk / 2;
        int rightN = nk - mid - 1;
        long midKey = n.key(mid); // 必须在收缩 numKeys 之前读出
        int rightId = allocNode(false);
        BTreeNode r = BTreeNode.pin(pool, rightId);
        try {
            for (int i = 0; i < rightN; i++) r.key(i, n.key(mid + 1 + i));
            for (int i = 0; i <= rightN; i++) r.child(i, n.child(mid + 1 + i));
            r.numKeys(rightN);
            r.dirty();
        } finally {
            r.unpin(true);
        }
        n.numKeys(mid);
        n.dirty();
        upKey[0] = midKey;
        newRight[0] = rightId;
    }

    // ---------- 删除 ----------

    /** 删除；key 不存在返回 false。 */
    public boolean delete(long key) {
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
    private void deleteRec(int nodeId, long key, boolean[] removed) {
        BTreeNode n = BTreeNode.pin(pool, nodeId);
        try {
            if (n.isLeaf()) {
                int i = lowerBound(n, key);
                if (i < n.numKeys() && n.key(i) == key) {
                    removeLeafAt(n, i);
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
                int cmin = c.minKeys();
                c.unpin(false);
                if (ck < cmin) fixUnderflow(n, ci);
            }
        } finally {
            n.unpin(true);
        }
    }

    private void removeLeafAt(BTreeNode n, int i) {
        int nk = n.numKeys();
        for (int j = i; j < nk - 1; j++) {
            n.key(j, n.key(j + 1));
            n.value(j, n.value(j + 1));
        }
        n.numKeys(nk - 1);
        n.dirty();
    }

    /** 修复 parent.child(ci) 的 underflow（parent 处于 pin 状态）。 */
    private void fixUnderflow(BTreeNode parent, int ci) {
        int childId = parent.child(ci);
        BTreeNode child = BTreeNode.pin(pool, childId);
        int[] toFree = {0}; // 合并产生的待回收页（须等 child unpin 后再 free）
        try {
            // 1) 向左兄借
            if (ci > 0) {
                int leftId = parent.child(ci - 1);
                BTreeNode left = BTreeNode.pin(pool, leftId);
                try {
                    if (left.numKeys() > left.minKeys()) {
                        borrowFromLeft(parent, ci, left, child);
                        return;
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
                        borrowFromRight(parent, ci, child, right);
                        return;
                    }
                } finally {
                    right.unpin(true);
                }
            }
            // 3) 合并（优先与左兄）
            toFree[0] = ci > 0 ? mergeChildren(parent, ci - 1) : mergeChildren(parent, ci);
        } finally {
            child.unpin(true);
        }
        if (toFree[0] != 0) engine.freePage(toFree[0]);
    }

    private void borrowFromLeft(BTreeNode parent, int ci, BTreeNode left, BTreeNode child) {
        int lk = left.numKeys();
        if (child.isLeaf()) {
            long k = left.key(lk - 1);
            Rid v = left.value(lk - 1);
            left.numKeys(lk - 1);
            left.dirty();
            shiftLeafRight(child);
            child.key(0, k);
            child.value(0, v);
            child.numKeys(child.numKeys() + 1);
            child.dirty();
            parent.key(ci - 1, k); // 分隔键 = 移过去的键
        } else {
            long downKey = parent.key(ci - 1);
            int movedChild = left.child(lk);
            long upKey = left.key(lk - 1);
            left.numKeys(lk - 1);
            left.dirty();
            shiftInternalRight(child);
            child.key(0, downKey);
            child.child(0, movedChild);
            child.numKeys(child.numKeys() + 1);
            child.dirty();
            parent.key(ci - 1, upKey);
        }
        parent.dirty();
    }

    private void borrowFromRight(BTreeNode parent, int ci, BTreeNode child, BTreeNode right) {
        int rk = right.numKeys();
        if (child.isLeaf()) {
            long k = right.key(0);
            Rid v = right.value(0);
            shiftLeafLeft(right, rk);
            right.numKeys(rk - 1);
            right.dirty();
            int ck = child.numKeys();
            child.key(ck, k);
            child.value(ck, v);
            child.numKeys(ck + 1);
            child.dirty();
            parent.key(ci, right.key(0)); // 分隔键 = 右兄新的最小键
        } else {
            long downKey = parent.key(ci);
            int movedChild = right.child(0);
            long upKey = right.key(0);
            shiftInternalLeft(right, rk);
            right.numKeys(rk - 1);
            right.dirty();
            int ck = child.numKeys();
            child.key(ck, downKey);
            child.child(ck + 1, movedChild);
            child.numKeys(ck + 1);
            child.dirty();
            parent.key(ci, upKey);
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
                for (int j = 0; j < rk; j++) {
                    left.key(lk + j, right.key(j));
                    left.value(lk + j, right.value(j));
                }
                left.numKeys(lk + rk);
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
                left.key(lk, parent.key(sepIdx)); // 分隔键下坠
                for (int j = 0; j < rk; j++) left.key(lk + 1 + j, right.key(j));
                for (int j = 0; j <= rk; j++) left.child(lk + 1 + j, right.child(j));
                left.numKeys(lk + 1 + rk);
            }
            left.dirty();
            int pk = parent.numKeys();
            for (int j = sepIdx; j < pk - 1; j++) parent.key(j, parent.key(j + 1));
            for (int j = sepIdx + 1; j < pk; j++) parent.child(j, parent.child(j + 1));
            parent.numKeys(pk - 1);
            parent.dirty();
        } finally {
            left.unpin(true);
            right.unpin(true);
        }
        return rightId;
    }

    private void shiftLeafRight(BTreeNode n) {
        int nk = n.numKeys();
        for (int j = nk; j > 0; j--) {
            n.key(j, n.key(j - 1));
            n.value(j, n.value(j - 1));
        }
    }

    private void shiftInternalRight(BTreeNode n) {
        int nk = n.numKeys();
        for (int j = nk; j > 0; j--) n.key(j, n.key(j - 1));
        for (int j = nk + 1; j > 0; j--) n.child(j, n.child(j - 1));
    }

    private void shiftLeafLeft(BTreeNode n, int nk) {
        for (int j = 0; j < nk - 1; j++) {
            n.key(j, n.key(j + 1));
            n.value(j, n.value(j + 1));
        }
    }

    private void shiftInternalLeft(BTreeNode n, int nk) {
        for (int j = 0; j < nk - 1; j++) n.key(j, n.key(j + 1));
        for (int j = 0; j < nk; j++) n.child(j, n.child(j + 1));
    }

    // ---------- 范围扫描 ----------

    /** 闭区间范围扫描。 */
    public List<Rid> rangeScan(long from, long to) {
        return rangeScan(from, true, to, true);
    }

    public List<Rid> rangeScan(long from, boolean fromInc, long to, boolean toInc) {
        List<Rid> out = new ArrayList<>();
        if (!fromInc) {
            if (from == Long.MAX_VALUE) return out;
            from++;
        }
        if (!toInc) {
            if (to == Long.MIN_VALUE) return out;
            to--;
        }
        if (from > to) return out;
        int leafId = findLeaf(from);
        BTreeNode leaf = BTreeNode.pin(pool, leafId);
        int idx = lowerBound(leaf, from);
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
                long k = leaf.key(idx);
                if (k > to) return out;
                out.add(leaf.value(idx));
                idx++;
            }
        } finally {
            if (leaf != null) leaf.unpin(false);
        }
    }

    /** 全表（全索引）有序扫描。 */
    public List<Rid> scanAll() {
        // 走到最左叶
        int cur = root;
        while (true) {
            BTreeNode n = BTreeNode.pin(pool, cur);
            try {
                if (n.isLeaf()) break;
                cur = n.child(0);
            } finally {
                n.unpin(false);
            }
        }
        List<Rid> out = new ArrayList<>();
        int leafId = cur;
        BTreeNode leaf = BTreeNode.pin(pool, leafId);
        int idx = 0;
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
                out.add(leaf.value(idx));
                idx++;
            }
        } finally {
            if (leaf != null) leaf.unpin(false);
        }
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

    /** 树高、节点数与叶节点平均利用率（用于调试与测试）。 */
    public BTreeStats stats() {
        int[] internal = {0}, leaves = {0}, keys = {0};
        collectStats(root, internal, leaves, keys);
        double util = leaves[0] == 0 ? 0 : (double) keys[0] / ((double) leaves[0] * BTreeNode.LMAX);
        return new BTreeStats(height, internal[0], leaves[0], util, keys[0]);
    }

    private void collectStats(int pageId, int[] internal, int[] leaves, int[] keys) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            if (n.isLeaf()) {
                leaves[0]++;
                keys[0] += n.numKeys();
            } else {
                internal[0]++;
                for (int i = 0; i <= n.numKeys(); i++) collectStats(n.child(i), internal, leaves, keys);
            }
        } finally {
            n.unpin(false);
        }
    }

    /** 校验树结构不变量（测试用）：键有序、孩子数=键数+1、叶链双向一致、除根外键数在 [min,max]。 */
    public void validate() {
        validateRec(root, null, null);
        // 叶链双向一致
        int leftmost = root;
        while (true) {
            BTreeNode n = BTreeNode.pin(pool, leftmost);
            try {
                if (n.isLeaf()) break;
                leftmost = n.child(0);
            } finally {
                n.unpin(false);
            }
        }
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
    private void validateRec(int pageId, Long loInclusive, Long hiExclusive) {
        BTreeNode n = BTreeNode.pin(pool, pageId);
        try {
            int nk = n.numKeys();
            for (int i = 0; i < nk; i++) {
                long k = n.key(i);
                if (i > 0 && n.key(i - 1) >= k)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键无序: 页 " + pageId + " @" + i);
                if (loInclusive != null && k < loInclusive)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键低于下界: 页 " + pageId + " key=" + k);
                if (hiExclusive != null && k >= hiExclusive)
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "键高于上界: 页 " + pageId + " key=" + k);
            }
            if (pageId != root) {
                if (nk < n.minKeys() || nk > n.maxKeys())
                    throw new MiniDbException(MiniDbException.Code.BTREE,
                            "节点键数越界 [" + n.minKeys() + "," + n.maxKeys() + "]: " + nk);
            } else if (nk > n.maxKeys()) {
                throw new MiniDbException(MiniDbException.Code.BTREE, "根键数越界: " + nk);
            }
            if (!n.isLeaf()) {
                for (int i = 0; i <= nk; i++) {
                    Long lo;
                    Long hi;
                    if (i == 0) lo = loInclusive; else lo = n.key(i - 1);
                    if (i == nk) hi = hiExclusive; else hi = n.key(i);
                    validateRec(n.child(i), lo, hi);
                }
            }
        } finally {
            n.unpin(false);
        }
    }
}
