# Spark SQL Join原理分析


## 1. Join问题综述：

Join有`inner`,`leftouter`,`rightouter`,`fullouter`,`leftsemi`,`leftanti`六种类型，对单独版本的Join操作，可以将问题表述为：

> IterA，IterB为两个Iterator，根据规则A将两个Iterator中相应的Row进行合并，然后按照规则B对合并后Row进行过滤。
> 比如Inner_join，它的合并规则A为：对IterA中每一条记录，生成一个key，并利用该key从IterB的Map集合中获取到相应记录，并将它们进行合并；而对于规则B可以为任意过滤条件，比如IterA和IterB任何两个字段进行比较操作。

对于IterA和IterB，当我们利用iterA中key去IterB中进行一一匹配时，我们称IterA为`streamedIter`，IterB为`BuildIter`或者`hashedIter`。即我们流式遍历`streamedIter`中每一条记录，去`hashedIter`中去查找相应匹配的记录。

而这个查找过程中，即为`Build`过程，每一次`Build`操作的结果即为一条`JoinRow（A,B）`，其中`JoinRow(A)`来自`streamedIter`，`JoinRow(B)`来自`BuildIter`，此时这个过程为`BuildRight`，而如果`JoinRow(B)`来自`streamedIter`，`JoinRow(A)`来自`BuildIter`，即为`BuildLeft`，

有点拗口！那么为什么要去区分`BuildLeft`和`BuildRight`呢？对于`leftouter`，`rightouter`，`leftsemi`,`leftanti`，它们的Build类型是确定，即`left*`为`BuildRight`，`right*`为`BuildLeft`类型，但是对于`inner`操作，`BuildLeft`和`BuildRight`两种都可以，而且选择不同，可能有很大性能区别：

> BuildIter也称为hashedIter，即需要将BuildIter构建为一个内存Hash，从而加速Build的匹配过程；此时如果BuildIter和streamedIter大小相差较大，显然利用小的来建立Hash，内存占用要小很多！

总结一下：Join即由下面几部分组成：

	trait Join {
	  val joinType: JoinType //Join类型
	  val streamedPlan: SparkPlan //用于生成streamedIter
	  val buildPlan: SparkPlan //用于生成hashedIter

	  val buildSide: BuildSide //BuildLeft或BuildRight
	  val buildKeys: Seq[Expression] //用于从streamedIter中生成buildKey的表达式
	  val streamedKeys: Seq[Expression] //用于从hashedIter中生成streamedKey的表达式

	  val condition: Option[Expression]//对joinRow进行过滤
	}

>  注：对于fullouter，IterA和IterB同时为streamedIter和hashedIter，即先IterA＝streamedIter，IterB＝hashedIter进行leftouter，然后再用先IterB＝streamedIter，IterA＝hashedIter进行leftouter，再把两次结果进行合并。

### 1.1 几种Join的实现

#### 1.1.1 InnerJoin

1. 利用streamIter中每个srow，从hashedIter中查找匹配项；
2. 如果匹配成功，即构建多个JoinRow，否则返回empty

		streamIter.flatMap{ srow =>
			val joinRow = new JoinedRow
			joinRow.withLeft(srow)
			val matches = hashedIter.get(buildKeys(srow))
			if (matches != null) {
		        matches.map(joinRow.withRight(_)).filter(condition)
		    } else {
		        Seq.empty
		    }
		}


#### 1.1.2 LeftOutJoin

1. leftIter即为streamIter，而RightIter即为hashedIter，不可以改变
2. 利用streamIter中每个srow，从hashedIter中查找匹配项；
3. 如果匹配成功，即构建多个JoinRow，否则返回JoinRow的Build部分为Null

		val nullRow = new NullRow()
		streamIter.flatMap{ srow =>
			val joinRow = new JoinedRow
			joinRow.withLeft(srow)
			val matches = hashedIter.get(buildKeys(srow))
			if (matches != null) {
		        matches.map(joinRow.withRight(_)).filter(condition)
		    } else {
		        Seq(joinRow.withRight(nullRow))
		    }
		}


