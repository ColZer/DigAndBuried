HashShuffle和SortShuffleManager实现分析
=====

在《[Spark基础以及Shuffle实现分析](./spark/shuffle-study.md)》中，我们说到Shuffle包括ShuffleMapStage和ShuffledRDD两步骤，分别对应了Shuffle的Map和Reduce；在这两个步骤中ShuffleManager充当了很重要的角色。

+   它指导了ShuffleMapStage的Task怎么进行Map的write操作
+   它指导了ShuffledRDD的compute函数怎么去每个Map的节点拉取指定reduce的数据；

ShuffleManager接口代码这里就不贴了，详情参见上一篇文章。

另外在Spark中，通过配置信息可以来选择具体ShuffleManager的实现版本：HashShuffleManager或SortShuffleManager。下面我们分别针对两种ShuffleManager进行分析；

##HashShuffleManager
HashShuffleManager是Spark最早版本的ShuffleManager，该ShuffleManager的严重缺点是会产生太多小文件，特别是reduce个数很多时候，存在很大的性能瓶颈。

这里就直接给出小文件个数，后面会分析原因：ShuffleMapTask个数×reduce个数。这个数字很大的，假设512M的HDFS的分片大小，1T数据对应2048个map，如果reduce再大，
那么就生成几万/几十万个小文件，这是致命的；

针对这个问题，HashShuffleManager在“某个版本”支持了consolidateFiles（合并文件）的机制；可以通过“spark.shuffle.consolidateFiles”配置来启动，在处理reduce个数很多的
计算时候，这个功能可以显著的提供处理性能。这个机制生成的小文件个数是多少呢？对于单个Executor，如果并发的ShuffleMapTask的个数为M个，那么在该Executor上针对该Shuffle
最多会生成M×reduce个数个小文件，总小文件的个数，当然是所有Executor上小文件之和了！这个机制可以很大程度上减少小文件的个数。

那么下面我们就来分析HashShuffleManager的具体实现。

    private[spark] class HashShuffleManager(conf: SparkConf) extends ShuffleManager     
      private val fileShuffleBlockManager = new FileShuffleBlockManager(conf)
      
      override def getReader[K, C](
          handle: ShuffleHandle,startPartition: Int,endPartition: Int,
          context: TaskContext): ShuffleReader[K, C] = {
        new HashShuffleReader(handle, startPartition, endPartition, context)
      }
    
      override def getWriter[K, V](handle: ShuffleHandle, mapId: Int, context: TaskContext)
          : ShuffleWriter[K, V] = {
        new HashShuffleWriter(shuffleBlockManager, handle, mapId, context)
      }
      
      override def shuffleBlockManager: FileShuffleBlockManager = {
        fileShuffleBlockManager
      }
    }

HashShuffleManager的代码很简单，把所有功能都通过FileShuffleBlockManager，HashShuffleWriter，HashShuffleReader三个类来实现，其中FileShuffleBlockManager最为重要。
它管理Shuffle运行过程中所涉及到文件，换句话说，FileShuffleBlockManager为HashShuffleWriter，HashShuffleReader提供了write和read的FileHandle。HashShuffleWriter比较简单，
但是HashShuffleReader还是比较复杂的，后面我们会分析。

###FileShuffleBlockManager
上面说到consolidateFiles其实就是在FileShuffleBlockManager上进行改进，为了清晰表达FileShuffleBlockManager的功能，我们首先把consolidateFiles移除来进行讲解；

FileShuffleBlockManager它是BlockManager，它负责了Shuffle的Mapoutput的Block怎么写，以及怎么读；

针对怎么写的问题，FileShuffleBlockManager提供了forMapTask接口：
    
    def forMapTask(shuffleId: Int, mapId: Int, numBuckets: Int, serializer: Serializer,
          writeMetrics: ShuffleWriteMetrics) = {
        new ShuffleWriterGroup {    
          val writers: Array[BlockObjectWriter] ={
            Array.tabulate[BlockObjectWriter](numBuckets) { bucketId =>
              val blockId = ShuffleBlockId(shuffleId, mapId, bucketId)
              val blockFile = blockManager.diskBlockManager.getFile(blockId)
              if (blockFile.exists) {
                if (blockFile.delete()) {
                  logInfo(s"Removed existing shuffle file $blockFile")
                } else {
                  logWarning(s"Failed to remove existing shuffle file $blockFile")
                }
              }
              blockManager.getDiskWriter(blockId, blockFile, serializer, bufferSize, writeMetrics)
            }
          }          
        }
      }

