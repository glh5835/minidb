-- @case join-setup
CREATE TABLE emp (id INT, name VARCHAR(10), dept INT);
CREATE TABLE dept (id INT, dname VARCHAR(10));
INSERT INTO emp VALUES (1,'alice',10),(2,'bob',20),(3,'carol',10),(4,'dave',30);
INSERT INTO dept VALUES (10,'eng'),(20,'sales'),(40,'hr');
SELECT COUNT(*) FROM emp;
-- expect: 4

-- @case inner-join-basic
SELECT emp.name, dept.dname FROM emp INNER JOIN dept ON emp.dept = dept.id ORDER BY emp.id;
-- expect: alice | eng
-- expect: bob | sales
-- expect: carol | eng

-- @case inner-join-keyword-optional
SELECT emp.name, dept.dname FROM emp JOIN dept ON emp.dept = dept.id ORDER BY emp.id;
-- expect: alice | eng
-- expect: bob | sales
-- expect: carol | eng

-- @case left-join
SELECT emp.name, dept.dname FROM emp LEFT JOIN dept ON emp.dept = dept.id;
-- expect: alice | eng
-- expect: bob | sales
-- expect: carol | eng
-- expect: dave | NULL

-- @case left-outer-join
SELECT emp.name FROM emp LEFT OUTER JOIN dept ON emp.dept = dept.id WHERE dept.dname IS NULL;
-- expect: dave

-- @case comma-cross-join
SELECT COUNT(*) FROM emp, dept;
-- expect: 12

-- @case comma-join-with-where
SELECT emp.name FROM emp, dept WHERE emp.dept = dept.id AND dept.dname = 'eng';
-- expect: alice
-- expect: carol

-- @case three-way-join
CREATE TABLE loc (dname VARCHAR(10), city VARCHAR(10));
INSERT INTO loc VALUES ('eng','beijing'),('sales','shanghai');
SELECT emp.name, loc.city FROM emp JOIN dept ON emp.dept = dept.id JOIN loc ON dept.dname = loc.dname ORDER BY emp.id;
-- expect: alice | beijing
-- expect: bob | shanghai
-- expect: carol | beijing

-- @case join-with-extra-where
SELECT emp.name FROM emp JOIN dept ON emp.dept = dept.id WHERE emp.id > 2;
-- expect: carol

-- @case join-table-alias
SELECT e.name, d.dname FROM emp e JOIN dept d ON e.dept = d.id WHERE d.dname = 'sales';
-- expect: bob | sales

-- @case self-join
SELECT a.name, b.name FROM emp a JOIN emp b ON a.dept = b.dept AND a.id < b.id;
-- expect: alice | carol

-- @case order-by-asc
SELECT name FROM emp ORDER BY id;
-- expect: alice
-- expect: bob
-- expect: carol
-- expect: dave

-- @case order-by-desc
SELECT name FROM emp ORDER BY id DESC;
-- expect: dave
-- expect: carol
-- expect: bob
-- expect: alice

-- @case order-by-multi-key
SELECT dept, name FROM emp ORDER BY dept DESC, id ASC;
-- expect: 30 | dave
-- expect: 20 | bob
-- expect: 10 | alice
-- expect: 10 | carol

-- @case order-by-expression
SELECT name FROM emp ORDER BY -id;
-- expect: dave
-- expect: carol
-- expect: bob
-- expect: alice

-- @case limit-basic
SELECT id FROM emp ORDER BY id LIMIT 2;
-- expect: 1
-- expect: 2

-- @case limit-offset
SELECT id FROM emp ORDER BY id LIMIT 2 OFFSET 1;
-- expect: 2
-- expect: 3

-- @case limit-zero
SELECT COUNT(*) FROM emp LIMIT 0;

-- @case distinct-single
SELECT DISTINCT dept FROM emp ORDER BY dept;
-- expect: 10
-- expect: 20
-- expect: 30

-- @case distinct-multi
SELECT DISTINCT dept, 1 FROM emp ORDER BY dept;
-- expect: 10 | 1
-- expect: 20 | 1
-- expect: 30 | 1

