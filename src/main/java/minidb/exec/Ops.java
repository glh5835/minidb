package minidb.exec;

import minidb.storage.Row;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/** 算子执行工具。 */
public final class Ops {
    private Ops() {}

    /** 拉取算子全部行（先 close 掉算子）。 */
    public static List<Row> collect(Op op) {
        List<Row> out = new ArrayList<>();
        op.open();
        try {
            Row r;
            while ((r = op.next()) != null) out.add(r);
        } finally {
            op.close();
        }
        return out;
    }

    public static int dump(Op op, PrintStream out) {
        int n = 0;
        op.open();
        try {
            Row r;
            while ((r = op.next()) != null) {
                out.println(formatRow(r));
                n++;
            }
        } finally {
            op.close();
        }
        return n;
    }

    public static String formatRow(Row r) {
        StringBuilder sb = new StringBuilder("(" + r.rid() + ") ");
        for (int i = 0; i < r.values().length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(r.values()[i]);
        }
        return sb.toString();
    }
}
