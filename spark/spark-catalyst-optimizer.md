# Spark-Catalyst Optimizer

Logical Plan Optimizer为Spark Catalyst工作最后阶段了，后面生成Physical Plan以及执行，主要是由Spark SQL来完成。Logical Plan Optimizer主要是对Logical Plan进行剪枝，合并等操作，进而删除掉一些无用计算，或对一些计算的多个步骤进行合并。

关于Optimizer：优化包括RBO（Rule Based Optimizer）/CBO(Cost Based Optimizer)，其中这里基于Spark Catalyst是属于RBO，即基于一些经验规则（Rule）对Logical Plan的语法结构进行优化；在生成Physical Plan时候，还可以基于Cost代价做进一步的优化，比如多表join，优先选择小表进行join，以及根据数据大小，在HashJoin/SortMergeJoin/BroadcastJoin三者之间进行抉择。

下面我们将会对一些主要的优化Rule进行逐条分析。由于优化的策略会随着知识的发现而逐渐引入，核心还是要理解原理！！

> 下面实例中的`a,b`为表`t`的两个字段:`CREATE TABLE `t`(`a` int, `b` int, `c` int)`。
> 可以通过explain extended sql来了解我们sql 语句优化情况.

#### 1. BooleanSimplification: 简化Boolean表达式，主要是针对Where语句中的And/Or组合逻辑进行优化。

主要包括三项工作，由于比较简单，就不贴完整的sql语句了：
1. Simplifies expressions whose answer can be determined without evaluating both sides 简化不需要对两边都进行计算的Bool表达式。
- 实例：`true or a=b`-->`true`

2. Eliminates / extracts common factors. 对`And/OR`两边相同子表达式进行抽离，避免重复计算。
- 实例：`(a=1 and b=2) or (a=1 and b>2);`-->`(a=1) and (b=2 || b>2)`

3. Merge same expressions如果`And/OR`左右表达式完全相等，就可以删除一个。
- 实例：`a+b=1 and a+b=1`-->`a+b=1`

4. Removes `Not` operator.转换`Not`的逻辑。实例：`not(a>b)`-->`a<=b`

#### 2. NullPropagation 对NULL常量参与表达式计算进行优化。与True/False相似，如果NULL常量参与计算，那么可以直接把结果设置为NULL，或者简化计算表达式。

1. IsNull/IsNotNull/EqualNullSafe 针对NULL进行判断，直接返回NULL。
2. GetArrayItem/GetMapValue/GetStructField/GetArrayStructFields在key为NULL或者整个Array/Map为NULL的时候，直接返回NULL。
3. Substring/StringRegexExpression/BinaryComparison/BinaryArithmetic/In 字符串数字进行操作，如果参数为NULL之类的，可以直接返回NULL。
4. Coalesce/AggregateExpression如果Child表达式有NULL，可以进行删除等操作

#### 3. SimplifyCasts 删除无用的cast转换。如果cast前后数据类型没有变化，即可以删除掉cast操作

- 实例：`select cast(a as int) from t` --> `select a from t` //a本身就是int类型

#### 4. SimplifyCaseConversionExpressions 简化字符串的大小写转换函数。如果对字符串进行连续多次的Upper/Lower操作，只需要保留最后一次转换即可。

- 实例：`select lower(upper(lower(a))) as c from t;` --> `select lower(a) as c from t;`

#### 5. SimplifyBinaryComparison 针对>=,<=,==等运算，如果两边表达式`semanticEquals`相等，即可以他们进行简化。

如果进行==，>=，<=比较，那么可以简化为Ture；如果进行>，<比较，那么可以简化为Flase

#### 6. OptimizeIn 使用HashSet来优化set in 操作

如果In比较操作符对应的set集合数目超过"spark.sql.optimizer.inSetConversionThreshold"设置的值(默认值为10)，那么Catalyst会自动将set转换为Hashset，提供in操作的性能。

