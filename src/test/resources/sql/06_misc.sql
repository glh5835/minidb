-- @case misc-select-constant
SELECT 42;
-- expect: 42

-- @case misc-select-string
SELECT 'hello world';
-- expect: hello world

-- @case misc-select-arithmetic-precedence
SELECT 2 + 3 * 4;
-- expect: 14

-- @case misc-select-parens
SELECT (2 + 3) * 4;
-- expect: 20

-- @case misc-select-negative-literal
SELECT -5, -1.5;
-- expect: -5 | -1.5

-- @case misc-keyword-case-insensitive
create table MC (a int);
insert into MC values (1);
SeLeCt a FrOm mC;
-- expect: 1

-- @case misc-comment-lines
-- 这是注释
SELECT 7; -- 行尾注释
-- expect: 7

-- @case misc-unicode-roundtrip
CREATE TABLE uni (s VARCHAR(50));
INSERT INTO uni VALUES ('中文与English混合123');
SELECT * FROM uni;
-- expect: 中文与English混合123

-- @case misc-boolean-true-in-where
CREATE TABLE bt (a INT);
INSERT INTO bt VALUES (1), (2);
SELECT COUNT(*) FROM bt WHERE 1 = 1;
-- expect: 2

-- @case misc-boolean-false-in-where
SELECT COUNT(*) FROM bt WHERE 1 = 2;
-- expect: 0

-- @case misc-and-short-circuit-rows
SELECT COUNT(*) FROM bt WHERE a = 1 AND a = 2;
-- expect: 0

-- @case misc-or-all-rows
SELECT COUNT(*) FROM bt WHERE a = 1 OR a = 2 OR a = 3;
-- expect: 2

-- @case misc-mod-operator
SELECT 10 % 3;
-- expect: 1

-- @case misc-nested-parens
SELECT ((1 + 2) * (3 + 4));
-- expect: 21

-- @case misc-double-sum-integer-display
CREATE TABLE dsi (d DOUBLE);
INSERT INTO dsi VALUES (1.5), (2.5);
SELECT SUM(d) FROM dsi;
-- expect: 4.0

-- @case misc-int-sum-display
CREATE TABLE isi (a INT);
INSERT INTO isi VALUES (1), (2), (3);
SELECT SUM(a) FROM isi;
-- expect: 6

-- @case misc-avg-int
SELECT AVG(a) FROM isi;
-- expect: 2.0

-- @case misc-order-by-string
CREATE TABLE obs (s VARCHAR(10));
INSERT INTO obs VALUES ('banana'), ('apple'), ('cherry');
SELECT s FROM obs ORDER BY s;
-- expect: apple
-- expect: banana
-- expect: cherry

-- @case misc-order-by-string-desc
SELECT s FROM obs ORDER BY s DESC;
-- expect: cherry
-- expect: banana
-- expect: apple

-- @case misc-in-with-negative
SELECT COUNT(*) FROM isi WHERE a IN (-1, 1, 3);
-- expect: 2

-- @case misc-between-negative
SELECT COUNT(*) FROM isi WHERE a BETWEEN -5 AND 2;
-- expect: 2

-- @case misc-like-escape-percent-in-middle
CREATE TABLE lke (s VARCHAR(20));
INSERT INTO lke VALUES ('100%'), ('abc'), ('50%off');
SELECT COUNT(*) FROM lke WHERE s LIKE '1%';
-- expect: 1

-- @case misc-like-underscore-exact
SELECT COUNT(*) FROM lke WHERE s LIKE '_00%';
-- expect: 1

-- @case misc-distinct-with-expression
SELECT DISTINCT a % 2 AS m FROM isi ORDER BY m;
-- expect: 0
-- expect: 1

-- @case misc-limit-greater-than-rows
SELECT COUNT(*) FROM bt LIMIT 100;
-- expect: 2

-- @case misc-offset-past-end
SELECT a FROM isi ORDER BY a LIMIT 5 OFFSET 10;

-- @case misc-update-with-subquery
CREATE TABLE uws (id INT, v INT);
INSERT INTO uws VALUES (1, 10), (2, 20);
UPDATE uws SET v = (SELECT MAX(v) FROM uws) WHERE id = 1;
SELECT v FROM uws WHERE id = 1;
-- expect: 20

