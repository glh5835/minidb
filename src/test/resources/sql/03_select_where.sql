-- 基础数据（后续 case 复用）
-- @case where-setup
CREATE TABLE w (id INT, name VARCHAR(10), dept INT, salary DOUBLE);
INSERT INTO w VALUES (1,'alice',10,100.0),(2,'bob',20,80.0),(3,'carol',10,90.0),(4,'dave',30,70.0),(5,'eve',20,95.5);
SELECT COUNT(*) FROM w;
-- expect: 5

-- @case where-eq
SELECT name FROM w WHERE id = 3;
-- expect: carol

-- @case where-neq
SELECT COUNT(*) FROM w WHERE dept != 10;
-- expect: 3

-- @case where-neq-angle
SELECT COUNT(*) FROM w WHERE dept <> 10;
-- expect: 3

-- @case where-lt
SELECT name FROM w WHERE salary < 80;
-- expect: dave

-- @case where-le
SELECT COUNT(*) FROM w WHERE salary <= 90.0;
-- expect: 3

-- @case where-gt
SELECT name FROM w WHERE salary > 95;
-- expect: alice
-- expect: eve

-- @case where-ge
SELECT COUNT(*) FROM w WHERE salary >= 95;
-- expect: 2

-- @case where-and
SELECT name FROM w WHERE dept = 10 AND salary > 95;
-- expect: alice

-- @case where-or
SELECT name FROM w WHERE dept = 30 OR salary > 95;
-- expect: dave
-- expect: eve

-- @case where-not
SELECT name FROM w WHERE NOT dept = 10;
-- expect: bob
-- expect: dave
-- expect: eve

-- @case where-and-or-precedence
SELECT name FROM w WHERE dept = 30 OR dept = 20 AND salary > 90;
-- expect: dave
-- expect: eve

-- @case where-parens
SELECT name FROM w WHERE (dept = 10 OR dept = 20) AND salary < 85;
-- expect: bob

-- @case where-string-compare
SELECT name FROM w WHERE name > 'carol';
-- expect: dave
-- expect: eve

-- @case like-prefix
SELECT name FROM w WHERE name LIKE 'a%';
-- expect: alice

-- @case like-suffix
SELECT name FROM w WHERE name LIKE '%e';
-- expect: alice
-- expect: dave
-- expect: eve

-- @case like-contains
SELECT name FROM w WHERE name LIKE '%av%';
-- expect: dave

-- @case like-underscore
SELECT name FROM w WHERE name LIKE '_ob';
-- expect: bob

-- @case like-exact
SELECT name FROM w WHERE name LIKE 'carol';
-- expect: carol

-- @case like-percent-all
SELECT COUNT(*) FROM w WHERE name LIKE '%';
-- expect: 5

-- @case like-not
SELECT name FROM w WHERE name NOT LIKE '%a%';
-- expect: bob
-- expect: eve

-- @case in-list
SELECT name FROM w WHERE dept IN (10, 30);
-- expect: alice
-- expect: carol
-- expect: dave

-- @case in-not
SELECT name FROM w WHERE dept NOT IN (10, 30);
-- expect: bob
-- expect: eve

-- @case in-single
SELECT name FROM w WHERE id IN (2);
-- expect: bob

-- @case in-strings
SELECT id FROM w WHERE name IN ('alice', 'bob');
-- expect: 1
-- expect: 2

-- @case between-numeric
SELECT name FROM w WHERE salary BETWEEN 80 AND 95;
-- expect: bob
-- expect: carol

-- @case between-not
SELECT name FROM w WHERE salary NOT BETWEEN 80 AND 95;
-- expect: alice
-- expect: dave
-- expect: eve

-- @case between-exclusive-bounds
SELECT COUNT(*) FROM w WHERE id BETWEEN 2 AND 4;
-- expect: 3

-- @case arithmetic-in-where
SELECT name FROM w WHERE salary * 2 >= 190;
-- expect: alice
-- expect: eve

-- @case arithmetic-mod
SELECT name FROM w WHERE id % 2 = 0;
-- expect: bob
-- expect: dave

-- @case arithmetic-int-division
SELECT name FROM w WHERE salary / 100 = 1;
-- expect: alice

-- @case is-null-scalar-empty
SELECT COUNT(*) FROM w WHERE (SELECT MAX(salary) FROM w WHERE dept = 99) IS NULL;
-- expect: 5

-- @case is-not-null
SELECT COUNT(*) FROM w WHERE name IS NOT NULL;
-- expect: 5

-- @case negative-comparison
SELECT COUNT(*) FROM w WHERE -salary < -90;
-- expect: 2

-- @case where-order-independent-spacing
SELECT   name   FROM    w    WHERE      id=1;
-- expect: alice

-- @case where-on-empty-result
SELECT COUNT(*) FROM w WHERE 1 = 2;
-- expect: 0

-- @case project-expression-column
SELECT id * 10 AS x FROM w WHERE id = 4;
-- expect: 40

-- @case project-string-literal
SELECT 'tag' AS label, name FROM w WHERE id = 5;
-- expect: tag | eve

-- @case mixed-case-keywords
SeLeCt NaMe FrOm w WhErE iD = 1;
-- expect: alice

-- @case select-value-no-from
SELECT 1 + 1;
-- expect: 2