这个函数看起来很简单，它针对每个MapTask构造一个ShuffleWriterGroup对象，该对象包含一组writer，个数为numBuckets，即reduce个数，这里对文件的管理基于
blockManager.diskBlockManager来实现的，详细可以参见《 [Spark-Block管理](./spark/spark-block-manager.md)》的讲解；

这里我们可以看到，对应每个Map都创建numBuckets个小文件，从而证明了“ShuffleMapTask个数×reduce个数”

有写就有读，getBlockData接口提供了读取指定ShuffleBlockID的数据

    override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
          val file = blockManager.diskBlockManager.getFile(blockId)
          new FileSegmentManagedBuffer(file, 0, file.length)
     }

从上面我们看到，不支持consolidateFiles的FileShuffleBlockManager还是很简单。下面我们分析支持consolidateFiles的FileShuffleBlockManager的实现

###支持consolidateFiles的FileShuffleBlockManager
从上面forMapTask我们看到，针对每个Map都会创建一个ShuffleWriterGroup对象，其中封装了一组文件的writer，这个时候我们想，多个mapTask是否可以向这个
ShuffleWriterGroup对象进行写？是的，可以，但是有一个前提是前一个map必须写完了，即close了该ShuffleWriterGroup所有的writer，那么下一个map就可以直接使用
这组ShuffleWriterGroup对象所对应Files进行写；然后通过offset和length来从相同的文件中读取属于自己map的这部分。

上面说的有点啰嗦，下面我们一步一步进行解析。

对consolidateFiles的理解首先必须理解FileGroup的概念，代码如下：

    private class ShuffleFileGroup(val shuffleId: Int, val fileId: Int, val files: Array[File]) {
        private var numBlocks: Int = 0
        private val mapIdToIndex = new PrimitiveKeyOpenHashMap[Int, Int]()
        private val blockOffsetsByReducer = Array.fill[PrimitiveVector[Long]](files.length) {
          new PrimitiveVector[Long]()
        }
        private val blockLengthsByReducer = Array.fill[PrimitiveVector[Long]](files.length) {
          new PrimitiveVector[Long]()
        }
    
        def apply(bucketId: Int) = files(bucketId)
    
        def recordMapOutput(mapId: Int, offsets: Array[Long], lengths: Array[Long]) {
          mapIdToIndex(mapId) = numBlocks
          numBlocks += 1
          for (i <- 0 until offsets.length) {
            blockOffsetsByReducer(i) += offsets(i)
            blockLengthsByReducer(i) += lengths(i)
          }
        }
    
        def getFileSegmentFor(mapId: Int, reducerId: Int): Option[FileSegment] = {
          val file = files(reducerId)
          val blockOffsets = blockOffsetsByReducer(reducerId)
          val blockLengths = blockLengthsByReducer(reducerId)
          val index = mapIdToIndex.getOrElse(mapId, -1)
          if (index >= 0) {
            val offset = blockOffsets(index)
            val length = blockLengths(index)
            Some(new FileSegment(file, offset, length))
          } else {
            None
          }
        }
      }

每个ShuffleFileGroup都被赋予了唯一的编号，即fileId，它封装了一组文件的Handle，即val files: Array[File]，它的个数即reduce的个数。

ShuffleFileGroup中最为重要四个属性:

+   numBlocks:当前ShuffleFileGroup被写入了多少个Block，这里多少个什么含义？如果有10个MapTask使用了这组ShuffleFileGroup，那么numBlocks就为10；所以
这里numBlocks命名是不好的；因为按照BlockManager里面对Block的定义，每个ShuffleBlock应该是【"shuffle_" + shuffleId + "_" + mapId + "_" + reduceId】而这里把Map
当着Block单位；
+   mapIdToIndex：这里根据使用该ShuffleFileGroup的mapTask先后顺序进行编号，比如mapID=4，mapID=7先后使用了该FileGroup，那么4->0,7->1的映射就存储在mapIdToIndex
中；
+   blockOffsetsByReducer/blockLengthsByReducer：现在有多个MapTask使用追加的方式使用同一组文件，对于其中一个reduce文件，那么每个Map就使用该文件的offset+length区间段的
文件，因此，对于每个reduce文件，我们需要存储每个mapTask使用该文件的offset和length，即这里的blockOffsetsByReducer/blockLengthsByReducer；
这两个可以理解为一个二维数组，一维为reduce编号，所以一维的个数是确定的，即reduce的个数；每个二维是一个vector，它的个数等于numBlocks，即使用该FileGroup的MapTask的个数；