-- @case misc-delete-with-subquery
DELETE FROM uws WHERE v = (SELECT MIN(v) FROM uws);
SELECT COUNT(*) FROM uws;
-- expect: 0

-- @case misc-empty-select-from-empty
CREATE TABLE esq (a INT);
SELECT * FROM esq;

-- @case misc-big-int-expression
SELECT 3000000000 + 1;
-- expect: 3000000001

-- @case misc-group-by-zero-groups-having
SELECT a, COUNT(*) FROM esq GROUP BY a HAVING COUNT(*) > 0;

-- @case misc-multiple-statements-in-one-line
CREATE TABLE ms (a INT); INSERT INTO ms VALUES (5);
SELECT * FROM ms;
-- expect: 5

-- @case misc-varchar-max-3000
CREATE TABLE mv (s VARCHAR(3000));
INSERT INTO mv VALUES ('x');
SELECT COUNT(*) FROM mv;
-- expect: 1

-- @case misc-neg-column-compare
CREATE TABLE ncc (a INT, b INT);
INSERT INTO ncc VALUES (3, 5), (5, 3);
SELECT a FROM ncc WHERE a < b;
-- expect: 3

-- @case misc-cross-product-filtered
CREATE TABLE cp1 (x INT);
CREATE TABLE cp2 (y INT);
INSERT INTO cp1 VALUES (1), (2);
INSERT INTO cp2 VALUES (10), (20);
SELECT x, y FROM cp1, cp2 WHERE y / 10 = x ORDER BY x, y;
-- expect: 1 | 10
-- expect: 2 | 20

-- @case misc-left-join-null-check
CREATE TABLE lj1 (id INT);
CREATE TABLE lj2 (id INT, v VARCHAR(5));
INSERT INTO lj1 VALUES (1), (2), (3);
INSERT INTO lj2 VALUES (2, 'found');
SELECT lj1.id FROM lj1 LEFT JOIN lj2 ON lj1.id = lj2.id WHERE lj2.v IS NULL ORDER BY lj1.id;
-- expect: 1
-- expect: 3

-- @case misc-aggregate-group-empty-input
CREATE TABLE age (g INT, v INT);
SELECT g, COUNT(*) FROM age GROUP BY g;

-- @case misc-having-without-matching
INSERT INTO age VALUES (1, 5);
SELECT g FROM age GROUP BY g HAVING SUM(v) > 100;

-- @case misc-deep-expression
SELECT 1 + 2 * 3 - 4 / 2 + (5 % 3);
-- expect: 7

-- @case misc-double-negative
SELECT -(-5);
-- expect: 5

-- @case misc-not-comparison-chain
CREATE TABLE ncc2 (a INT);
INSERT INTO ncc2 VALUES (1), (2), (3);
SELECT COUNT(*) FROM ncc2 WHERE NOT a = 1 AND NOT a = 2;
-- expect: 1

-- @case misc-in-large-list
SELECT COUNT(*) FROM ncc2 WHERE a IN (1, 2, 3, 4, 5, 6, 7, 8);
-- expect: 3

-- @case misc-order-stability-limit
SELECT a FROM ncc2 ORDER BY a LIMIT 2 OFFSET 1;
-- expect: 2
-- expect: 3

-- @case misc-string-compare-lexicographic
SELECT COUNT(*) FROM lke WHERE s < 'b';
-- expect: 3

-- @case misc-update-does-not-touch-other-rows
CREATE TABLE udn (id INT, v INT);
INSERT INTO udn VALUES (1, 1), (2, 2), (3, 3);
UPDATE udn SET v = 99 WHERE id = 2;
SELECT COUNT(*) FROM udn WHERE v = 99;
-- expect: 1

-- @case misc-delete-with-like
CREATE TABLE dwl (s VARCHAR(10));
INSERT INTO dwl VALUES ('keep1'), ('drop-me'), ('keep2');
DELETE FROM dwl WHERE s LIKE 'drop%';
SELECT s FROM dwl ORDER BY s;
-- expect: keep1
-- expect: keep2

-- @case misc-select-star-qualified
SELECT ncc2.a FROM ncc2 WHERE a = 3;
-- expect: 3

-- @case misc-sum-of-empty-group-by-global
CREATE TABLE seg (a INT);
INSERT INTO seg VALUES (1);
DELETE FROM seg;
SELECT COUNT(*), SUM(a) FROM seg;
-- expect: 0 | NULL
