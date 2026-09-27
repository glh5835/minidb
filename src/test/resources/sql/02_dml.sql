-- @case insert-single
CREATE TABLE t (a INT, b VARCHAR(10));
INSERT INTO t VALUES (1, 'one');
SELECT * FROM t;
-- expect: 1 | one

-- @case insert-multi-rows
CREATE TABLE m (a INT);
INSERT INTO m VALUES (1), (2), (3);
SELECT COUNT(*) FROM m;
-- expect: 3

-- @case insert-column-reorder
CREATE TABLE r (a INT, b VARCHAR(5));
INSERT INTO r (b, a) VALUES ('x', 9);
SELECT * FROM r;
-- expect: 9 | x

-- @case insert-expression-values
CREATE TABLE e (a INT, b BIGINT, c DOUBLE);
INSERT INTO e VALUES (1 + 2, 100 * 2, 1.5 * 2);
SELECT * FROM e;
-- expect: 3 | 200 | 3.0

-- @case insert-negative-values
CREATE TABLE n (a INT, d DOUBLE);
INSERT INTO n VALUES (-5, -2.5);
SELECT * FROM n;
-- expect: -5 | -2.5

-- @case insert-unicode-string
CREATE TABLE u (s VARCHAR(30));
INSERT INTO u VALUES ('数据库内核');
SELECT * FROM u;
-- expect: 数据库内核

-- @case insert-empty-string
CREATE TABLE es (s VARCHAR(5));
INSERT INTO es VALUES ('');
SELECT * FROM es;
-- expect: 

-- @case insert-int-boundary
CREATE TABLE ib (a INT);
INSERT INTO ib VALUES (2147483647), (-2147483648);
SELECT COUNT(*) FROM ib WHERE a > 0;
-- expect: 1

-- @case insert-bigint-large
CREATE TABLE bl (v BIGINT);
INSERT INTO bl VALUES (9000000000000000000);
SELECT * FROM bl;
-- expect: 9000000000000000000

-- @case insert-int-overflow-error
CREATE TABLE io (a INT);
-- expect-error
INSERT INTO io VALUES (99999999999);

-- @case insert-varchar-overflow-error
CREATE TABLE vo (s VARCHAR(4));
-- expect-error
INSERT INTO vo VALUES ('toolong');

-- @case insert-type-mismatch-error
CREATE TABLE tm (a INT);
-- expect-error
INSERT INTO tm VALUES ('text');

-- @case insert-wrong-arity-error
CREATE TABLE wa (a INT, b INT);
-- expect-error
INSERT INTO wa VALUES (1);

-- @case insert-missing-table-error
-- expect-error
INSERT INTO ghost VALUES (1);

-- @case insert-fractional-into-int-error
CREATE TABLE fi (a INT);
-- expect-error
INSERT INTO fi VALUES (1.5);

-- @case update-single-row
CREATE TABLE us (id INT, v INT);
INSERT INTO us VALUES (1, 10), (2, 20);
UPDATE us SET v = 99 WHERE id = 1;
SELECT v FROM us WHERE id = 1;
-- expect: 99

-- @case update-with-expression
CREATE TABLE ue (id INT, v INT);
INSERT INTO ue VALUES (1, 10), (2, 20);
UPDATE ue SET v = v * 10 + 1 WHERE id = 2;
SELECT v FROM ue WHERE id = 2;
-- expect: 201

-- @case update-multiple-assignments
CREATE TABLE um (id INT, a INT, b VARCHAR(5));
INSERT INTO um VALUES (1, 1, 'x');
UPDATE um SET a = 2, b = 'y' WHERE id = 1;
SELECT * FROM um;
-- expect: 1 | 2 | y

-- @case update-all-rows
CREATE TABLE ua (id INT, v INT);
INSERT INTO ua VALUES (1, 1), (2, 2), (3, 3);
UPDATE ua SET v = 0;
SELECT COUNT(*) FROM ua WHERE v = 0;
-- expect: 3

-- @case update-no-match
CREATE TABLE un (id INT, v INT);
INSERT INTO un VALUES (1, 1);
UPDATE un SET v = 9 WHERE id = 99;
SELECT v FROM un;
-- expect: 1

-- @case update-unknown-column-error
CREATE TABLE uc (a INT);
INSERT INTO uc VALUES (1);
-- expect-error
UPDATE uc SET no_col = 1;

-- @case update-return-count
CREATE TABLE ur (a INT);
INSERT INTO ur VALUES (1), (2);
UPDATE ur SET a = 5;
SELECT COUNT(*) FROM ur WHERE a = 5;
-- expect: 2

-- @case delete-where
CREATE TABLE dw (id INT);
INSERT INTO dw VALUES (1), (2), (3);
DELETE FROM dw WHERE id = 2;
SELECT COUNT(*) FROM dw;
-- expect: 2

-- @case delete-all
CREATE TABLE da (id INT);
INSERT INTO da VALUES (1), (2);
DELETE FROM da;
SELECT COUNT(*) FROM da;
-- expect: 0

-- @case delete-no-match
CREATE TABLE dn (id INT);
INSERT INTO dn VALUES (1);
DELETE FROM dn WHERE id = 99;
SELECT COUNT(*) FROM dn;
-- expect: 1

-- @case delete-then-refill
CREATE TABLE dr (id INT);
INSERT INTO dr VALUES (1), (2), (3);
DELETE FROM dr WHERE id <= 2;
INSERT INTO dr VALUES (10);
SELECT * FROM dr;
-- expect: 10
-- expect: 3

-- @case delete-unknown-column-error
CREATE TABLE dc (a INT);
-- expect-error
DELETE FROM dc WHERE no_col = 1;

-- @case insert-update-delete-cycle
CREATE TABLE cyc (id INT, v VARCHAR(8));
INSERT INTO cyc VALUES (1, 'a'), (2, 'b'), (3, 'c');
UPDATE cyc SET v = 'b2' WHERE id = 2;
DELETE FROM cyc WHERE id = 1;
INSERT INTO cyc VALUES (4, 'd');
SELECT * FROM cyc;
-- expect: 4 | d
-- expect: 2 | b2
-- expect: 3 | c

-- @case insert-null-via-empty-subquery
CREATE TABLE ns (a INT);
INSERT INTO ns VALUES (1);
SELECT (SELECT MAX(a) FROM ns WHERE a > 99);
-- expect: NULL

-- @case bigint-arithmetic
CREATE TABLE ba (v BIGINT);
INSERT INTO ba VALUES (3000000000);
SELECT v * v / 1000000 FROM ba;
-- expect: 9000000000000

-- @case double-precision
CREATE TABLE dp (d DOUBLE);
INSERT INTO dp VALUES (0.1);
SELECT d * 3 FROM dp;
-- expect: 0.30000000000000004
