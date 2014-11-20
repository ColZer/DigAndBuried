Spark Block Manager管理
============

spark中的RDD-Cache, Shuffle-output, 以及broadcast的实现都是基于BlockManager来实现, BlockManager提供了数据存储（内存/文件存储）接口.

这里的Block和HDFS中谈到的Block块是有本质区别:HDFS中是对大文件进行分Block进行存储,Block大小固定为512M等;而Spark中的Block是用户的操作单位,
一个Block对应一块有组织的内存,一个完整的文件或文件的区间端,并没有固定每个Block大小的做法;

##BlockID和ManagerBuffer
上面谈到,Block是用户的操作单位,而这个操作对应的key就是这里BlockID,该Key所对应的真实数据内容为ManagerBuffer;  
先提前看一下BlockDataManager这个数据接口,它在NetWork包中,对外提供了Block的操作.

    trait BlockDataManager {
      def getBlockData(blockId: String): Option[ManagedBuffer]
      def putBlockData(blockId: String, data: ManagedBuffer, level: StorageLevel): Unit
    }

我们看到BlockDataManager中有getBlockData和putBlockData两个接口,分别通过blockId获取一个ManagedBuffer,以及将一个blockId与ManagedBuffer对添加到
BlockManager中管理; 在Spark中BlockDataManager的唯一实现也就是我们这里谈到的BlockManager"服务".

BlockID本质上是一个字符串,但是在Spark中将它保证为"一组"case类,这些类的不同本质是BlockID这个命名字符串的不同,从而可以通过BlockID这个字符串来区别BlockID.

首先我们来看在Spark中Block类型,其实也就是开头谈到的RDD-Cache, Shuffle-output, 以及broadcast等;

+   RDDBlock:"rdd_" + rddId + "_" + splitIndex; 即每个RDD block表示一个特定rdd的一个分片
+   ShuffleBlock:多说一句关于shuffle,在Spark的1.1版本中发布一个sort版本的shuffle,原先的版本为hash,因此两种类型的shuffle也对应了两种数据结构
    +   Hash版本,ShuffleBlock:"shuffle_" + shuffleId + "_" + mapId + "_" + reduceId
    +   Sort版本,对于每一个bucket(shuffleId + "_" + mapId + "_" + reduceId组合)由ShuffleDataBlock和ShuffleIndexBlock两种block组成
        +    "shuffle_" + shuffleId + "_" + mapId + "_" + reduceId + ".data"
        +    "shuffle_" + shuffleId + "_" + mapId + "_" + reduceId + ".index"
+   BroadcastBlock:"broadcast_" + broadcastId + "_" + field)具体这里不多说,不感兴趣
+   TaskResultBlock:"taskresult_" + taskId;Spark中task运行的结果也是通过BlockManager进行管理
+   StreamBlock: "input-" + streamId + "-" + uniqueId应该是用于streaming中,不是特别感兴趣
+   TempBlock: "temp_" + id

通过上面的命名规则,我们可以快速确定每个Block的类型,以及相应的业务信息. 其中RDDBlock, ShuffleBlock, TaskResultBlock是个人比较感兴趣的三种Block.

再来看看ManagedBuffer, 本质上ManagedBuffer是一个对外Buffer的封装,这个类型在BlockManager内部使用并不多,外部通过BlockDataManager的接口来获取和
保存相应的Buffer到BlockManager中,这里我们首先简单的分析一下ManagedBuffer.

    sealed abstract class ManagedBuffer {
      def size: Long
      def nioByteBuffer(): ByteBuffer
      def inputStream(): InputStream
    }

每个ManagedBuffer都有一个Size方法获取Buffer的大小,然后通过nioByteBuffer和inputStream两个接口对外提供了对Buffer的访问接口.至于这个Buffer具体存储方式由
子类来实现.比如ManagedBuffer的FileSegmentManagedBuffer子类实现了,将文件部分段转化为一个ManagedBuffer

    final class FileSegmentManagedBuffer(val file: File, val offset: Long, val length: Long)
      extends ManagedBuffer {
      override def nioByteBuffer(): ByteBuffer = {
          var channel: FileChannel = null
            channel = new RandomAccessFile(file, "r").getChannel
            channel.map(MapMode.READ_ONLY, offset, length)
      }          
    }
    