对上面四个属性的理解以后，对recordMapOutput和getFileSegmentFor这两个接口理解比较简单了，分别对于了更新操作和读取操作，就不详细说了，很简单；

对ShuffleFileGroup的理解是理解consolidateFiles的重点；在传统的FileShuffleBlockManager中，每个MapTask私有一组File，即一个FileGroup，而在consolidateFiles中，
多个Map可以按照先后顺序共有一组File；

下面我们就来分析，在Spark中，是怎么实现这个先后顺序来共有一个ShuffleFileGroup；

     private class ShuffleState(val numBuckets: Int) {
        val unusedFileGroups = new ConcurrentLinkedQueue[ShuffleFileGroup]()
        val allFileGroups = new ConcurrentLinkedQueue[ShuffleFileGroup]()
     }
 
对于每个Shuffle，在FileShuffleBlockManager维护一个ShuffleState的对象，该对象有两个ShuffleFileGroup的队列（注意是队列，为什么？）;

+   allFileGroups:存储该Shuffle使用到所有的ShuffleFileGroup,所以对于Executor上,每个Shuffle使用的文件总数就为allFileGroups.length * reduce个数;
+   unusedFileGroups:一个ShuffleFileGroup在被一个MapTask使用完(使用完就是MapTask运行结束,关闭了writer)以后,会将它放到unusedFileGroups中,从而可以被重用.
所以这里的unused不是废弃的含义;另外上面说了这个是队列,而不是列表和set之类的,先进先出从而可以平均每个ShuffleFileGroup被重用的次数,否则会出现一个ShuffleFileGroup被重复使用N次;

到目前为止我们差不多已经知道了支持consolidateFiles的FileShuffleBlockManager是怎么实现的,我们还是来看一下代码的具体的实现;

    def forMapTask(shuffleId: Int, mapId: Int, numBuckets: Int, serializer: Serializer,
          writeMetrics: ShuffleWriteMetrics) = {
        new ShuffleWriterGroup {
          shuffleStates.putIfAbsent(shuffleId, new ShuffleState(numBuckets))
          private val shuffleState = shuffleStates(shuffleId)
          private var fileGroup: ShuffleFileGroup = null
    
          val writers: Array[BlockObjectWriter] =  {
            fileGroup = getUnusedFileGroup()
            Array.tabulate[BlockObjectWriter](numBuckets) { bucketId =>
              val blockId = ShuffleBlockId(shuffleId, mapId, bucketId)
              blockManager.getDiskWriter(blockId, fileGroup(bucketId), serializer, bufferSize,
                writeMetrics)
            }
          }
    
          override def releaseWriters(success: Boolean) {
              if (success) {
                val offsets = writers.map(_.fileSegment().offset)
                val lengths = writers.map(_.fileSegment().length)
                fileGroup.recordMapOutput(mapId, offsets, lengths)
              }
              recycleFileGroup(fileGroup)
          }
    
          private def getUnusedFileGroup(): ShuffleFileGroup = {
            val fileGroup = shuffleState.unusedFileGroups.poll()
            if (fileGroup != null) fileGroup else newFileGroup()
          }
    
          private def newFileGroup(): ShuffleFileGroup = {
            val fileId = shuffleState.nextFileId.getAndIncrement()
            val files = Array.tabulate[File](numBuckets) { bucketId =>
              val filename = physicalFileName(shuffleId, bucketId, fileId)
              blockManager.diskBlockManager.getFile(filename)
            }
            val fileGroup = new ShuffleFileGroup(shuffleId, fileId, files)
            shuffleState.allFileGroups.add(fileGroup)
            fileGroup
          }
    
          private def recycleFileGroup(group: ShuffleFileGroup) {
            shuffleState.unusedFileGroups.add(group)
          }
        }
      }

