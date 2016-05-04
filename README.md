填坑与埋坑
==========

从事spark相关的工作快五个月了，但是一直没有去做相关的总结，没有总结就没有沉淀，今天在这里“开贴”，自己挖坑自己埋。

### [Spark Memory解析](./spark/spark-memory-manager.md)
在Spark日常工作中（特别是处理大数据），内存算是最常见问题。看着日志里打着各种FullGC甚至OutOfMemory日志，但是却不能理解是在哪一块出了内存问题。其实也这是正常的，Spark内存管理某种程度上还是相当复杂了，涉及RDD-Cache，Shuffle，Off-Heap等逻辑，它贯穿在整个任务执行的每个环节中。

+   开始埋坑日期:2016-5-1
+   坑状态:done


### [Spark Network 模块分析（基于Netty的实现）](./spark/spark-network-netty.md)
一直以来，基于Akka实现的RPC通信框架是Spark引以为豪的主要特性，也是与Hadoop等分布式计算框架对比过程中一大亮点，但是时代和技术都在演化，从Spark1.3.1版本开始，为了解决大块数据（如Shuffle）的传输问题，Spark引入了Netty通信框架，到了1.6.0版本，Netty居然完成取代了Akka，承担Spark内部所有的RPC通信以及数据流传输。

+   开始埋坑日期:2016-4-1
+   坑状态:done

### [Hadoop DataNode分析](./hadoop/datanode.md)
在HDFS集群中,DataNode直接提供了磁盘文件的管理，也是性能的最大的瓶颈之一，对DataNode的分析显得尤为重要。这次对DataNode串读了一下，对DataNode基本逻辑有一定的了解

+   开始埋坑日期:2015-12-25
+   坑状态:done

###  [Hadoop IPC分析](./hadoop/hadoop-ipc.md)
最近比较悠闲，想回头再看一下Hadoop源码，准备着重了解一下HDFS内部的细节，突然发现以前熟读的hadoop ipc又有点模糊了，为了加深记忆，还是在这里重新梳理一下，针对大体思路上做一下笔记，hadoop源码分析类的“笔记”好难写，代码量太大了，只能扣一下比较重要的细节解释一下，具体的还是要自己去阅读代码！！！！

+   开始埋坑日期:2015-12-15
+   坑状态:done


### [系统进程性能分析](./system/java-memory.md)
好久没有埋坑了，最近突然想把系统性能以及问题跟踪相关的原理和工具稍微整理，基本都是日常开发和运维不可缺少的东西，包括[《进程分析之内存》](./system/memory.md)，[《进程分析之JAVA内存》](./system/java-memory.md)，[《进程分析之CPU》](./system/cpu.md)，[《进程分析之磁盘IO》](./system/disk-io.md)，《进程分析之网络IO》；

+   开始埋坑日期：2015-12-02
+   坑状态：doing