FileSegmentManagedBuffer通过NIO接口将文件Map到内存中,并返回ByteBuffer;注意这个nioByteBuffer函数是每次调用将会返回一个新的ByteBuffer,对它的操作不影响
真实的Buffer的offset和long

除了FileSegmentManagedBuffer实现以外, 还有NioByteBufferManagedBuffer(将一块已有的ByteBuffer内存封装为ManagedBuffer)和NettyByteBufManagedBuffer(
将netty中的ByteBuf内存封装为ManagedBuffer)

上面说到了ManagedBuffer只是BlockManager对外提供的Buffer表示,现在问题来了,这里谈到的BlockID对于的"Block块"在BlockManager服务中是怎么维护它的状态的呢?

## BlockInfo和StorageLevel
上面谈到的Block在BlocManager中是怎么样的维护它的状态的呢?注意我们这里不去分析Block具体是怎么存储,后面会去分析;这里分析Block的属性信息;BlockManager
为了每个Block的属性信息来跟踪每个Block的状态.

首先来看StorageLevel, 在Spark中,对应RDD的cache有很多level选择,这里谈到的StorageLevel就是这块内容;首先我们来看存储的级别:

+   DISK,即文件存储
+   Memory,内存存储,这里的内存指的是Jvm中的堆内存,即onHeap
+   OffHeap, 非JVM中Heap的内存存储;

对于DISK和Memory两种级别是可以同时出现的,而OffHeap与其他两个是互斥的.