- 实例：`select * from t where a in (1,2,3)`对应的In操作为`Filter a#13 IN (1,2,3)`。
- 而`select * from t where a in (1,2,3,4,5,6,7,8,9,10,11)`为`Filter a#19 INSET (5,10,1,6,9,2,7,3,11,8,4)`

#### 7. LikeSimplification 简化正则匹配计算。针对`前缀，后缀，包含，相等`四种正则表达式，可以将Like操作转换为普通的字符串比较。

1. 如果Like表达式为前缀匹配类型"([^_%]+)%"，即转换为startWith字符串函数操作。
- 实例：`select * from t where a like "2%"` --> `+- 'Filter 'a.startwith(2)` //是内部转换，不存在StartWith对应的sql函数

2. 同理，如果Like表达式是后缀匹配类型"%([^_%]+)"，或包含"%([^_%]+)%"，或相等"([^_%]*)"。可以转换为EndsWith，Contains，EqualTo等字符串比较。

3. 如果同时为前缀和后缀，即“([^_%]+)%([^_%]+)”，即转换为EndsWith和StartWith进行And操作。


#### 8. GetCurrentDatabase和ComputeCurrentTime 在优化阶段对`current_database(), current_date(), current_timestamp()`函数直接计算出值。

- 实例：`select current_database()` --> `select "default" as current_database()`
- 实例：`select current_timestamp();` --> `select 1467996624588000 AS current_timestamp()`


#### 9. ColumnPruning 字段剪枝，即删除Child无用的的output字段

1. p @ Project(_, p2: Project) 如果p2输出的字段有p中不需要的，即可以简化p2的输出。
- 实例：`select a from (select a,b from t)` --> `select a from (select a from t)`。在下面的`CollapseProject`会对这个表达式进行二次优化。

2. p @ Project(_, a: Aggregate)，原理同上，Aggregate只是一个Project的包装而已
- 实例：`select c from (select max(a) as c,max(b) as d from t)` --> `select c from (select max(a) as c from t)`。在下面的`CollapseProject`会对这个表达式进行二次优化。

3. a @ Aggregate(_, _, child)，a @ Aggregate(_, _, child) 原理同上

4. p @ Project(_, child)，if sameOutput(child.output, p.output)即child和p有相同的输出，就可以删除Project的封装
- 实例：`select b from (select b from t)` --> `select b from t`这个操作与`CollapseProject`原理一致


#### 10. CollapseProject  针对Project操作进行合并。将Project与子Project或子Aggregate进行合并。是一种剪枝操作

1. p1 @ Project(_, p2: Project)，连续两次Project操作，并且Project输出都是deterministic类型，那么就两个Project进行合并。
- 实例：`select c + 1 from (select a+b as c from t)` -->`select a+b+1 as c+1 from t`。

你可以能会问，这种合并会不会因为p1和p2的输出不是完全一样，而优化出错呢？
- 首先如果p1中有，但是p2中没有！抱歉，语法错误。`select c + 1,a from (select a+b as c from t)`-->`cannot resolve '`a`' given input columns`
- 其次如果p2中有，但是p1中不需要！会被ColumnPruning剪掉，不会存在这种case。`select c + 1 from (select a+b as c,a from t)`-->`select a+b+1 as c+1 from t`
因此是可以证明p1和p2连续两次Project操作，只要他们都是deterministic类型，那么他们输出肯定是一致的。

2. p @ Project(_, agg: Aggregate) 原理同上
- 实例：`select c+1 from (select max(a) as c from t)` --> `select max(a)+1 as c+1 from t`


#### 11. CollapseRepartition 针对多次Repartition操作进行合并，Repartition是一种基于exchange的shuffle操作，操作很重，剪枝很有必要。

如果连续进行两次Repartition，是可以对他们操作进行合并的，而且以外层的`numPartitions`和`shuffle`参数为主。
- 实例：`Repartition(numPartitions, shuffle, Repartition(_, _, child))`-->`Repartition(numPartitions, shuffle, child)`

