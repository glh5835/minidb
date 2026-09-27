-- @case create-table-basic-types
CREATE TABLE t (id INT, ts BIGINT, score DOUBLE, name VARCHAR(20));
INSERT INTO t VALUES (1, 100, 1.5, 'a');
SELECT * FROM t;
-- expect: 1 | 100 | 1.5 | a

-- @case create-table-varchar-default-size
CREATE TABLE v (s VARCHAR);
INSERT INTO v VALUES ('default-255-ok');
SELECT * FROM v;
-- expect: default-255-ok

-- @case create-table-primary-key-annotation
CREATE TABLE pk (id INT PRIMARY KEY, name VARCHAR(10));
INSERT INTO pk VALUES (1, 'x');
SELECT * FROM pk;
-- expect: 1 | x

-- @case create-duplicate-table-error
CREATE TABLE dup (a INT);
-- expect-error
CREATE TABLE dup (b INT);

-- @case create-empty-select
CREATE TABLE empty (a INT, b VARCHAR(5));
SELECT COUNT(*) FROM empty;
-- expect: 0

-- @case drop-table
CREATE TABLE gone (a INT);
INSERT INTO gone VALUES (1);
DROP TABLE gone;
-- expect-error
SELECT COUNT(*) FROM gone;
SELECT 1;
-- expect: 1

-- @case drop-missing-table-error
-- expect-error
DROP TABLE never_existed;

-- @case recreate-after-drop
CREATE TABLE reuse (a INT);
INSERT INTO reuse VALUES (1);
DROP TABLE reuse;
CREATE TABLE reuse (b VARCHAR(10));
INSERT INTO reuse VALUES ('new');
SELECT * FROM reuse;
-- expect: new

-- @case create-index
CREATE TABLE ix (id INT, name VARCHAR(10));
INSERT INTO ix VALUES (1,'a'),(2,'b'),(3,'c');
CREATE INDEX ix_id ON ix(id);
SELECT name FROM ix WHERE id = 2;
-- expect: b

-- @case create-duplicate-index-error
CREATE TABLE dxi (a INT);
CREATE INDEX d1 ON dxi(a);
-- expect-error
CREATE INDEX d1 ON dxi(a);

-- @case drop-index
CREATE TABLE dxi2 (a INT);
CREATE INDEX d2 ON dxi2(a);
DROP INDEX d2;
INSERT INTO dxi2 VALUES (5);
SELECT COUNT(*) FROM dxi2 WHERE a = 5;
-- expect: 1

-- @case drop-missing-index-error
-- expect-error
DROP INDEX no_such_index;

-- @case index-on-missing-table-error
-- expect-error
CREATE INDEX bad1 ON no_table(a);

-- @case index-on-missing-column-error
CREATE TABLE cm (a INT);
-- expect-error
CREATE INDEX bad2 ON cm(no_col);

-- @case index-on-varchar-error
CREATE TABLE cv (s VARCHAR(5));
-- expect-error
CREATE INDEX bad3 ON cv(s);

-- @case index-on-double-error
CREATE TABLE cd (d DOUBLE);
-- expect-error
CREATE INDEX bad4 ON cd(d);

-- @case unique-index-rejects-duplicates
CREATE TABLE uq (a INT);
INSERT INTO uq VALUES (1), (2), (1);
-- expect-error
CREATE INDEX uq_a ON uq(a);

-- @case unique-index-ok-on-distinct
CREATE TABLE uq2 (a INT);
INSERT INTO uq2 VALUES (1), (2), (3);
CREATE INDEX uq2_a ON uq2(a);
SELECT COUNT(*) FROM uq2 WHERE a >= 2;
-- expect: 2

-- @case long-identifiers
CREATE TABLE a_very_long_table_name_for_testing_purpose (a_column_name_that_is_long INT);
INSERT INTO a_very_long_table_name_for_testing_purpose VALUES (7);
SELECT a_column_name_that_is_long FROM a_very_long_table_name_for_testing_purpose;
-- expect: 7

-- @case ddl-count-after-empty
CREATE TABLE cnt (a INT);
SELECT COUNT(*) FROM cnt;
-- expect: 0