这是支持consolidateFiles的forMapTask的实现,和上面不同,在创建writers的时候,通过调用getUnusedFileGroup来获得; 在getUnusedFileGroup首先尝试从队列中获取
一个unusedFileGroup,如果失败就newFileGroup一个,从而实现重用; 那么一个fileGroup怎么进入unusedFileGroup呢?即releaseWriters,如果当前MapTask运行成功,
会把该MapTask对ShuffleFile的写的offset以及length更新到fileGroup中,同时设置为unusedFileGroup;

    override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
          // Search all file groups associated with this shuffle.
          val shuffleState = shuffleStates(blockId.shuffleId)
          val iter = shuffleState.allFileGroups.iterator
          while (iter.hasNext) {
            val segmentOpt = iter.next.getFileSegmentFor(blockId.mapId, blockId.reduceId)
            if (segmentOpt.isDefined) {
              val segment = segmentOpt.get
              return new FileSegmentManagedBuffer(segment.file, segment.offset, segment.length)
            }
          }
          throw new IllegalStateException("Failed to find shuffle block: " + blockId)
      }

getBlockData接口就需要去遍历所有的FileGroup, 即allFileGroups,判读其中是否有指定的mapID和reduceID对应的数据,如果有就结束遍历; 

OK,我想我们应该理解了支持consolidateFiles的FileShuffleBlockManager的实现了;

###HashShuffleWriter的实现;
如果对FileShuffleBlockManager的理解比较清楚,HashShuffleWriter的理解就比较简单.

    private[spark] class HashShuffleWriter[K, V](
        shuffleBlockManager: FileShuffleBlockManager,
        handle: BaseShuffleHandle[K, V, _],
        mapId: Int,
        context: TaskContext)
      extends ShuffleWriter[K, V] with Logging {
      
       private val dep = handle.dependency
       private val numOutputSplits = dep.partitioner.numPartitions
       private val shuffle = shuffleBlockManager.forMapTask(dep.shuffleId, mapId, numOutputSplits, ser,
          writeMetrics)
     }
 
每个Map都对应一个HashShuffleWriter, 通过reduce的分区函数partitioner来确定reduce的个数, 然后通过上面的forMapTask来返回一组FileHandle.

另外我们知道MapReduce中有一个Map端的Combine机制, 即mapSideCombine, 如果需要,那么就需要在write之前进行reduce操作, 详细逻辑如下,
dep.aggregator.get.combineValuesByKey就为mapSideCombine的逻辑;

    override def write(records: Iterator[_ <: Product2[K, V]]): Unit = {
        val iter = if (dep.aggregator.isDefined) {
          if (dep.mapSideCombine) {
            dep.aggregator.get.combineValuesByKey(records, context)
          } else {
            records
          }
        } else if (dep.aggregator.isEmpty && dep.mapSideCombine) {
          throw new IllegalStateException("Aggregator is empty for map-side combine")
        } else {
          records
        }
    
        for (elem <- iter) {
          val bucketId = dep.partitioner.getPartition(elem._1)
          shuffle.writers(bucketId).write(elem)
        }
      }

我们知道每个MapTask运行结束以后,需要请求FileShuffleBlockManager关闭相应的writers,并向Driver返回一个MapStatus,从而可以被reduce操作定位Map的位置,
这部分逻辑就是commitWritesAndBuildStatus来实现的

    private def commitWritesAndBuildStatus(): MapStatus = {
        val sizes: Array[Long] = shuffle.writers.map { writer: BlockObjectWriter =>
          writer.commitAndClose()
          writer.fileSegment().length
        }
        MapStatus(blockManager.blockManagerId, sizes)
      }
      shuffle.releaseWriters(success)

在commitWritesAndBuildStatus中统计每个reduce的大小,并关闭每个reduce的writer, 最后返回MapStatus; 
在commitWritesAndBuildStatus执行完成以后就请求FileShuffleBlockManager调用releaseWriter,关闭FileGroup;

所以整体来说HashShuffleWriter的实现还是很简单的; 通过这里的分析,大家应该对《[Spark基础以及Shuffle实现分析](./spark/shuffle-study.md)》中ShuffleMapStage应该有更
深入的认识了;

###HashShuffleReader的实现;
这里我不打算继续讲HashShuffleReader,为什么?如果大家打开SortShuffleManager,我们就会发现, 它也是使用HashShuffleReader. 这么说就是HashShuffleReader
和具体的ShuffleManager无关,在分析完SortShuffleManager我们再统一进行分析;

##SortShuffleManager
在HashShuffleManager中,不管是否支持consolidateFiles, 同一个map的多个reduce之间都对应了不同的文件,至于对应哪个文件,是由分区函数进行Hash来确定的;
这是为什么它要叫做HashShuffleManager.