#### 1.1.3 RightOutJoin
1. RightIter即为streamIter，而LeftIter即为hashedIter，不可以改变
2. 利用streamIter中每个srow，从hashedIter中查找匹配项；
3. 如果匹配成功，即构建多个JoinRow，否则返回JoinRow的Build部分为Null

		val nullRow = new NullRow()
		streamIter.flatMap{ srow =>
			val joinRow = new JoinedRow
			joinRow.withRight(srow)//注意与LeftOutJoin的区别
			val matches = hashedIter.get(buildKeys(srow))
			if (matches != null) {
		        matches.map(joinRow.withLeft(_)).filter(condition)
		    } else {
		        Seq(joinRow.withLeft(nullRow))
		    }
		}

#### 1.1.4 LeftSemi

1. leftIter即为streamIter，而RightIter即为hashedIter，不可以改变
2. 利用streamIter中每个srow，从hashedIter中查找匹配项；
3. 如果匹配成功，即返回srow，否则返回empty
4. 它不是返回JoinRow，而是返回srow

		streamIter.filter{ srow =>
			val matches = hashedIter.get(buildKeys(srow))
			if(matches == null) {
				false //没有找到匹配项
			} else{
				if(condition.isEmpty == false) { //需要对`假想`后joinrow进行判断
						val joinRow = new JoinedRow
						joinRow.withLeft(srow)
						! matches.map(joinRow.withLeft(_)).filter(condition).isEmpty
				} else {
					true
				}
			}
		}

	LeftSemi从逻辑上来说，它即为In判断。

#### 1.1.5 LeftAnti

1. leftIter即为streamIter，而RightIter即为hashedIter，不可以改变
2. 利用streamIter中每个srow，从hashedIter中查找匹配项；
3. 它匹配逻辑为LeftSemi基本相反，即相当于No In判断。
3. 如果匹配不成功，即返回srow，否则返回empty
4. 它不是返回JoinRow，而是返回srow

		streamIter.filter{ srow =>
			val matches = hashedIter.get(buildKeys(srow))
			if(matches == null) {
				true //没有找到匹配项
			} else{
				if(condition.isEmpty == false) { //需要对`假想`后joinrow进行判断
						val joinRow = new JoinedRow
						joinRow.withLeft(srow)
						matches.map(joinRow.withLeft(_)).filter(condition).isEmpty
				} else {
					false
				}
			}
		}

### 1.2 HashJoin与SortJoin

上面描述的Join是需要将`BuildIter`在内存中构建为`hashedIter`，从而加速匹配过程，因此我们也将这个Join称为HashJoin。但是建立一个Hash表需要占用大量的内存。
那么问题来：如果我们的Iter太大，无法建立Hash表怎么吧？在分布式Join计算下，Join过程中发生在Shuffle阶段，如果一个数据集的Key存在数据偏移，很容易出现一个`BuildIter`超过内存大小，无法完成Hash表的建立，进而导致HashJoin失败，那么怎么办？

> 在HashJoin过程中，针对`BuildIter`建立`hashedIter`是为了加速匹配过程中。匹配查找除了建立Hash表这个方法以外，将streamedIter和BuildIter进行排序，也是一个加速匹配过程，即我们这里说的sortJoin。

排序不也是需要内存吗？是的，首先排序占用内存比建立一个hash表要小很多，其次排序如果内存不够，可以将一部分数据Spill到磁盘，而Hash为全内存，如果内存不够，将会导致整个Shuffle失败。

下面以**InnerJoin的SortJoin实现**为例子，讲述它与HashJoin的区别：

1. streamIter和BuildIter都需要为有序。
2. 利用streamIter中每个srow，从BuildIter中顺序查找，由于两边都是有序的，所以查找代价很小。

		val buildIndex = 0
		streamIter.flatMap{ srow =>
			val joinRow = new JoinedRow
			joinRow.withLeft(srow)
			//顺序查找
			val matches = BuildIter.search(buildKeys(srow), buildIndex)
			if (matches != null) {
		        matches.map(joinRow.withRight(_)).filter(condition)
		        buildIndex += matches.length
		    } else {
		        Seq.empty
		    }
		}

