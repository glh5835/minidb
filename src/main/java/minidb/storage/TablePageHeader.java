package minidb.storage;

/**
 * 数据页 32 字节头部布局（定长表与变长表共用）：
 * <pre>
 *  [0]      type byte
 *  [1..3)   numSlots  short  定长表=已初始化槽数；变长表=槽位数组长度
 *  [3..5)   freeLow   short  变长表：槽位数组末尾（向上增长边界）
 *  [5..7)   dataStart short  变长表：记录数据起点（向下增长边界）
 *  [7..11)  totalFree int    变长表：可用字节数（含删除产生的空洞）
 *  [11..15) nextPage  int    同表页链表后继
 *  [15..19) prevPage  int    同表页链表前驱
 *  [19..21) firstFree short  变长表：第一个空闲槽下标，-1 表示无；定长表：空闲槽扫描提示
 *  [21..24) 保留
 *  [24..32) pageLsn  long   阶段5 WAL 用
 * </pre>
 */
public final class TablePageHeader {
    public static final int HDR_SIZE = 32;
    public static final int OFF_TYPE = 0;
    public static final int OFF_NUM_SLOTS = 1;
    public static final int OFF_FREE_LOW = 3;
    public static final int OFF_DATA_START = 5;
    public static final int OFF_TOTAL_FREE = 7;
    public static final int OFF_NEXT = 11;
    public static final int OFF_PREV = 15;
    public static final int OFF_FIRST_FREE = 19;
    public static final int OFF_PAGE_LSN = 24;

    private TablePageHeader() {}

    public static void init(byte[] d, Page.Type type) {
        java.util.Arrays.fill(d, 0, HDR_SIZE, (byte) 0);
        d[OFF_TYPE] = type.id;
        minidb.common.Bytes.putShort(d, OFF_FIRST_FREE, (short) -1);
    }

    public static short numSlots(byte[] d) { return minidb.common.Bytes.getShort(d, OFF_NUM_SLOTS); }
    public static void numSlots(byte[] d, short v) { minidb.common.Bytes.putShort(d, OFF_NUM_SLOTS, v); }
    public static short freeLow(byte[] d) { return minidb.common.Bytes.getShort(d, OFF_FREE_LOW); }
    public static void freeLow(byte[] d, short v) { minidb.common.Bytes.putShort(d, OFF_FREE_LOW, v); }
    public static short dataStart(byte[] d) { return minidb.common.Bytes.getShort(d, OFF_DATA_START); }
    public static void dataStart(byte[] d, short v) { minidb.common.Bytes.putShort(d, OFF_DATA_START, v); }
    public static int totalFree(byte[] d) { return minidb.common.Bytes.getInt(d, OFF_TOTAL_FREE); }
    public static void totalFree(byte[] d, int v) { minidb.common.Bytes.putInt(d, OFF_TOTAL_FREE, v); }
    public static int nextPage(byte[] d) { return minidb.common.Bytes.getInt(d, OFF_NEXT); }
    public static void nextPage(byte[] d, int v) { minidb.common.Bytes.putInt(d, OFF_NEXT, v); }
    public static int prevPage(byte[] d) { return minidb.common.Bytes.getInt(d, OFF_PREV); }
    public static void prevPage(byte[] d, int v) { minidb.common.Bytes.putInt(d, OFF_PREV, v); }
    public static short firstFree(byte[] d) { return minidb.common.Bytes.getShort(d, OFF_FIRST_FREE); }
    public static void firstFree(byte[] d, short v) { minidb.common.Bytes.putShort(d, OFF_FIRST_FREE, v); }
}