下面要介绍的SortShuffleManager和HashShuffleManager有一个本质的差别,即同一个map的多个reduce的数据都写入到同一个文件中;那么SortShuffleManager产生的Shuffle
文件个数为2*Map个数; 不是说,每个map只对应一个文件吗?为什么要乘以2呢?下面我们来一一分析;

    private[spark] class SortShuffleManager(conf: SparkConf) extends ShuffleManager {
    
      private val indexShuffleBlockManager = new IndexShuffleBlockManager()
      private val shuffleMapNumber = new ConcurrentHashMap[Int, Int]()
    
      override def getReader[K, C](
          handle: ShuffleHandle,startPartition: Int,endPartition: Int,context: TaskContext): ShuffleReader= {
        new HashShuffleReader(
          handle.asInstanceOf[BaseShuffleHandle[K, _, C]], startPartition, endPartition, context)
      }
    
      override def getWriter[K, V](handle: ShuffleHandle, mapId: Int, context: TaskContext)
          : ShuffleWriter[K, V] = {
        val baseShuffleHandle = handle.asInstanceOf[BaseShuffleHandle[K, V, _]]
        shuffleMapNumber.putIfAbsent(baseShuffleHandle.shuffleId, baseShuffleHandle.numMaps)
        new SortShuffleWriter(
          shuffleBlockManager, baseShuffleHandle, mapId, context)
      }
    
      override def shuffleBlockManager: IndexShuffleBlockManager = {
        indexShuffleBlockManager
      }
    }

和HashShuffleManager一下, SortShuffleManager的主要的功能是依赖IndexShuffleBlockManager, HashShuffleReader, SortShuffleWriter三个类来实现;其中IndexShuffleBlockManager
管理Shuffle的文件,而和HashShuffleManager不一样,IndexShuffleBlockManager的复杂度很小,反而SortShuffleWriter是SortShuffleManager的核心, 
它实现了一个map的所有的reduce数据怎么写到一个文件中(先透露一下,对该map的数据进行外部排序,这样属于每个reduce的数据就为排序后文件的一个文件段);而HashShuffleReader
的实现和HashShuffleManager一致;

下面我们先分析IndexShuffleBlockManager;

    @DeveloperApi
    case class ShuffleDataBlockId(shuffleId: Int, mapId: Int, reduceId: Int) extends BlockId {
      def name = "shuffle_" + shuffleId + "_" + mapId + "_" + reduceId + ".data"
    }
    
    @DeveloperApi
    case class ShuffleIndexBlockId(shuffleId: Int, mapId: Int, reduceId: Int) extends BlockId {
      def name = "shuffle_" + shuffleId + "_" + mapId + "_" + reduceId + ".index"
    }

首先,在IndexShuffleBlockManager中,针对每个Map有两个文件,一个是index文件,一个是data文件;这个时候你肯定会问,这里的ShuffleDataBlockId,ShuffleIndexBlockId
所对应的blockID, 即为最后文件的name, 其中不是把reduceID也作为文件名的一部分吗?难道我说错了?每个map的每个reduce都对应一个Index文件和一个Data文件?

当然不是这样的,那么下面我们就来看IndexShuffleBlockManager的实现

    private[spark]
    class IndexShuffleBlockManager extends ShuffleBlockManager {    
      private lazy val blockManager = SparkEnv.get.blockManager
    
      def getDataFile(shuffleId: Int, mapId: Int): File = {
        blockManager.diskBlockManager.getFile(ShuffleDataBlockId(shuffleId, mapId, 0))
      }
    
      private def getIndexFile(shuffleId: Int, mapId: Int): File = {
        blockManager.diskBlockManager.getFile(ShuffleIndexBlockId(shuffleId, mapId, 0))
      }
      
      def removeDataByMap(shuffleId: Int, mapId: Int): Unit = {
        getDataFile(shuffleId, mapId).delete()
        getIndexFile(shuffleId, mapId).delete()
      }
      
      def writeIndexFile(shuffleId: Int, mapId: Int, lengths: Array[Long]) = {
        val indexFile = getIndexFile(shuffleId, mapId)
        val out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(indexFile)))
        var offset = 0L
        out.writeLong(offset)    
        for (length <- lengths) {
          offset += length
          out.writeLong(offset)
        }
      }
    
      override def getBlockData(blockId: ShuffleBlockId): ManagedBuffer = {
        val indexFile = getIndexFile(blockId.shuffleId, blockId.mapId)    
        val in = new DataInputStream(new FileInputStream(indexFile))
        in.skip(blockId.reduceId * 8)
        val offset = in.readLong()
        val nextOffset = in.readLong()
        new FileSegmentManagedBuffer(getDataFile(blockId.shuffleId, blockId.mapId),
          offset,nextOffset - offset)
      }
    }

