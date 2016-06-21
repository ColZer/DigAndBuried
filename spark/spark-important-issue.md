##我比较关心的Spark-Core-Issue

###Spark-Core 网络模块的重构：

1. 2016年02月 [SPARK-13529][BUILD] Move network/* modules into common/network-* 网络通信模块升级为Common项目
2. 2015年03月 [SPARK-5124][Core] A standard RPC interface and an Akka implementation RPC提供插件式接口,并把Akka的裸用升级为其中的一个具体实现
3. 2015年09月[SPARK-6028][Core]A new RPC implemetation based on the network module  基于network-common实现的RPC接口,即基于Netty的RPC接口
4. 2015年12月[SPARK-7995][SPARK-6280][CORE] Remove AkkaRpcEnv and remove systemName from setupEndpointRef 基于Akka的RPC告别历史舞台

###Spark-Core Shuffle的重构：

1. 2014年06月[SPARK-2044] Pluggable interface for shuffles ShuffleManager提供插件式接口,HashShuffle为第一个实现.
2. 2014年07月[SPARK-2045] Sort-based shuffle 基于Sorted的ShuffleManager上线.
3. 2014年07月[SPARK-2047] Introduce an in-mem Sorter, and use it to reduce mem usage 引入TimSort算法
4. 2014年10月[SPARK-3453] Netty-based BlockTransferService, extracted from Spark core ShuffleFetch 为了解决ShuffleFetch过程中存在的各种问题,第一次引入netty重构了network模块,即network-common的前生
5. 2014年11月[SPARK-3796] Create external service which can serve shuffle files 支持外部独立ShuffleService,支持yarn和mesos,后面的executors的动态分配都依赖这个功能.
6. 2015年11月[SPARK-10708] Consolidate sort shuffle implementations SortedShuffle最大的一次升级，增加了bypassMergeSort和Unsafe堆外内存的支持.
7. 2015年11月[SPARK-10984] Simplify *MemoryManager class structure Shuffle过程中内存管理与MemoryManager进行整合.
8. 2016年04月[SPARK-14667] Remove HashShuffleManager HashShuffle正式下线

###Spark-Core 内存管理的升级

1. 2015年10月[SPARK-10956] Common MemoryManager interface for storage and execution MemoryManager提供插件式接口,支持对存储和Shuffle对内存进行管理,静态内存分段管理为默认的实现
2. 2015年11月[SPARK-10983] Unified memory manager 引入统一内存管理
3. 2015年11月[SPARK-11389][CORE] Add support for off-heap memory to MemoryManager 支持堆外内存的使用，支持Tungsten，支持Spillable，支持了Consolidate sort shuffle
4. 2016年04月[SPARK-13992] Add support for off-heap caching 支持RDD的off－heap的cache（Spark 2.x removed the external block store API that Tachyon caching was based on (see #10752 / SPARK-12667)）

###Spark-Core 其他升级

1. 2015年10月[SPARK-3795] Heuristics for dynamically scaling executors 开始启动Executor的动态分配，到1.5.2版本在所有集群环境下都得到支持。
2. 2016年05月[SPARK-1239] Improve fetching of map output statuses优化ShuffleFecth过程中.(Bug:Map任务数目为7w,Fecth任务数目7w,Shuffle输出13T,MapOutput输出1.2G,在ShuffleFecth启动时,Driver内存倍瞬间打包,这个ISSUE可以解决这个问题)
3. 2016年02月[SPARK-12757] Add block-level read/write locks to BlockManager 对于Block的操作本身就存在了锁的存在

###Spark-Streaming升级
1. 2015年04月[SPARK-7056] [STREAMING] Make the Write Ahead Log pluggable 支持WAL， 大大的增加了streaming的可用性
2. 2015年08月[SPARK-8979] Add a PID based rate estimator 基于PID算法的流量控制
3. 2016年04月[SPARK-12133][STREAMING] Streaming dynamic allocation 支持SparkStreaming的Executor的动态分配，不好用。很多Core中的Patch没有打到这边，比如：([SPARK-13162] Standalone mode does not respect initial executors 其中spark.cores.max与动态分配的冲突。)