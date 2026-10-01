package minidb.storage;

import minidb.btree.BPlusTree;
import minidb.common.MiniDbException;
import minidb.common.Rid;

import java.nio.charset.StandardCharsets;

/**
 * 索引键统一编码门面：按索引键类型（LONG=整型 / STRING=VARCHAR）把列值转换为
 * B+ 树键并执行插入/删除/查找。NULL 列值不入索引（各操作为 no-op）。
 * Executor 的 DML 维护点与 Recovery 的索引回补共用，防止两处编码漂移。
 */
public final class IndexKeys {
    private IndexKeys() {}

    /** 列值 → 树键：LONG 返回 Long，STRING 返回 String（超长抛 BTREE）。 */
    public static Object encode(String keyType, Object v) {
        if (v == null) return null;
        if (IndexMeta.KEY_STRING.equals(keyType)) {
            String s = v.toString();
            if (s.getBytes(StandardCharsets.UTF_8).length > BPlusTree.MAX_KEY_BYTES)
                throw new MiniDbException(MiniDbException.Code.BTREE,
                        "索引键超长（上限 " + BPlusTree.MAX_KEY_BYTES + " 字节）: " + s);
            return s;
        }
        return ((Number) v).longValue();
    }

    private static RuntimeException badKey(Object k) {
        return new MiniDbException(MiniDbException.Code.BTREE, "索引键类型错误: " + k.getClass());
    }

    public static boolean insert(BPlusTree tree, String keyType, Object v, Rid rid) {
        Object k = encode(keyType, v);
        if (k == null) return true; // NULL 不入索引
        if (k instanceof String s) return tree.insert(s, rid);
        if (k instanceof Long l) return tree.insert(l, rid);
        throw badKey(k);
    }

    public static boolean delete(BPlusTree tree, String keyType, Object v) {
        Object k = encode(keyType, v);
        if (k == null) return true;
        if (k instanceof String s) return tree.delete(s);
        if (k instanceof Long l) return tree.delete(l);
        throw badKey(k);
    }

    public static Rid search(BPlusTree tree, String keyType, Object v) {
        Object k = encode(keyType, v);
        if (k == null) return null;
        if (k instanceof String s) return tree.search(s);
        if (k instanceof Long l) return tree.search(l);
        throw badKey(k);
    }

    /** 索引列值比较（判断 UPDATE 是否需要维护索引）：LONG 数值比，STRING equals。 */
    public static boolean sameValue(String keyType, Object a, Object b) {
        if (IndexMeta.KEY_STRING.equals(keyType)) return Objects_equals(a, b);
        if (a == null || b == null) return a == b;
        return ((Number) a).longValue() == ((Number) b).longValue();
    }

    private static boolean Objects_equals(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }
}