首先从getDataFile和getIndexFile文件我们看到,获取ShuffleBlockID的时候是把reduceID设置为0, 换句话这就证明上面那个疑问是错误的;然后我就看getBlockData,
该函数的功能是从当前的ShuffleBlockManager中读取到指定Shuffle reduce的内容:从逻辑上面我们看到, 首先从indexFile中,按照blockId.reduceId * 8的offset
开始读取一个Long数据,这个Long就代表当前reduce数据在getDataFile中偏移量;再看writeIndexFile,我们就看到,针对一个Map都会生成一个Index文件,
按照reduce的顺序将她们的offset输出到index文件中; 

到这里你肯定Data文件是怎么输出的?难道是删除了Data文件写的逻辑?不是的;IndexShuffleBlockManager的确只提供了对Index文件管理,至于Data的文件怎么写?那就我们
接下来需要分析的SortShuffleWriter的实现了;

    private[spark] class SortShuffleWriter[K, V, C](
        shuffleBlockManager: IndexShuffleBlockManager,
        handle: BaseShuffleHandle[K, V, C], mapId: Int,  context: TaskContext)
      extends ShuffleWriter[K, V] with Logging {
    
      private val dep = handle.dependency    
      private val blockManager = SparkEnv.get.blockManager    
      private var sorter: ExternalSorter[K, V, _] = null
      override def write(records: Iterator[_ <: Product2[K, V]]): Unit = {
        if (dep.mapSideCombine) {
          sorter = new ExternalSorter[K, V, C](
            dep.aggregator, Some(dep.partitioner), dep.keyOrdering, dep.serializer)
          sorter.insertAll(records)
        } else {
          sorter = new ExternalSorter[K, V, V](
            None, Some(dep.partitioner), None, dep.serializer)
          sorter.insertAll(records)
        }
    
        val outputFile = shuffleBlockManager.getDataFile(dep.shuffleId, mapId)
        val blockId = shuffleBlockManager.consolidateId(dep.shuffleId, mapId)
        val partitionLengths = sorter.writePartitionedFile(blockId, context, outputFile)
        shuffleBlockManager.writeIndexFile(dep.shuffleId, mapId, partitionLengths)
    
        mapStatus = MapStatus(blockManager.blockManagerId, partitionLengths)
      }
    }

SortShuffleWriter的实现依赖ExternalSorter.从名称我们就看到它的含义是外部排序的含义, 我们调用sorter.writePartitionedFile(blockId, context, outputFile)将当前分片的
reduce数据输出到getDataFile(dep.shuffleId, mapId)文件中, 并返回每个reduce的大小, 然后调用writeIndexFile来写index文件;

从这里我们看到, SortShuffleManager本质上对我们的数据进行外部排序,并把排序后文件分为每个reduce段, 并把每个端的偏移量写到index文件中;至于外部排序的具体实现
我就不去分析了.

现在还ShuffleManager里面还是最后一个大问题就是ShuffleRead,下面我们继续分析;

##ShuffleReader
在《[Spark基础以及Shuffle实现分析](./spark/shuffle-study.md)》中,我对ShuffledRDD的实现也是简单进行解释了一下,这里通过对ShuffleReader的实现的分析,对Shuffle
的Reduce步骤进行详细分析;

    class ShuffledRDD[K, V, C](
        @transient var prev: RDD[_ <: Product2[K, V]],
        part: Partitioner)
      extends RDD[(K, C)](prev.context, Nil) {
        override def compute(split: Partition, context: TaskContext): Iterator[(K, C)] = {
            val dep = dependencies.head.asInstanceOf[ShuffleDependency[K, V, C]]
            SparkEnv.get.shuffleManager.getReader(dep.shuffleHandle, split.index, split.index + 1, context)
              .read()
              .asInstanceOf[Iterator[(K, C)]]
          }
     }

上面ShuffledRDD的compute函数,从逻辑我们可以看到, 它是通过shuffleManager.getReader的函数来实现了, 即我们这里要分析的ShuffleReader;

