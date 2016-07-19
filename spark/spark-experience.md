# Spark 使用经验

## 1. 正确的使用flatmap
在Spark中，flatMap操作可以根据输入一条记录而生成多条记录，实际应用中看到很多代码如下：

	sc.parallelize(Array(1,2,3),3).flatMap(i=>{val a= makeArray(i);a})
	//makeArray或为MakeSeq/MakeHashMap在执行过程中，会立即完成多条记录的构建，并堆放在执行器的内存中。

而实际上flatmap的函数定义为`flatMap(f: T => TraversableOnce)`，要求返回值为`TraversableOnce`。 上面的`Array/Seq/HashMap`都是`TraversableOnce`类型，但是他们缺点是数据都已经内存中。

另外一个TraversableOnce的子类为`Iterator`，通过实现`Iterator`的`hasNext`和`next`两个函数，即可以在执行过程中来生成每条数据，执行开销一样，但是会大大的减小执行器的内存的占用。

	sc.parallelize(Array(1,2,3),3).flatMap(i=>{new Iterator[Int] {
	    var finished = false
		override def hasNext: Boolean = !finished
		override def next(): Int={
			val newItem = buildOneItem(i)
			if() finished=true
			newItem
		}
    })
    //buildOneItem每次只会生成一条记录并放在内存中

## 2. reduceByKey一定比groupByKey好吗
在很多Spark程序性能优化文章中都优先推荐使用aggregateByKey 和reduceByKey，原因就是它提供了map-side的aggregator的功能，可以在Map端对输出的数据进行aggregator，减小shuffle write和fetch的数据量；然后这个优化的前提条件是它真的可以减少数据量，否则使用reduceByKey反而适得其反，导致shuffle-sort-map过程性能底下。原因是因为多了一个map-side的aggregator计算吗？不是，主要原因是map-side的aggregator不支持unsafeSortedShuffle，而只能选择普通SortedShuffle，在数据量较大，频繁的spill会导致sort性能低下（测试结果相关4倍）。

	//测试:Key为随机生成的数字，map-side的aggregator效果一般
	val p = sc.parallelize(0 to 10, 10)
	val p1=p.flatMap(a=>{val rnd=new scala.util.Random;(0 to 5000000).map(i=>(rnd.nextLong,a))})
	p1.reduceByKey(_+_,5).saveAsTextFile("/bigfile/test/p1")
	//ShuffleWrite＝599M，耗时：57s，ShuffleFetchAndWrite＝599M，耗时：43s
	//5000000->5000000*2
	//2G内存会有Executor频繁进行fullGC，只能勉强提高Executor内存大小到3G。
	//ShuffleWrite＝1197.9M，耗时：2.2m，ShuffleFetchAndWrite＝1197.9M，耗时：1.8 min

	val p3=p.flatMap(a=>{val rnd=new scala.util.Random;(0 to 5000000).map(i=>(rnd.nextLong,a))})
	p3.groupByKey(5).map(k=>k._2.reduce(_+_)).saveAsTextFile("/bigfile/test/p1")
	//ShuffleWrite＝599M，耗时：14s，ShuffleFetchAndWrite＝599M，耗时：48s
	//5000000->5000000*2，2G->3G
	//ShuffleWrite＝1177.6M，耗时：28s，ShuffleFetchAndWrite＝1177.6M，耗时：1.8 min

	> 结论：对于map-side的aggregator效果一般，map端输出数据大小基本一致的Case下，reduceByKey的性能要比groupByKey差了4倍左右；

	//测试：Key可以减小Value大小10分之一，map-side的aggregator效果明显
	val p = sc.parallelize(0 to 10, 10)
	val p1=p.flatMap(a=>{val rnd=new scala.util.Random;(0 to 5000000).map(i=>(rnd.nextInt(5000000/10),i))})
	p1.reduceByKey(_+_,5).saveAsTextFile("/bigfile/test/p1")
	//ShuffleWrite＝57.3M，耗时：8s，ShuffleFetchAndWrite＝57.3M，耗时：5s

	val p3=p.flatMap(a=>{val rnd=new scala.util.Random;(0 to 5000000).map(i=>(rnd.nextLong/10,a))})
	p3.groupByKey(5).map(k=>k._2.reduce(_+_)).saveAsTextFile("/bigfile/test/p1")
	//ShuffleWrite＝599M，耗时：14s，ShuffleFetchAndWrite＝599M，耗时：44s

    > 结论：map-side的aggregator效果明显，reduceByKey还是很有优势的，因此在实际业务环境下需要根据数据的特点来进行选择。