-- @case sub-setup
CREATE TABLE emp (id INT, name VARCHAR(10), dept INT, salary INT);
CREATE TABLE dept (id INT, dname VARCHAR(10));
INSERT INTO emp VALUES (1,'alice',10,100),(2,'bob',20,80),(3,'carol',10,90),(4,'dave',30,70);
INSERT INTO dept VALUES (10,'eng'),(20,'sales'),(40,'hr');
SELECT COUNT(*) FROM dept;
-- expect: 3

-- @case in-subquery
SELECT name FROM emp WHERE dept IN (SELECT id FROM dept) ORDER BY id;
-- expect: alice
-- expect: bob
-- expect: carol

-- @case not-in-subquery
SELECT name FROM emp WHERE dept NOT IN (SELECT id FROM dept);
-- expect: dave

-- @case scalar-subquery-eq
SELECT name FROM emp WHERE salary = (SELECT MAX(salary) FROM emp);
-- expect: alice

-- @case scalar-subquery-lt
SELECT name FROM emp WHERE salary < (SELECT AVG(salary) FROM emp) ORDER BY name;
-- expect: bob
-- expect: dave

-- @case exists-subquery
SELECT dname FROM dept WHERE EXISTS (SELECT 1 FROM emp WHERE emp.dept = dept.id) ORDER BY dname;
-- expect: eng
-- expect: sales

-- @case not-exists-subquery
SELECT dname FROM dept WHERE NOT EXISTS (SELECT 1 FROM emp WHERE emp.dept = dept.id);
-- expect: hr

-- @case correlated-scalar
SELECT e.name FROM emp e WHERE e.salary >= (SELECT AVG(salary) FROM emp WHERE emp.dept = e.dept) ORDER BY e.name;
-- expect: alice
-- expect: bob
-- expect: dave

-- @case correlated-in
SELECT d.dname FROM dept d WHERE d.id IN (SELECT emp.dept FROM emp WHERE emp.salary > 85) ORDER BY d.dname;
-- expect: eng

-- @case scalar-empty-is-null
SELECT name FROM emp WHERE (SELECT MAX(salary) FROM emp WHERE dept = 99) IS NULL;
-- expect: alice
-- expect: bob
-- expect: carol
-- expect: dave

-- @case in-subquery-with-where
SELECT name FROM emp WHERE id IN (SELECT id FROM emp WHERE salary >= 90) ORDER BY id;
-- expect: alice
-- expect: carol

-- @case scalar-min-compare
SELECT COUNT(*) FROM emp WHERE salary > (SELECT MIN(salary) FROM emp);
-- expect: 3

-- @case subquery-arithmetic
SELECT name FROM emp WHERE salary = (SELECT MAX(salary) FROM emp) OR salary = (SELECT MIN(salary) FROM emp);
-- expect: alice
-- expect: dave

-- @case exists-with-constant
SELECT COUNT(*) FROM dept WHERE EXISTS (SELECT 1 FROM emp);
-- expect: 3

-- @case not-exists-with-constant
SELECT COUNT(*) FROM dept WHERE NOT EXISTS (SELECT 1 FROM emp);
-- expect: 0

-- @case correlated-exists-count
-- expect-error
SELECT COUNT(*) FROM dept WHERE EXISTS (SELECT 1 FROM emp WHERE emp.dept = dept.dept_id);

-- @case in-empty-subquery
SELECT COUNT(*) FROM emp WHERE id IN (SELECT id FROM emp WHERE 1 = 2);
-- expect: 0

-- @case not-in-empty-subquery
SELECT COUNT(*) FROM emp WHERE id NOT IN (SELECT id FROM emp WHERE 1 = 2);
-- expect: 4

-- @case scalar-count-compare
SELECT name FROM emp WHERE (SELECT COUNT(*) FROM emp) = 4;
-- expect: alice
-- expect: bob
-- expect: carol
-- expect: dave

-- @case two-level-nesting
SELECT name FROM emp WHERE dept IN (
  SELECT id FROM dept WHERE dname IN (SELECT dname FROM dept WHERE id = 10)
);
-- expect: alice
-- expect: carol

-- @case correlated-aggregate-having
SELECT e.dept FROM emp e GROUP BY e.dept
HAVING AVG(e.salary) > (SELECT AVG(salary) FROM emp) ORDER BY e.dept;
-- expect: 10

-- @case in-subquery-dedup
CREATE TABLE dup (x INT);
INSERT INTO dup VALUES (10), (10), (20);
SELECT name FROM emp WHERE dept IN (SELECT x FROM dup) ORDER BY id;
-- expect: alice
-- expect: bob
-- expect: carol

-- @case scalar-subquery-in-project
SELECT name, (SELECT MAX(salary) FROM emp) AS top FROM emp WHERE id = 1;
-- expect: alice | 100

-- @case exists-true-literal
SELECT dname FROM dept WHERE EXISTS (SELECT 1) ORDER BY dname LIMIT 1;
-- expect: eng

-- @case subquery-error-bad-column
-- expect-error
SELECT name FROM emp WHERE dept IN (SELECT no_col FROM dept);

-- @case not-in-matches-none
SELECT name FROM emp WHERE dept NOT IN (SELECT id FROM dept WHERE id = 10 OR id = 20 OR id = 30);
-- expect: dave
