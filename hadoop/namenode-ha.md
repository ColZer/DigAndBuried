# NameNode HA解析


HA即高可用性，Namenode维护了整个HDFS集群文件的Meta信息，早期设计它为单点结构，而且功能较重，无法做到快速重启。在后续迭代过程中，通过提供SecondNamenode和BackupNamenode的功能支持，可以实现对NamnodeMeta的备份和恢复的功能，但是它们无法做到线上故障的快速恢复，需要人工的介入，即它只是保证了整个HDFS集群Meta信息的不丢失和数据的完整性，而不能保证集群的可用性。

Namespace是NameNode管理Meta信息的基本单位，通过NamespaceID进行唯一标示。在HA的架构下，对于一个Namespace的管理可以由多个Namenode实例进行管理，每一个NameNode实例被称为一个NamespaceServiceID。但是在任何时刻只有一个Namenode实例为Active，其他为Standby，只有处于Active状态的节点才可以接受Client的操作请求，而其他Standby节点则通过EditLog与Active节点保持同步。

同时我们知道，NameNode对Block的维护是不会持久化Block所在的DataNode列表，而是实时接受DataNode对Block的Report。在HA架构中，NameNode之间不会对Block的Report进行同步，而是依赖DataNode主动对每一个NameNode进行Report，即对于一个NameSpace下的BlockPool的管理，每一个DataNode需要明确知道有哪些NamespaceServiceID参与，并主动向其上报Block的信息。

因此在任何时刻，这一组NameNode都是处于热状态，随时都可以从Standby状态升级为Active从而为Client提供服务。

对于状态迁移，HA架构基于Zookeep提供一个轻量级的主竞选和Failover的功能，即通过一个锁节点，谁拿到这个节点的写权限，即为Active，而其他则降级为Standby，并监听该锁节点的状态变化，只要当前Active释放了写权限，所有Standby即立即竞选成为Active，并对外提供服务。

##1. Image与EditLog
NameNode维护了一个Namespace的所有信息，如Namespace中包括的文件列表以及ACL权限信息，而Image即为内存中Namespace序列化到磁盘中的文件。和Mysql之类的存储不同，对Namespace的操作不会直接将其他操作后的结果写入到磁盘中Image文件中，而只是做内存数据结构的修改，并通过写EditLog的方式将操作写入日志文件。而只会在特定的情况下将内存中Namespace信息checkpoint到磁盘的Image文件中。

为什么不直接写入Image而转写EditLog文件呢？主要和两个文件格式设计有关系，其中Image文件设计较为紧凑，文件大小只会受NameSpace文件目录个数影响，而与操作次数无关，同时它的设计主要是为了易读取而不宜修改，比如删除一个文件，无法直接对Image文件中的一部分进行修改，需要重写整个Image文件。而EditLog为追加写类型的文件，它记录了对Namespace每一个操作，EditLog文件个数和大小与操作次数直接相关，在频繁的操作下，EditLog文件大小会很大。受此限制，NameNode提供了一定checkpoint机制，可以定时刷新内存中NameSpace信息到Image文件中，或者将老的Image文件与EditLog进行合并，生成一个新的Image文件，进而可以将过期的EditLog文件进行删除，减小对NameNode磁盘的浪费。


另外，在HA架构中，EditLog提供了Active与Standby之间的同步功能，即通过将EditLog重写到Standy节点中，下面开始一一分析它的实现原理。

### 1.1 初始化
`dfs.namenode.name.dir`和`dfs.namenode.edits.dir`为NameNode存储Image和EditLog文件目录。在HDFS集群安装过程中，需要进行Format操作，即初始格式化这两个目录，主要有以下几个操作：

1. 初始化一个Namespace，即随机分配一个NamespaceID，上面我们谈到Active节点和Standby节点是同时为一个NamespaceID服务，因此我们肯定的是：Format操作只会发生在Active节点上，Standby节点是不需要Format节点。
2. 将NameSpace信息写入到VERSION文件，并初始化seen_txid文件内容为0。

		namespaceID=972780058
		clusterID=yundata
		cTime=0
		storageType=NAME_NODE
		blockpoolID=BP-102718850-10.54.38.52-1459226879449
		layoutVersion=-63

> 关于ClusterID的补充：hdfs namenode -format [-clusterid cid ]。 format操作有一个可选的clusterid参数，默认不提供的情况下，即随机生成一个clusterid。
> 那么为什么会有Cluster概念呢？它和Namespace什么关系？正常情况，我们说一个hdfs集群（cluster），它由一组NameNode维护一个Namespace和一组DataNode而组成。即Cluster和NameSpace是一一对应的。
> 但是在支持Federation功能的集群下，一个Cluster可以由多个NameSpace，即多组Namenode，它主要是解决集群过大，NameNode内存压力过大的情况下，可以对NameNode进行物理性质的划分。
> 你可能会问？那为什么不直接搞多套HDFS集群呢？在没有Federation功能情况下，一般都是这样做的，毕竟NameNode的内存是有限的。但是搞多套HDFS集群对外就由多个入口，而Federation可以保证对外只有一个入口。

3. 将当前初始化以后Image文件（只包含一个根目录/）写入到磁盘文件中，并初始化一个Edit文件接受后面的操作日志写。
4. 格式化其他非FileEditLog（即提供分享功能的EditLog目录。）

### 1.2 基于QJM的EditLog分享存储
`dfs.namenode.name.dir`和`dfs.namenode.edits.dir`只是提供本地NameNode对Image和EditLog文件写的路径，而在HA架构中，NameNode之间是需要进行EditLog的同步，此时需要一个"shared storage "来存储EditLog，HDFS提供了NFS和"Quorum Journal Manager"两套解决方案，这里只会对"Quorum Journal Manager"进行分析。

如格式化的第四步的描述，需要对非FileEditLog进行格式化，而这里QJM即为非FileEditLog之一。

对于HDFS来说，QJM与Zookeeper性质接近，都是是一个独立的共享式服务，也由2N+1个Journal节点组成，为NameNode提供了EditLogOutputStream和EditLogInputStream读写接口。关于读写它由以下几个特点：

1. 对于2N＋1个节点组成的QJM集群，在写的情况下，不需要保证所有节点都写成功，只需要其中N＋1节点（超过1/2）写成功就表示写成功。这也是它为什么叫着Quorum的原因了，即它是基于Paxos算法。
2. 在读的情况下，它提供了组合接口，即可以将所有Journal节点上的EditLog按照txid进行排序，组合出一个有效的列表，提供NameNode进行读。
3. 写Journal失败对于NameNode是致命的，NameNode会直接退出。

QJM是一个较为轻量的集群，运维也比较简单，挂了直接起起来就可以，只要不超过N个节节点挂了，集群都可以正常功能。

### 1.3 EditLog Roll的实现

### 1.4 Image Checkpoint的实现