### [Pregel原理分析与Bagel实现](./spark/pregel-bagel.md)
[Pregel](http://people.apache.org/~edwardyoon/documents/pregel.pdf) 2010年就已经出来了, [Bagel](https://spark.apache.org/docs/latest/bagel-programming-guide.html)也2011年
就已经在spark项目中开源, 并且在最近的graphX项目中申明不再对Bagel进行支持, 使用GraphX的"高级API"进行取代, 种种迹象好像说明Pregel这门技术已经走向"末端", 其实个人的观点倒不是这样的; 

最近因为项目的需要去调研了一下图计算框架,当看到Pregel的时候就有一种感叹原来"密密麻麻"的图计算可以被简化到这样. 虽然后面项目应该是用Graphx来做,但是还是想对Pregel做一个总结.

+   开始埋坑日期：2014-12-12
+   坑状态：done

### [MLLib Pipeline的实现分析](./spark/mllib-pipeline.md)
Spark中的Mllib一直朝着可实践性的方法前进着, 而Pipeline是这个过程中一个很重要的功能. 在2014年11月,孟祥瑞在Spark MLLib代码中CI了一个全新的package:"org.apache.spark.ml", 
和传统的"org.apache.spark.mllib"独立, 这个包即Spark MLLib的Pipeline and Parameters功能. 到目前为止,这个package只有三次ci, 代码量也较少,但是基本上可以清楚看到pipeline逻辑,
这里开第一个mllib的坑, 开始对mllib进行深入学习. 

+   开始埋坑日期：2014-12-10
+   坑状态：done

### [Spark基础以及Shuffle实现分析](./spark/shuffle-study.md)
包括mapreduce和spark在内的所有离线计算工具，shuffle操作永远是设计最为笨重的，也是整体计算性能的瓶颈。主要原因是shuffle操作是不可避免的，
而且它涉及到大量的本地IO，网络IO，甚至会占用大量的内存，CPU来做sort-based shuffle相关的操作。
这里挖一个这个坑，由于第一个坑，所以我会在这个坑里面阐述大量的spark基础的东西，顺便对这些基础做一下整理,包括Job的执行过程中等;

+   开始埋坑日期：2014-9-24
+   坑状态：done

### [两种ShuffleManager的实现:Hash和Sort](./spark/shuffle-hash-sort.md)
在《[Spark基础以及Shuffle实现分析](./spark/shuffle-study.md)》中分析了Spark的Shuffle的实现,但是其中遗留了两个问题. 
本文针对第二个问题:"具体shuffleManager和shuffleBlockManager的实现"进行分析, 即HashShuffle和SortShuffle当前Spark中支持了两种ShuffleManager的实现;
其中SortShuffle是spark1.1版本发布的,详情参见:[Sort-based shuffle implementation](https://issues.apache.org/jira/browse/SPARK-2045)
本文将对这两种Shuffle的实现进行分析.

+   开始埋坑日期：2014-11-24
+   坑状态：done

###[关于Scala的implicit(隐式转换)的思考](./spark/scala-implicit.md)
Scala的隐式转换是一个很重要的语法糖,在Spark中也做了很多应用,其中最大的应用就是在RDD类型上提供了reduceByKey,groupByKey等函数接口, 如果不能对隐式
转换很好的理解, 基本上都无法理解为什么在RDD中不存在这类函数的基础上可以执行这类函数.文章内部做了解释, 同时针对隐式转换做了一些总结和思考

+   开始埋坑日期：2014-12-01
+   坑状态：done

### [Spark-Block管理](./spark/spark-block-manager.md)
在Spark里面，block的管理基本贯穿了整个计算模型，从cache的管理，shuffle的输出等等，都和block密切相关。这里挖一坑。

+   开始埋坑日期:2014-9-25
+   坑状态：done

### [Spark-Block的BlockTransferService](./spark/spark-block-manager.md)
在上面的Spark-BlockManager中,我们基本了解了整个BlockManager的结构,但是在分析Spark Shuffle时候,我发现我遗留了对BlockTransferService的分析,
毕竟Spark的Shuffle的reduce过程,需要从远程来获取Block;在上面的文章中,对于remoteGET,分析到BlockTransferService就停止了,这里补上;  

其实个人在0.91版本就遇到一个remoteGet的bug, 即当时remoteGet没有进行超时控制,消息丢包导致假死, 当然目前版本没有这个问题了. 具体的我会在这篇文章中进行解释;

+   开始埋坑日期:2014-11-25
+   期望完成日期：2014-12-10
+   坑状态：doing

### [Spark闭包清理的理解](./spark/function-closure-cleaner.md)
scala是一门函数编程语言，当然函数，方法，闭包这些概念也是他们的核心，在阅读spark的代码过程，也充斥着大量关于scala函数相关的特性引用，比如：

    def map[U: ClassTag](f: T => U): RDD[U] = new MappedRDD(this, sc.clean(f))
map函数的应用，每次我传入一个f都会做一次sc.clean的操作，那它到底做了什么事情呢？其实这些都和scala闭包有关系。  
同时java8之前版本,java不对闭包的支持,那么java是通过内部类来实现,那么内部类与闭包到底有那些关系和区别呢?同时会深入剖析java的内部类与scala的内部类的区别

+   开始埋坑日期:2014-9-25
+   期望完成日期：2014-10-30
+   坑状态：doing

### [HBase总结笔记](./hbase/hbase-learn.md)
在Hadoop系里面玩了几年了,但是HBase一直以来都不太原因去深入学习.这次借项目,系统的对HBase进行学习,这里对Hbase里面一些核心主题进行总结.
目前还没有打算从源码层面去深入研究的计划,后面有时间再一一研究.

+   开始埋坑日期:2014-10-10
+   坑状态：done

###   [HBase Bulk Loading实践](./hbase/hbase-bulk-loading.md)
近期需要将mysql中的30T的数据导入到HBase中,一条条put,HDFS的IO负载太大,所以采用Hbase内部提供的Bulk Loading工具批量导入.   
Bulk Loading直接通过把HFile文件加载到已有的Hbase表中,因此我们只需要通过一个mapreduce将原始数据写为HFile格式,就可以轻易导入大量的数据.  

+   开始埋坑日期:2014-10-15
+   坑状态:done

###[HBase Filter学习](./hbase/hbase-filter.md)
HBase的逻辑查询是严格基于Filter来实现的,在HBase中,针对Filter提供了一个包来实现,类型还是挺多的,因为业务需要,这里做一个简单的整理.
对日常比较需要的Filter拿出来做一个分析

+   开始埋坑日期:2014-11-10
+   坑状态：done

###   [NodeManager解析系列一：内存Monitor分析](./hadoop/nodemanager-container-monitor.md)
用过MapReduce都遇到因为task使用内存过多，导致container被kill，然后通过网上来找资料来设置mapreduce.map.memory.mb/mapreduce.reduce.memory.mb
/mapreduce.map.java.opts/mapreduce.reduce.java.opts来解决问题。但是对于内部实现我们还是不清楚，这篇文章就是来解析NodeManager怎么
对container的内存使用进行Monitor

+   开始埋坑日期:2014-10-20
+   坑状态:done

###   [NodeManager解析系列二：Container的启动](./hadoop/nodemanager-container-launch.md)
Hadoop里面模块很多，为什么我优先对NodeManager进行解析呢？因为NodeManager与我提交的spark/mapreduce任务密切相关，
如果对NodeManager理解不透，都不能理解Spark的Executor是怎么被调度起来的。这篇文件就是对Container的启动进行分析

+   开始埋坑日期:2014-11-2
+   坑状态:done

###   [NodeManager解析系列三：Localization的分析](./hadoop/nodemanager-container-localizer.md)
任何一个阅读过NodeManager源码的人都被Localization弄得晕头转向的，从LocalResource，LocalizedResource，LocalResourcesTracker，
LocalizerTracker这些关键字开始，命名十分接近，稍不注意注意就搞糊涂了，这篇文章对Localization进行分析，从个人感受来看，对Localization
理解透了，基本上NodeManager都理解差不多了

+   开始埋坑日期:2014-11-2
+   坑状态:done

### [NodeManager解析系列四：与ResourceManager的交互](./hadoop/nodemanager-container-withrm.md)
在yarn模型中，NodeManager充当着slave的角色，与ResourceManager注册并提供了Containner服务。前面几部分核心分析NodeManager所提供的
Containner服务，本节我们就NodeManager与ResourceManager交互进行分析。从逻辑上说，这块比Containner服务要简单很多。

+   开始埋坑日期:2014-11-4
+   坑状态:done

### [Hadoop的Metric系统的分析](./hadoop/metric-learn.md)
对于Hadoop/Spark/HBase此类的分布式计算系统的日常维护,熟读系统的metric信息应该是最重要的技能.本文对Hadoop的metric/metric2的实现进行深究,
但也仅仅是从实现的角度进行分析,对metric的完全理解需要时间积累,这样才能理解整个系统中每个metric的值对系统的影响.  
在JVM内部,本身也有一套metric系统JMX,通过JMX可以远程查看甚至修改的应用运行时信息,本文将会从JMX开始,一步一步对这几套系统metric的实现进行分析.

+   开始埋坑日期:2014-10-15
+   期望完成日期：2014-10-30
+   坑状态：doing