>   我们知道ShuffleMapStage中所有ShuffleMapTask是分散在Executor上的, 每个Map对应一个Task, Task运行结束以后, 会把MapOutput的信息保存在MapStatus返回给Driver,
>   Driver将其注册到MapOutputTrack中;  到目前为止, ShuffleMapStage的过程就执行完成了;
>   
>   ShuffledRDD会为每个reduce创建一个分片, 对于运行在Executor A上的shuffleRDD的一个分片的Task, 为了获取该分片的对于的Reduce数据, 它需要向MapOutputTrack获取
>   指定ShuffleID的所有的MapStatus, 由于MapOutputTrack是一个主从结构, 获取MapStatus也涉及到Executor A请求Driver的过程. 一旦获得该Shuffle所对于的所有的MapStatus,
>   该Task从每个MapStatus所对应的Map节点(BlockManager节点)去拉取指定reduce的数据, 并把所有的数据组合为Iterator, 从而完成ShuffledRDD的compute的过程;

上面即ShuffledRDD的compute过程的描述,下面我们来一步一步看每步的实现, 同时找出原因,为什么针对不同的ShuffleManager,都可以使用同一个ShuffleReader: HashShuffleReader

    private[spark] class HashShuffleReader[K, C](
        handle: BaseShuffleHandle[K, _, C],startPartition: Int,endPartition: Int,
        context: TaskContext)extends ShuffleReader[K, C]    {
        
      private val dep = handle.dependency
    
      override def read(): Iterator[Product2[K, C]] = {
        val ser = Serializer.getSerializer(dep.serializer)
        
        val iter = BlockStoreShuffleFetcher.fetch(handle.shuffleId, startPartition, context, ser)
    
        val aggregatedIter: Iterator[Product2[K, C]] = if (dep.aggregator.isDefined) {
          if (dep.mapSideCombine) {
            new InterruptibleIterator(context, dep.aggregator.get.combineCombinersByKey(iter, context))
          } else {
            new InterruptibleIterator(context, dep.aggregator.get.combineValuesByKey(iter, context))
          }
        } else if (dep.aggregator.isEmpty && dep.mapSideCombine) {
          throw new IllegalStateException("Aggregator is empty for map-side combine")
        } else {
          iter.asInstanceOf[Iterator[Product2[K, C]]].map(pair => (pair._1, pair._2))
        }
        aggregatedIter
      }

从HashShuffleReader的代码来看, 它的逻辑很简洁, 其中核心就是通过BlockStoreShuffleFetcher.fetch获取指定reduce的所有的数据, 因此fetch就是我们上面说去每个
Map拉取数据的过程.

    private[hash] object BlockStoreShuffleFetcher extends Logging {
      def fetch[T](shuffleId: Int,reduceId: Int,context: TaskContext,serializer: Serializer)
        : Iterator[T] ={
        val blockManager = SparkEnv.get.blockManager    
        val statuses = SparkEnv.get.mapOutputTracker.getServerStatuses(shuffleId, reduceId)    
    
        val blockFetcherItr = new ShuffleBlockFetcherIterator(
          context, SparkEnv.get.blockTransferService,blockManager,blocksByAddress,
          serializer,SparkEnv.get.conf.getLong("spark.reducer.maxMbInFlight", 48) * 1024 * 1024)
          
        val itr = blockFetcherItr.flatMap(unpackBlock)            
        new InterruptibleIterator[T](context, itr)
      }
    }

BlockStoreShuffleFetcher里面也没有完成实现fetch逻辑,而是交给ShuffleBlockFetcherIterator进行fetch, 但是获取指定Shuffle的所有reduce的MapStatus是这步进行的
即调用SparkEnv.get.mapOutputTracker.getServerStatuses(shuffleId, reduceId)来实现的;

那么下面就剩下了ShufflerRead最后一个步骤了,即 ShuffleBlockFetcherIterator的实现;

ShuffleBlockFetcherIterator接受的参数中一个参数就是指定的Shuffle的所有MapStatus信息,每个MapStatus其实是代表一个BlockManager的地址信息;那么就存在Local和Remote的
差别,如果是Local就可以直接通过本地的ShuffleBlockManager读取, 否则就去发起请求去远程读取.

因此第一个问题就是怎么对MapStatus进行划分, 划分为Local和Remote两个类别,即ShuffleBlockFetcherIterator. splitLocalRemoteBlocks的实现

    private[this] def splitLocalRemoteBlocks(): ArrayBuffer[FetchRequest] = {
    
        val remoteRequests = new ArrayBuffer[FetchRequest]
    
        var totalBlocks = 0
        for ((address, blockInfos) <- blocksByAddress) {
          totalBlocks += blockInfos.size
          if (address == blockManager.blockManagerId) {
            localBlocks ++= blockInfos.filter(_._2 != 0).map(_._1)
            numBlocksToFetch += localBlocks.size
          } else {
            val iterator = blockInfos.iterator
            var curRequestSize = 0L
            var curBlocks = new ArrayBuffer[(BlockId, Long)]
            while (iterator.hasNext) {
              val (blockId, size) = iterator.next()
              if (size > 0) {
                curBlocks += ((blockId, size))
                remoteBlocks += blockId
                numBlocksToFetch += 1
                curRequestSize += size
              } 
              if (curRequestSize >= targetRequestSize) {
                remoteRequests += new FetchRequest(address, curBlocks)
                curBlocks = new ArrayBuffer[(BlockId, Long)]
                curRequestSize = 0
              }
            }
            // Add in the final request
            if (curBlocks.nonEmpty) {
              remoteRequests += new FetchRequest(address, curBlocks)
            }
          }
        }
        remoteRequests
      }
  
splitLocalRemoteBlocks有两个过程, 第一判读当前MapStatus是不是Local,是就添加到localBlocks中, 否则就针对remote构造FetchRequest消息包并返回.

那么我们下面再一个一个来分析, Local的ShuffleBlock是怎么读取的?

    private[this] def fetchLocalBlocks() {
        for (id <- localBlocks) {
            shuffleMetrics.localBlocksFetched += 1
            results.put(new FetchResult(
              id, 0, () => blockManager.getLocalShuffleFromDisk(id, serializer).get))          
        }
      }
    def getLocalShuffleFromDisk(blockId: BlockId, serializer: Serializer): Option[Iterator[Any]] = {
        val buf = shuffleManager.shuffleBlockManager.getBlockData(blockId.asInstanceOf[ShuffleBlockId])
        val is = wrapForCompression(blockId, buf.inputStream())
        Some(serializer.newInstance().deserializeStream(is).asIterator)
    }

我们看到LocalBlock的获取最后就是基于每个ShuffleManager的ShuffleBlockManager的getBlockData的来实现, 在上面ShuffleManager中,我们就分析了具体的getBlockData的实现.

那remoteBlock是怎么获取的?

    private[this] def sendRequest(req: FetchRequest) {
        bytesInFlight += req.size
    
        val sizeMap = req.blocks.map { case (blockId, size) => (blockId.toString, size) }.toMap
        val blockIds = req.blocks.map(_._1.toString)
    
        blockTransferService.fetchBlocks(req.address.host, req.address.port, blockIds,
          new BlockFetchingListener {
            override def onBlockFetchSuccess(blockId: String, data: ManagedBuffer): Unit = {
              results.put(new FetchResult(BlockId(blockId), sizeMap(blockId),
                () => serializer.newInstance().deserializeStream(
                  blockManager.wrapForCompression(BlockId(blockId), data.inputStream())).asIterator
              ))
              shuffleMetrics.remoteBytesRead += data.size
              shuffleMetrics.remoteBlocksFetched += 1
            }
    
            override def onBlockFetchFailure(e: Throwable): Unit = {
              for ((blockId, size) <- req.blocks) {
                results.put(new FetchResult(blockId, -1, null))
              }
            }
          }
        )
      }

我们看到远程获取Block其实是基于blockTransferService来实现的,其实这个内容是BlockManager的BlockTransferService,这里我就不具体去分析,下一步我会写一个关于BlockTransferService
的实现,其实这块在0.91就遇到一个一个Bug,就是fetch永远等待导致job假死;后面再具体分析

到目前我们应该了解了ShuffleReader的功能, 级别上两种ShuffleManager的实现,我们就分析完了, 从上面分析我们可以看到SortShuffleManager创建小文件的数目应该是
最小的,而且Map输出是有序的, 在reduce过程中如果要进行有序合并, 代价也是最小的. 也因此SortShuffleManager现在是Spark1.1版本以后的默认配置项;

另外我们经常在各种交流说到,做了什么Shuffle优化, 从上面我们看到, Shuffle针对特定的应用有很大的优化的空间,比如基于内存的Shuffle等;懂了ShuffleManager,以后听
那些交流以后, 也就不会觉得人家多么高大不可攀了,嘿嘿,因为具体的优化接口Spark都是提供的,只是针对特定应用做了自己的实现而已;