> 注意：Repartition操作只针对在DataFrame's上调用`coalesce` or `repartition`函数，是无法通过SQL来构造含有Repartition的Plan。
> SQL中类似的为`RepartitionByExpression`，但是它不适合这个规则
> 比如：`select * from (select * from t  distribute by a) distribute by a`会产生两次RepartitionByExpression操作。
> == Optimized Logical Plan ==
> RepartitionByExpression [a#391]
>      +- RepartitionByExpression [a#391]
>      +- MetastoreRelation default, t

#### 12. CombineLimits：Limit操作合并。针对GlobalLimit，LocalLimit，Limit三个，如果连续多次，会选择最小的一次limit来进行合并。

- 实例：`select * from (select * from t limit 10) limit 5` --> `select * from t limit 5`
- 实例：`select * from (select * from t limit 5) limit 10` --> `select * from t limit 5`

#### 13. CombineFilters：Filter操作合并。针对连续多次Filter进行语义合并，即AND合并。

- 实例：`select a from (select a from t where a > 10) where a>20` --> `select a from t where a > 10 and a>20`
- 实例：`select a as c from (select a from t where a > 10)` --> `select a as c from t where a > 10`

#### 14. CombineTypedFilters：对TypedFilter进行合并，与CombineFilters功能一致，只是它是针对TypedFilter内部的函数进行合并，而`CombineFilters`是针对表达式进行合并。

即对两个TypedFilter的Func进行And组合：`combineFilterFunction(t2.func, t1.func)`

#### 15. PruneFilters 对Filter表达式进行剪枝 ，前面的`CombineFilters`和`CombineTypedFilters`都是Filter操作进行合并，这里是针对Filter表达式进行合并剪枝操作。

1. 如果Filter逻辑判断整体结果为True，那么是可以删除这个Filter表达式
- 实例：`select * from t where true or a>10` --> `select * from t`

2. 如果Filter逻辑判断整体结果为False或者NULL，可以把整个plan返回data设置为Seq.empty，Scheme保持不变。
- 实例：`select a from t where false` --> `LocalRelation <empty>, [a#655]`

3. 对于f @ Filter(fc, p: LogicalPlan)，如果fc中判断条件在Child Plan的约束下，肯定为Ture，那么就可以移除这个Filter判断，即Filter表达式与父表达式重叠。
- 实例：`select b from (select b from t where a/b>10 and b=2) where b=2` --> `select b from (select b from t where a/b>10 and b=2) `

#### 16. SimplifyConditionals 简化IF/Case语句逻辑。原理基本上和PruneFilters，BooleanSimplification一样，即删除无用的Case/IF语句

1. 对于If(predicate, trueValue, falseValue)，如果predicate为常量Ture/False/Null，是可以直接删除掉IF语句。不过SQL显式是没有IF这个函数的，但是Catalyst中有很多逻辑是会生成这个IF表达式。
- case If(TrueLiteral, trueValue, _) => trueValue
- case If(FalseLiteral, _, falseValue) => falseValue
- case If(Literal(null, _), _, falseValue) => falseValue

2. 对于CaseWhen(branches, _)，如果branches数组中第一个元素就为True，那么实际不需要进行后续case比较，直接选择第一个case的对应的结果就可以
- 实例：`select a, (case when true then "1" when false then "2" else "3" end) as c from t` -->
		`select a, "1" as c from t`

3. 对于CaseWhen(branches, _)，如果中间有when的值为False或者NULL常量，是可以直接删除掉这个表达式的。
- 实例：`select a, (case when b=2 then "1" when false then "2" else "3" end) as c from t` -->
		`select a, (case when b=2 then "1" else "3" end) as c from t`。//`when false then "2"`会被直接简化掉。

#### 17. ReplaceDistinctWithAggregate 用Aggregate来替换Distinct操作，换句话说Distinct操作不会出现在最终的Physical Plan中的

- Distinct(child) => Aggregate(child.output, child.output, child)
- 实例：`select distinct a,b from t` --> `select a,b from t group by a,b`

#### 18. ReplaceExceptWithAntiJoin 用AntiJoin操作来替换“except distinct”操作，注意不针对"except all"

distinct Except(left, right)操作的含义是从left中删除调right中存在的数据，以及自己当中存在重复的操作。因此可以立刻时left和right做了一个AntiJoin，并且join是输出不相等，同时对结果做distinct操作。
- 实例：`select a,b from t where b=10 except DISTINCT select a,b from t` -> `select distinct a,b from t where b=10 anti join (select a,b from t where a=10) t1 where t1.a != t.a and t1.b != t.b`

#### 19. ReplaceIntersectWithSemiJoin 用LEFT SemiJoin操作来替换“Intersect distinct”操作，注意不针对"Intersect all"

- 实例: "select a,b from t  Intersect distinct select a,b from t where a=10" -> `select distinct a,b from t where b=10 left semi join (select a,b from t where a=10) t1 where t1.a != t.a and t1.b != t.b`

> 针对上面ReplaceExceptWithAntiJoin和ReplaceIntersectWithSemiJoin，都是只支持”distinct”，那么你可能会问，那么怎么支持"all"？答案是：**spark sql根本就不支持"Intersect all"和"except all"操作，哈哈！！**

#### 20. LimitPushDown Limit操作下移，可以减小Child操作返回不必要的字段条目

1. LocalLimit(exp, Union(children)) 将limit操作下移到每个 Union上面；
- 实例：`(select a from t where a>10 union all select b from t where b>20) limit 30` --> `(select a from t where a>10 limit 30 union all select b from t where b>20 limit 30) limit 30`

> //注意：该规则中的Union操作为`UNION ALL`，不适用于`UNION DISTINCT`

2. LocalLimit(exp, join @ Join(left, right, joinType, _))  根据Join操作的类型，将limit操作移下移到left或者right。

#### 30. PushDownPredicate 对于Filter操作，原则上它处于越底层越好，他可以显著减小后面计算的数据量。

1. filter @ Filter(condition, project @ Project(fields, grandChild))
- 实例：`select rand(),a from (select * from t) where a>1` --> `select rand(),a from t where a>1` //如果Project包含nondeterministic
- 实例：`select rand(),a,id from (select *,spark_partition_id() as id from t) where a>1;` //是无法进行这个优化。

2. filter @ Filter(condition, aggregate: Aggregate) 对于Aggregate,Filter下移作用很明显。但不是所有的filter都可以下移，有些filter需要依赖整个aggregate最终的运行结果。如下所示
- 实例：`select a,d from (select count(a) as d, a from t group by a) where a>1 and d>10` 对于`a>1`和`d>10`两个Filter，显然`a>1`是可以下移一层，从而可以减小group by数据量。
- 而`d>10`显然不能，因此它优化以后的结果为 `select a,d from (select count(a) as d, a from t where a>1 group by a) where d>10`

3. filter @ Filter(condition, union: Union)原理一样还有大部分的一元操作，比如Limit，都可以尝试把Filter下移，来进行优化。
- 实例：`select * from (select * from t limit 10) where a>10`

> 但是如果子表达式输出non-deterministic类型，是不允许进行这项操作。// SPARK-13473: We can't push the predicate down when the underlying projection output non-deterministic field(s).  Non-deterministic expressions are essentially stateful. This implies that, for a given input row, the output are determined by the expression's initial state and all the input rows processed before. In another word, the order of input rows matters for non-deterministic expressions, while pushing down predicates changes the order.

#### 31. PushProjectThroughSample 将Project操作下移到Sample操作，从而精简Sample的输出。是一种剪枝操作
- case Project(projectList, Sample(lb, up, replace, seed, child)) => Sample(lb, up, replace, seed, Project(projectList, child))()

#### 32. PushPredicateThroughJoin 针对Join操作，调整Filter过滤规则