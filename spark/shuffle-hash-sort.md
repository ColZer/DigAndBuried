HashShuffle和SortShuffleManager实现分析
=====

在上一篇文章中，我们说到Shuffle包括ShuffleMapStage和ShuffledRDD两步骤，分别对应了Shuffle的Map和Reduce；在这两个步骤中ShuffleManager充当了很重要的角色。

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
如果对FileShuffleBlockManager的理解比较清楚,我想对




