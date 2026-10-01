package minidb.storage;

/** 索引元数据：名字、所在列、B+ 树根页号、键类型（LONG=整型 / STRING=VARCHAR）。 */
public record IndexMeta(String name, String column, int rootPage, String keyType) {
    public static final String KEY_LONG = "LONG";
    public static final String KEY_STRING = "STRING";
}