对于`FullOuter`Join，如果采用HashJoin方式来实现，代价较大，需要建立双向的Hash表，而基于SortJoin，它的代价与其他几种Join相差不大，因此`FullOuter默认都是基于SortJon来实现。

## 2. Spark中的Join实现

Spark针对Join提供了分布式实现，但是Join操作本质上也是单机进行，怎么理解？如果要对两个数据集进行分布式Join，Spark会先对两个数据集进行`Exchange`，即进行ShuffleMap操作，将Key相同数据分到一个分区中，然后在ShuffleFetch过程中利用HashJoin/SortJoin单机版算法来对两个分区进行Join操作。

另外如果Build端的整个数据集（非一个iter）大小较小，可以将它进行Broadcast操作，从而节约Shuffle的开销。

因此Spark支持`ShuffledHashJoinExec`,`SortMergeJoinExec`,`BroadcastHashJoinExec`三种Join算法，那么它怎么进行选择的呢？

- 如果build-dataset支持Broadcastable，并且它的大小小于`spark.sql.autoBroadcastJoinThreshold`，默认10M，那么优先进行BroadcastHashJoinExec
- 如果dataset支持Sort，并且`spark.sql.join.preferSortMergeJoin`为True，那么优先选择SortMergeJoinExec
- 如果dataset不支持Sort，那么只能选择`ShuffledHashJoinExec`了
	- 如果Join同时支持BuildRight和BuildLeft，那么根据两边数据大小，优先选择数据量小的进行Hash。

这一块逻辑都在`org.apache.spark.sql.execution.JoinSelection` 中描述。ps：Spark也对`Without joining keys`的Join进行支持，但是不在我们这次讨论范围中。

**BroadcastHashJoinExec**

	val p = spark.read.parquet("/Users/p.parquet")
	val p1 = spark.read.parquet("/Users/p1.parquet")
	p.joinWith(p1, p("to_module") === p1("to_module"),"inner")

	此时由于p和p1的大小都较小，它会默认选择BroadcastHashJoinExec
	== Physical Plan ==
	BroadcastHashJoin [_1#269.to_module], [_2#270.to_module], Inner, BuildRight
		:- Project p
		:- Project p1

**SortMergeJoinExec**

	val p = spark.read.parquet("/Users/p.parquet")
	val p1 = spark.read.parquet("/Users/p1.parquet")
	p.joinWith(p1, p("to_module") === p1("to_module"),"fullouter")

	fullouterJoin不支持Broadcast和ShuffledHashJoinExec，因此为ShuffledHashJoinExec

	== Physical Plan ==
	SortMergeJoin [_1#273.to_module], [_2#274.to_module], FullOuter
		:- Project p
		:- Project p1


由于**ShuffledHashJoinExec**一般情况下，不会被选择，它的条件比较苛责。

	//首先不能进行Broadcast！
	private def canBroadcast(plan: LogicalPlan): Boolean = {
      plan.statistics.isBroadcastable ||
        plan.statistics.sizeInBytes <= conf.autoBroadcastJoinThreshold（10M）
    }
    //其次spark.sql.join.preferSortMergeJoin必须设置false
    //然后build端可以放的进内存！
    private def canBuildLocalHashMap(plan: LogicalPlan): Boolean = {
      plan.statistics.sizeInBytes < conf.autoBroadcastJoinThreshold * conf.numShufflePartitions
    }
     //最后build端和stream端大小必须相差3倍！否则使用sort性能要好。
    private def muchSmaller(a: LogicalPlan, b: LogicalPlan): Boolean = {
      a.statistics.sizeInBytes * 3 <= b.statistics.sizeInBytes
    }
    //或者RowOrdering.isOrderable(leftKeys)==false