关于OffHeap这里多说两句:JVM中如果直接进行内存分配都是受JVM来管理,使用的是JVM中内存堆,但是现在有很多技术可以在JVM代码中访问不受JVM管理的内存,即OffHeap内存;
OffHeap最大的好处就是将内存的管理工作从JVM的GC管理器剥离出来由自己进行管理,特别是大对象,自定义生命周期的对象来说OffHeap很实用,可以减少GC的代销.  
Spark中实现的OffHeap是基于[Tachyon:分布式内存文件系统](http://tachyon-project.org/)来实现的,在我们这篇分析文档中不会具体分析Tachyon的实现,有时间再去研究一下.

继续回到StorageLevel的分析; 除了三种存储级别以外,StorageLevel还提供了以下几个配置项:

+   _deserialized:Block是否已经被序列化
+   _replication:Block副本个数,默认为1

同时OffHeap不支持这两个配置;

关于Block副本是通过BlockManager peer来实现,具体后面进行分析,这里对_deserialized做一个简单的描述;存储在中BlockManager可以是各种对象,是否支持序列化影响了对
这个对象的访问以及内存的压缩.

Spark内部针对StorageLevel提供了一组默认实现:

    class StorageLevel private(
        private var _useDisk: Boolean,
        private var _useMemory: Boolean,
        private var _useOffHeap: Boolean,
        private var _deserialized: Boolean,
        private var _replication: Int = 1)
      val NONE = new StorageLevel(false, false, false, false)
      val DISK_ONLY = new StorageLevel(true, false, false, false)
      val DISK_ONLY_2 = new StorageLevel(true, false, false, false, 2)
      val MEMORY_ONLY = new StorageLevel(false, true, false, true)
      val MEMORY_ONLY_2 = new StorageLevel(false, true, false, true, 2)
      val MEMORY_ONLY_SER = new StorageLevel(false, true, false, false)
      val MEMORY_ONLY_SER_2 = new StorageLevel(false, true, false, false, 2)
      val MEMORY_AND_DISK = new StorageLevel(true, true, false, true)
      val MEMORY_AND_DISK_2 = new StorageLevel(true, true, false, true, 2)
      val MEMORY_AND_DISK_SER = new StorageLevel(true, true, false, false)
      val MEMORY_AND_DISK_SER_2 = new StorageLevel(true, true, false, false, 2)
      val OFF_HEAP = new StorageLevel(false, false, true, false)

下面我们来分析在BlockManager对于一个Block的状态是怎么进行维护的. 分析代码之前,我们先说结论:

对于BlockManager中的存储的每个Block,不一定是对应的数据都PUT成功了,不一定可以立即提供对外的读取,因为PUT是一个过程,有成功还是有失败的状态.
,拿ShuffleBlock来说,在shuffleMapTask需要Put一个Block到BlockManager中,在Put完成之前,该Block将处于Pending状态,等待Put完成了不代表Block就可以被读取,
因为Block还可能Put"fail"了.

因此BlockManager通过BlockInfo来维护每个Block状态,在BlockManager的代码中就是通过一个TimeStampedHashMap来维护BlockID和BlockInfo之间的map.
    
    private val blockInfo = new TimeStampedHashMap[BlockId, BlockInfo]

在调用上述谈到的putBlockData接口时候,首先会为该BlockID生成一个Pending状态的BlockInfo,指定PUT结束再来更新BlockInfo的状态为READY或FAILD;

同时考虑线程间同步问题,如果一个BlockID对应的Block被多个线程同时进行PUT,只有第一个创建该BlockInfo的线程才会进行PUT的过程,其他的线程会直接等到该线程结束,
并以该线程的操作结果(成功,失败)来作为返回.

谈了那么多,BlockInfo具体是怎么实现的呢?

    private[storage] class BlockInfo(val level: StorageLevel, val tellMaster: Boolean) {
      @volatile var size: Long = BlockInfo.BLOCK_PENDING
      private def pending: Boolean = size == BlockInfo.BLOCK_PENDING
      private def failed: Boolean = size == BlockInfo.BLOCK_FAILED
      private def initThread: Thread = BlockInfo.blockInfoInitThreads.get(this)
    
      setInitThread()
    
      private def setInitThread() {
        BlockInfo.blockInfoInitThreads.put(this, Thread.currentThread())
      }
      
      def waitForReady(): Boolean = {
        if (pending && initThread != Thread.currentThread()) {
          synchronized {
            while (pending) {
              this.wait()
            }
          }
        }
        !failed
      }
    
      def markReady(sizeInBytes: Long) {
        size = sizeInBytes
        BlockInfo.blockInfoInitThreads.remove(this)
        synchronized {
          this.notifyAll()
        }
      }
    
      def markFailure() {
        size = BlockInfo.BLOCK_FAILED
        BlockInfo.blockInfoInitThreads.remove(this)
        synchronized {
          this.notifyAll()
        }
      }
    }
    
上面的代码比较简单:
+   Block是否Put成功由Size进行确定,初始化Size=-1, 失败了Size=-2, 成功了Size就Block的大小,通过MarkReady接口进行设置
+   每个BlockInfo都读取当前线程设置为initThread, 即和线程绑定
+   提供waitForReady接口,对于其他线程需要监听该线程生成的BlockInfo是否Read和Failure提供"等待接口"
+   提供了markReady和markFailure两个接口来确定Block的状态状态

对于上述的等待是怎么实现的?截取BlockManager中关于Put过程中一段代码

    val putBlockInfo = {
      val tinfo = new BlockInfo(level, tellMaster)
      val oldBlockOpt = blockInfo.putIfAbsent(blockId, tinfo)
      if (oldBlockOpt.isDefined) {
        if (oldBlockOpt.get.waitForReady()) {
          logWarning(s"Block $blockId already exists on this machine; not re-adding it")
          return updatedBlocks
        }
        oldBlockOpt.get
      } else {
        tinfo
      }
    }

每个Put线程,会为每个Block创建一个BlockInfo,并尝试将它添加到BlockManager中BlockID和BlockInfo的Map中,尝试但不一定成功,因为可能其他线程已经创建了该BlockID
此时,该线程将会等待已有的BlockId对象的BlockStatus状态ready(waitForReady),并直接返回已有的BlockStatus对象,不进行重复Put操作.

通过上面描述,我们已经分析了Block的属性信息,存储级别,状态信息;下面我们就来具体分析每个Block级别的实现,即DISK是怎么进行存储的?memory是怎么进行存储的?

##BlockStore
BlockStore即Block真正的存储器;在Spark中,BlockStore是一个trait接口,用户可以针对该接口进行实现自己的Store,比如你可以实现一个通过Redis来存储的OffHeap的Store.

目前Spark提供了下面几种BlockStore的实现.其中TachyonStore本章就不进行分析,后面有时间再研究一下性能问题

+   DiskStore
+   MemoryStore
+   TachyonStore

在分析具体的Store实现之前,我们来看看BlockStore对外提供的接口有哪些?

    private[spark] abstract class BlockStore(val blockManager: BlockManager) extends Logging {
    
      def putBytes(blockId: BlockId, bytes: ByteBuffer, level: StorageLevel): PutResult
      
      def putIterator(
        blockId: BlockId,
        values: Iterator[Any],
        level: StorageLevel,
        returnValues: Boolean): PutResult
    
      def putArray(
        blockId: BlockId,
        values: Array[Any],
        level: StorageLevel,
        returnValues: Boolean): PutResult
        
      def getSize(blockId: BlockId): Long    
      def getBytes(blockId: BlockId): Option[ByteBuffer]    
      def getValues(blockId: BlockId): Option[Iterator[Any]]
      
      def remove(blockId: BlockId): Boolean    
      def contains(blockId: BlockId): Boolean    
      def clear() { }
    }

从上面的代码我们看到

+   每个Store都有一个blockManager对象,即Store是受BlockManger管理的
+   提供了Put/Get/Remove三个接口用于对Store中的"内容"进行操作;特别是PUT/GET,提供了两种接口,分别针对Bytes和Iterator数据(Array也是Iterator)提供两个接口

从"针对Bytes和Iterator数据(Array也是Iterator)提供两个接口"我们可以猜测,在BlockManager中管理的数据有两种类型,第一种比如部分TaskResultBlock生成Bytes类型;
另外一种就是Iterator类型,这种较为场景,比如RDDBlock肯定就是这种类型.

上面的猜测有道理,但是也不完出正确,上面我们谈到StorageLevel的_deserialized,即Block是否被序列化,如果一个数据是Iterator,但是它需要被序列化,那么该Block
在存储到Store里面之前,需要从Iterator序列化为Bytes,进而调用putBytes来进行存储.

Iterator到Bytes和Bytes到Iterator的序列化和反序列过程是由BlockManager的dataSerialize和dataDeserialize来实现的.具体的序列化过程这里就不深入分析了.

下面我们来具体分析每个Store的实现.

### DiskStore
DiskStore即基于文件来存储Block. 基于Disk来存储,首先必须要解决一个问题就是磁盘文件的管理:磁盘目录结构的组成,目录的清理等,在Spark对磁盘文件的管理是通过
DiskBlockManager来进行管理的,因此对DiskStore进行分析之前,首先必须对DiskBlockManager进行分析.

在Spark的配置信息中,通过"SPARK_LOCAL_DIRS"可以配置Spark运行过程中临时目录.有几点需要强调一下:

+   SPARK_LOCAL_DIRS配置的是集合,即可以配置多个LocalDir,用","分开;这个和Hadoop中的临时目录等一样,可以在多个磁盘中创建localdir,从而分散磁盘的读写压力
+   spark运行过程中生成的子文件过程不可估计,这样很容易就会出现一个localDir中子文件过多,导致读写效率很差,针对这个问题,Spark在每个LocalDir中创建了64个子目录,
来分散文件.具体的子目录个数,可以通过"spark.diskStore.subDirectories"进行配置.

现在问题来了,对于一个filename,我该写到哪个目录下面呢?DiskBlockManager通过hash来分别确定localDir以及subdir
    
    val hash = Utils.nonNegativeHash(filename)
    val dirId = hash % localDirs.length
    val subDirId = (hash / localDirs.length) % subDirsPerLocalDir

DiskBlockManager的核心工作就是这个,即提供  def getFile(filename: String): File 接口,根据filename确定一个文件的路径; 
剩下来的就是目录清理等工作;都比较简单这里就不进行详细分析

分析完DiskBlockManager,下面我们再来看看DiskStore的实现;

    override def putBytes(blockId: BlockId, _bytes: ByteBuffer, level: StorageLevel): PutResult = {
        val bytes = _bytes.duplicate()
        val startTime = System.currentTimeMillis
        val file = diskManager.getFile(blockId)
        val channel = new FileOutputStream(file).getChannel
        while (bytes.remaining > 0) {
          channel.write(bytes)
        }
        channel.close()
        val finishTime = System.currentTimeMillis
        PutResult(bytes.limit(), Right(bytes.duplicate()))
      }
      
对于最为简单的PutBytes接口,DiskStore通过BlockID为文件名称,通过diskManager来获取Block对应的文件,进而完成Block的写

    override def putIterator(blockId: BlockId,values: Iterator[Any],level: StorageLevel,
          returnValues: Boolean): PutResult = {
        val file = diskManager.getFile(blockId)
        val outputStream = new FileOutputStream(file)
        blockManager.dataSerializeStream(blockId, outputStream, values)
        PutResult(length, null)
      }

对于putIterator接口,DiskStore是通过BlockManager的dataSerializeStream接口,将Iterator序列化为Stream流并写到blockID对应的文件中.

对于get类的接口这里要说一下文件segment的概念,在Shuffle中要用到,一个BlockID对应一个文件是有些浪费,会造成很多小文件,影响读写性能;因此Spark提供了对文件segment
的支持,文件的segment即为文件一个区段,由offset和length组成,默认offset=0,length=filesize,即读取整个文件

    def getBytes(segment: FileSegment): Option[ByteBuffer] = {
        getBytes(segment.file, segment.offset, segment.length)
     }

DiskStore剩下就没有什么需要分析的

### MemoryStore
MemoryStore采用的JVM的heap内存进行Block存储;对于存储内存中,首先第一个需要解决的问题就是使用多少的内存用于Store存储?首先看几个配置:

+   spark.storage.memoryFraction:多少比例的JVM最大内存用于store存储,默认是0.6
+   spark.storage.safetyFraction:一个安全比例,即memoryFraction基础上做了一个缩小操作;

那么最大可以用于Store的内存大小就为:
    
     (Runtime.getRuntime.maxMemory * memoryFraction * safetyFraction).toLong

这个公式大家肯定在多处看到过,但是在spark 1.1针对Spark 的memory做了一个优化:
[Pass "cached" blocks directly to disk if memory is not large enough](https://issues.apache.org/jira/browse/SPARK-1777).
这里有一个关于Store内存不够进行rdd cache时候存在的问题进行解决.

简单描述一下这个过程:RDD的分片在进行cache,要进行unroll,即解压的过程(通过对rdd的分片进行迭代加载到内存中),但是在unroll之前,是无法确定需要的内存的大小,
从而导致OOM; spark 1.1针对这个问题,采用分步来进行尝试解压,当前有空闲内存就进行解压,否则就不进行解压,其实就是不cache;

+   spark.storage.unrollMemoryThreshold:默认值2M,初始化进行申请用于unroll的内存
+   memoryGrowthFactor=1.5然后解压过程中按照1.5去增量申请内存.
+   不过最大可以用于Unroll的内存大小为:spark.storage.unrollFraction默认为02.既MemoryStore可以使用的内存*0.2

具体可以参考上面的jira,有相应的设计文档.

继续MemoryStore的分析.确定了内存大小以后, 下面每个消息在MemoryStore是怎么存储的?

    private case class MemoryEntry(value: Any, size: Long, deserialized: Boolean)
    private val entries = new LinkedHashMap[BlockId, MemoryEntry](32, 0.75f, true)

即采用LinkedHashMap进行存储每个Block,Block的内容为MemoryEntry,它是一个Value的封装;

剩下了的PUT/GET/REMOVE接口都比较简单,序列化的知识和上面谈到DiskStore基本一致,仅仅多了一个内存大小的限制和Unroll的过程;

##BlockManager的服务结构
通过上面的BlockID, ManagedBuffer, BlockInfo, StorageLevel以及Store的分析,在单机层面上,我们已经对BlockManager有了一定的认识,但是Spark是一个主从结构,
BlockManager也在分散在每个executor. 比如在本地GetBlock的可能不在本地,而需要通过Remote的BlockManager进行交互进行该Block的内容;

一句话就是说,Spark中的BlockManager是主从式的分布结构;下面我们对BlockManager的分布结构进行分析;

### BlockManagerMaster服务
BlockManagerMaster服务取名为Master其实是一个挺迷糊的名称;虽然它是Master,但是该对象并不是BlockManager的分布式服务的Master节点;而只是对Master节点一个连接符,
通过该连接符,从而已可以和真正的Master节点进行通信;不管是在Driver还是在Executor上,都有一个BlockManagerMaster.

真正的Master节点是BlockManagerMasterActor这个对象;这个对象什么时候创建的呢?

参见SparkEvn初始化部分的代码,这里在Driver和Executor上创建BlockManagerMaster,
    
    val blockManagerMaster = new BlockManagerMaster(registerOrLookup(
          "BlockManagerMaster",
          new BlockManagerMasterActor(isLocal, conf, listenerBus)), conf, isDriver)
      
其中registerOrLookup在Spark中使用地方很多;含义很简单,如果当前在Driver那么register一个Actor的服务端并返回一个连接符,否则创建一个与服务端的连接符
 
 如果现在在Driver执行上,那么就会创建一个BlockManagerMasterActor对象,这个对象就是BlockManager主从分布式结构的Master节点,
 它维护了整个集群所有的Block和所有的 BlockManager从节点; 但是如果是在Executor上,就不会创建BlockManagerMasterActor,
 而只创建一个与主服务的连接,用于与主服务进行通信.
 
 下面我们对BlockManagerMasterActor进行详细分析;
 
    class BlockManagerMasterActor
      extends Actor with ActorLogReceive with Logging {    
      private val blockManagerInfo = new mutable.HashMap[BlockManagerId, BlockManagerInfo]
      private val blockManagerIdByExecutor = new mutable.HashMap[String, BlockManagerId]
      private val blockLocations = new JHashMap[BlockId, mutable.HashSet[BlockManagerId]

BlockManagerMasterActor最为重要的三个数据结构为blockManagerInfo,blockManagerIdByExecutor,blockLocations. 
它们维护了executor, blockID, blockManager, block之间的关联信息.

BlockManagerMasterActor的功能也很简单,就是围绕上面三个数据结构,处理来自BlockManagerSlave的消息并更新上面三个数据结构;
下面我们看看BlockManager的通信协议BlockManagerMessages.

    private[spark] object BlockManagerMessages {
      // Messages from the master to slaves.
      sealed trait ToBlockManagerSlave
    
      case class RemoveBlock(blockId: BlockId) extends ToBlockManagerSlave
      case class RemoveRdd(rddId: Int) extends ToBlockManagerSlave
      case class RemoveShuffle(shuffleId: Int) extends ToBlockManagerSlave
      case class RemoveBroadcast(broadcastId: Long, removeFromDriver: Boolean = true)    
    
      // Messages from slaves to the master.
      sealed trait ToBlockManagerMaster
    
      case class RegisterBlockManager(blockManagerId: BlockManagerId,maxMemSize: Long,sender: ActorRef)
        extends ToBlockManagerMaster    
      case class UpdateBlockInfo( var blockManagerId: BlockManagerId,var blockId: BlockId, var storageLevel: StorageLevel,
          var memSize: Long,var diskSize: Long,var tachyonSize: Long)extends ToBlockManagerMaster
      case class GetLocations(blockId: BlockId) extends ToBlockManagerMaster    
      case class GetLocationsMultipleBlockIds(blockIds: Array[BlockId]) extends ToBlockManagerMaster    
      case class GetPeers(blockManagerId: BlockManagerId) extends ToBlockManagerMaster    
      case class RemoveExecutor(execId: String) extends ToBlockManagerMaster    
      case object StopBlockManagerMaster extends ToBlockManagerMaster    
      case object GetMemoryStatus extends ToBlockManagerMaster    
      case object GetStorageStatus extends ToBlockManagerMaster    
      case class GetBlockStatus(blockId: BlockId, askSlaves: Boolean = true)
        extends ToBlockManagerMaster    
      case class GetMatchingBlockIds(filter: BlockId => Boolean, askSlaves: Boolean = true)
        extends ToBlockManagerMaster    
      case class BlockManagerHeartbeat(blockManagerId: BlockManagerId) extends ToBlockManagerMaster    
      case object ExpireDeadHosts extends ToBlockManagerMaster
    }
    
这些消息包主要分为两种类型,由Master发送给Slave的ToBlockManagerSlave和由Slave发送给Master的ToBlockManagerMaster;具体的含义都很直接,主要包括

+   Remove:由Master通知Slave移除Block
+   Slave与Master之间的同步:通过RegisterBlockManager向Master注册,通过UpdateBlockInfo向Master汇报当前BlockManager的状态信息,通过BlockManagerHeartbeat进行心跳
+   GetLocations:通过与Master进行通信,获取远程Block的location信息
+   Master提供各种查询入口.

最后,一句话:上面谈到了BlockManagerMasterActor这个只会在Driver上创建,但是不管是在Driver还是在Slave上都会创建BlockManagerMaster;
所以取名为BlockManagerMaster很模糊.

##BlockManagerSlaveActor
上面谈BlockManagerMasterActor说到,Slave可以通过BlockManagerMaster与Master进行通信,使用的消息包为: ToBlockManagerMaster;但是也提到Slave可以和Master进行
通信,即通过ToBlockManagerSlave,要进行通信,那么就必须创建一个Actor,即本节分析的BlockManagerSlaveActor.

它是在BlockManager中创建的,即每个BlockManager都是一个SlaveActor;同时BlockManager不管是在Driver还是Executor都会被创建,那么就是包括Driver在内所有的节点
都是BlockManager的从节点,这个还是不难理解.

SlaveActor就可以直接贴代码了,比较简单:

    override def receiveWithLogging = {
        case RemoveBlock(blockId) =>
          doAsync[Boolean]("removing block " + blockId, sender) {
            blockManager.removeBlock(blockId)
            true
          }    
        case RemoveRdd(rddId) =>
          doAsync[Int]("removing RDD " + rddId, sender) {
            blockManager.removeRdd(rddId)
          }    
        case RemoveShuffle(shuffleId) =>
          doAsync[Boolean]("removing shuffle " + shuffleId, sender) {
            if (mapOutputTracker != null) {
              mapOutputTracker.unregisterShuffle(shuffleId)
            }
            SparkEnv.get.shuffleManager.unregisterShuffle(shuffleId)
          }    
        case RemoveBroadcast(broadcastId, _) =>
          doAsync[Int]("removing broadcast " + broadcastId, sender) {
            blockManager.removeBroadcast(broadcastId, tellMaster = true)
          }    
        case GetBlockStatus(blockId, _) =>
          sender ! blockManager.getStatus(blockId)    
        case GetMatchingBlockIds(filter, _) =>
          sender ! blockManager.getMatchingBlockIds(filter)
      }
  
从上面我们可以看到,Slave接受Master的操作就两类:remove和getStatus;这里就要说一下不规范的问题了;

    case class GetBlockStatus(blockId: BlockId, askSlaves: Boolean = true)
        extends ToBlockManagerMaster
    case class GetMatchingBlockIds(filter: BlockId => Boolean, askSlaves: Boolean = true)
        extends ToBlockManagerMaster    
        
上面我们知道GetBlockStatus和GetMatchingBlockIds是继承ToBlockManagerMaster,但是这里把它当着ToBlockManagerSlave来处理,但是不会出错,只是不规范!!

到目前为止BlockManager的主从结构就挖出来了;BlockManagerMasterActor为真正的主, BlockManagerMaster为与主之间的通信连接符, BlockManagerSlaveActor从上的通信接口;

还剩下最后一个重点,就是真正的从的逻辑模块,即BlockManager对象,这里说是BlockManager对象; 本文全部都在讨论的是BlockManager框架;

##BlockManager对象
BlockManager对象是每个Block的slave对象,在每个Executor上都一个BlockManager对象,其中通过BlockManagerId进行标示
    
    class BlockManagerId private (
        private var executorId_ : String,
        private var host_ : String,
        private var port_ : Int)
 
每个BlockManager对象, 包括了与BlockManagerMasterActor保持连接的BlockManagerMaster,有提供给Master通信的BlockManagerSlaveActor;
同时还包含了

+   val blockInfo = new TimeStampedHashMap[BlockId, BlockInfo]:即本地BlockManager维护的所有Block信息
+   MemoryStore/DiskStore/TachyonStore/DiskBlockManager
+   metadataCleaner/broadcastCleaner:Block清理服务

同时BlockManager是继承自BlockDataManager,对外提供了getBlockData和putBlockData接口;

BlockManager对象内部每个函数的实现这里就不进行分析,无非就是和上面谈到的所有的对象进行交互!后面有时间再补充了!好累!!