-- @case group-count
SELECT dept, COUNT(*) FROM emp GROUP BY dept ORDER BY dept;
-- expect: 10 | 2
-- expect: 20 | 1
-- expect: 30 | 1

-- @case group-sum
CREATE TABLE g (dept INT, v INT);
INSERT INTO g VALUES (1, 10),(1, 20),(2, 5),(2, 15),(3, 7);
SELECT dept, SUM(v) FROM g GROUP BY dept ORDER BY dept;
-- expect: 1 | 30
-- expect: 2 | 20
-- expect: 3 | 7

-- @case group-avg
SELECT dept, AVG(v) FROM g GROUP BY dept ORDER BY dept;
-- expect: 1 | 15.0
-- expect: 2 | 10.0
-- expect: 3 | 7.0

-- @case group-min-max
SELECT dept, MIN(v), MAX(v) FROM g GROUP BY dept ORDER BY dept;
-- expect: 1 | 10 | 20
-- expect: 2 | 5 | 15
-- expect: 3 | 7 | 7

-- @case group-multi-key
SELECT dept, v % 2, COUNT(*) FROM g GROUP BY dept, v % 2 ORDER BY dept, v % 2;
-- expect: 1 | 0 | 2
-- expect: 2 | 1 | 2
-- expect: 3 | 1 | 1

-- @case group-expression-key
SELECT v / 10 AS bucket, COUNT(*) FROM g GROUP BY v / 10 ORDER BY bucket;
-- expect: 0 | 2
-- expect: 1 | 2
-- expect: 2 | 1

-- @case having-filter
SELECT dept, COUNT(*) FROM g GROUP BY dept HAVING COUNT(*) > 1 ORDER BY dept;
-- expect: 1 | 2
-- expect: 2 | 2

-- @case having-alias
SELECT dept, COUNT(*) AS n FROM g GROUP BY dept HAVING n >= 2 ORDER BY dept;
-- expect: 1 | 2
-- expect: 2 | 2

-- @case having-sum
SELECT dept FROM g GROUP BY dept HAVING SUM(v) >= 20 ORDER BY dept;
-- expect: 1
-- expect: 2

-- @case aggregate-no-group
SELECT COUNT(*), SUM(v), MIN(v), MAX(v) FROM g;
-- expect: 5 | 57 | 5 | 20

-- @case aggregate-empty-table
CREATE TABLE ge (a INT);
SELECT COUNT(*), SUM(a), AVG(a), MIN(a), MAX(a) FROM ge;
-- expect: 0 | NULL | NULL | NULL | NULL

-- @case aggregate-expression-arg
SELECT SUM(v * 2) FROM g;
-- expect: 114

-- @case avg-double-column
CREATE TABLE ad (d DOUBLE);
INSERT INTO ad VALUES (1.0), (2.0), (3.5);
SELECT AVG(d) FROM ad;
-- expect: 2.1666666666666665

-- @case group-order-desc-limit
SELECT dept, COUNT(*) AS n FROM emp GROUP BY dept ORDER BY n DESC, dept LIMIT 2;
-- expect: 10 | 2
-- expect: 20 | 1

-- @case count-column-vs-star
CREATE TABLE cc (a INT, b VARCHAR(5));
INSERT INTO cc VALUES (1, 'x'), (2, 'y');
SELECT COUNT(*), COUNT(a) FROM cc;
-- expect: 2 | 2

-- @case group-by-with-join
SELECT d.dname, COUNT(*) FROM emp e JOIN dept d ON e.dept = d.id GROUP BY d.dname ORDER BY d.dname;
-- expect: eng | 2
-- expect: sales | 1

-- @case project-arithmetic-order-stable
SELECT id, dept / 10 AS d10 FROM emp WHERE id <= 2 ORDER BY id;
-- expect: 1 | 1
-- expect: 2 | 2

-- @case order-by-qualified-column
SELECT emp.id FROM emp ORDER BY emp.id DESC LIMIT 1;
-- expect: 4
