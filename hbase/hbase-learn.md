HBase 相关主题总结
=====

##   [MSLAB:A memstore-local allocation buffer](http://www.taobaotest.com/blogs/2310)   
Memstore功能是保存并索引所有临时的cell,每个cell的在物理内存层面上占用的内存是不连续的,此时如果对menstore进行flush操作,势必就会在内存中
清除这部分内存,后果就是造成内存碎片,Lab的功能就是预分配一块内存,将所有需要被menstore索引的 cell复制到这块内存中进行管理,从而可以实现对flush以后,
减小内存碎片.  
 
上述文章对MSLAB进行测试,从测试结果来看,优化效果不是特别明显.  
但是重要的是,LAB的内存是预分配的,默认2m,如果单RS上的region太多,会造成内存占用过大的问题.而且默认的"hbase.hregion.memstore.mslab.enabled"是启用的
    
##   [单RS上的region个数对性能的影响](http://hbase.apache.org/book/regions.arch.html)  
RS以region为管理对象,每个region有自身store管理,在资源上,每个region都有一定的资源占用,因此RS对region管理也是有一定数量的限制,  

+   上述MSLAB是一个预分配的内存,在region中不含任何的数据时候,都会占用2M的内存,如果region过多,哪怕region不被read/write.对内存都是一个占用
+   memstore的flush受多个条件控制;RS上所有的region的memstore所占用的堆内存大小由"hbase.regionserver.global.memstore.size"进行设置.
    对于单个memstore,如果大小超过"hbase.hregion.memstore.flush.size",那么会被flush.
    对于整个RS,如果所有的region的memstore大小之和超过"hbase.regionserver.global.memstore.size.lower.limit",也会提前触发flush,
    提前粗放flush也会为后面的compact增加压力
+   RS的重启过程需要进行region的迁移,region数目也确定了迁移的效率.           

总之,RS的能力还是有限,根据官网的建议,100是最好的配置.

##   [Hbase scheme的设计总结](http://hbase.apache.org/book/schema.html),[资料二](https://communities.intel.com/community/itpeernetwork/datastack/blog/2013/11/10/discussion-on-designing-hbase-tables)  

+   CF的设计,尽量保证只有一个CF.  
>由于HBASE的FLUSHING和压缩是基于REGION的,当一个列族所存储的数据达到FLUSHING阀值时该表的所有列族将同时进行FLASHING操作
>这将带来不必要的Ｉ／Ｏ开销。同时还要考虑到同意和一个表中不同列族所存储的记录数量的差别，即列族的势。
>当列族数量差别过大将会使包含记录数量较少的列族的数据分散在多个Region之上，而Region可能是分布是不同的RegionServer上。
>这样当进行查询等操作系统的效率会受到一定影响。  
>同时针对CF,rowKey是冗余存储的,在rowkey信息量很大的时候,多个CF对磁盘的浪费会很大.

+   最小化RowKey,CF,qualifiers的名称.  
>在HBase这三个东西都是按照字符进行对待的,在设计的时候尽量将int/float类型转换为byte进行存储,比如rowkey为userid=340827182,为一个4字节int,
>如果直接按照字符串进行编码,每个数字为一个字符,需要占用一个字节而进行byte编码可以控制它的大小.
>特别是CF/qualifiers完成可以采用单字节进行设置,务必不要和传统数据库一样设置很长的名字.  
>缺陷是进行byte编码会导致数据看起来不直观,

+   hbase返回的结果按照rowkey,CF,qualifiers,timestamp的次序进行排序,合理设置它们自己值来实现排序的功能.
+   在rowKey中加入散列的hex值来避免region的热点问题.针对时序的数据参考openTSDB的设计.
+   版本的个数:HBase在进行数据存储时，新数据不会直接覆盖旧的数据，而是进行追加操作，不同的数据通过时间戳进行区分.默认每行数据存储三个版本.
+   合理使用blockcache,对于blockcache的问题参考后面详细的学习.
+   合理使用最小版本和TTL控制cell的有效期,对于过期自动删除的数据尤其有用.    

>Time to live (TTL): This specifies the time after which a record is deleted and is by default set to forever. 
>This should be changed if you want HBase to delete rows after certain period of the time. 
>This is useful in the case when you store data in HBase which should be deleted after aggregation.  
>  
>Min Version: As we know HBase maintains multiple version of every record as and when they are inserted. 
>Min Version should always be used along with TTL. For example if the TTL is set to one year and there is no update on the row 
>for that period the row will be deleted. If you don’t want the row to be deleted you would have to set a min version for it such that only 
>the files below that version will be deleted.

##  [region 预先Split/自动Split/手动Split的学习](http://zh.hortonworks.com/blog/apache-hbase-region-splitting-and-merging/)   
HBase的table的split可以通过pre-splitting,auto-splitting,forced-splitting三个过程来实现.    
pre-splitting为预先对region进行切割,可以在create table时指定splits或通过org.apache.hadoop.hbase.util.RegionSplitter工具进行分区

        //自创建cf=f1的test_table表,并使用 SplitAlgorithm. HexStringSplit算法进行pre-splitting,
        //或UniformSplit算法 
        // -c 指定预先分区分区个数
        hbase org.apache.hadoop.hbase.util.RegionSplitter test_table HexStringSplit -c 10 -f f1
        
        //使用create 的SPLITS/SPLITSFILE属性进行设置
        create 'test_table', 'f1', SPLITS=['a', 'b', 'c']
        echo -e  "a\nb\nc" /tmp/splits
        create 'test_table', 'f1', SPLITSFILE=/tmp/splits'

auto-splitting是一个不受master参与的自动切割的过程."什么时候自动分区"以及"分区选择的中间点"由参数"hbase.regionserver.region.split.policy所配置算法来确定,
有ConstantSizeRegionSplitPolicy,IncreasingToUpperBoundRegionSplitPolicy,KeyPrefixRegionSplitPolicy等算法
具体算法参照原文描述:

>ConstantSizeRegionSplitPolicy is the default and only split policy for HBase versions before 0.94. 
It splits the regions when the total data size for one of the stores (corresponding to a column-family) in the region gets bigger than 
>configured “hbase.hregion.max.filesize”, which has a default value of 10GB. 
>
>IncreasingToUpperBoundRegionSplitPolicy is the default split policy for HBase 0.94, 
>which does more aggressive splitting based on the number of regions hosted in the same region server.
>The split policy uses the max store file size based on Min (R^2 * “hbase.hregion.memstore.flush.size”, “hbase.hregion.max.filesize”), 
>where R is the number of regions of the same table hosted on the same regionserver. 
>So for example, with the default memstore flush size of 128MB and the default max store size of 10GB, the first region on the region server will be split just after the first flush at 128MB. As number of regions hosted in the region server increases, it will use increasing split sizes: 512MB, 1152MB, 2GB, 3.2GB, 4.6GB, 6.2GB, etc. After reaching 9 regions, the split size will go beyond the configured “hbase.hregion.max.filesize”, at which point, 10GB split size will be used from then on. For both of these algorithms,
>regardless of when splitting occurs, the split point used is the rowkey that corresponds to the mid point in the “block index” for the largest store file in the largest store.
>
>KeyPrefixRegionSplitPolicy is a curious addition to the HBase arsenal. You can configure the length of the prefix for your row keys for grouping them, 
>and this split policy ensures that the regions are not split in the middle of a group of rows having the same prefix. If you have set prefixes for your keys, 
>then you can use this split policy to ensure that rows having the same rowkey prefix always end up in the same region. 
>This grouping of records is sometimes referred to as “Entity Groups” or “Row Groups”. 
>This is a key feature when considering use of the “local transactions” (alternative link) feature in your application design.   

auto-splitting过程中不会直接进行文件的分割,而是创造两个ref来指向parent_region,在两个子region在后面需要进行compactions,才对原始数据进行rewrite. 
一个region在将ref进行rewrite之前,是不允许再次进行split.  
所以auto-splitting的耗时是可以接受的.
    
如果实在不接受auto-splitting所带来的性能问题,可以设置ConstantSizeRegionSplitPolicy和hbase.hregion.max.filesize足够大来关闭auto-splitting  
使用建议:一般情况下不需要预分配太多splits,让auto-splitting根据每个分区的大小来自动分配可能达到更好的平衡  

forced-splitting:在shell里面可以使用split命令对table,region进行线上强制split.

##    [OpenTSDB的Scheme设计](http://opentsdb.net/docs/build/html/user_guide/backends/hbase.html)   
OpenTSDB基于HBase实现对metric数据进行存储和查询.在详细了解实现原理之前先来看看什么是metric数据.

对metric的认识可以有两个视角:

+   视角1:站在metric统计项的角度来看,metric数据即为一个特定统计项在时间流中每个时间点对应的统计值组成的序列集合.  
首先每个统计项由metricValue表示一个时间点的统计值.其次由于统计项需要针对不同的实体/属性进行区分,每个统计项含有一个metricTag属性,它是一个KV集,
表示当前统计项的属性集,
比如针对metric=free-memory的统计项,那么metrixTag=[{'node':'bj'},{'rack':'01'},{'host':'bj-1'}].代表统计的bj节点下01机架上的bj-1机器的free-memory
如果修改host=bj-2,那么这两个统计项目就是针对不同的实体进行统计.
+   视角2:站在统计实体的角度来看.比如上述的"bj节点下01机架上的bj-1机器"就是一个统计实体,那么在一个时间点下,该实体上所有的统计项目和统计值就组成一个
metric集合,比如上述的实体对应的统计值就为metricValue=[{"cpu idle":20},{"free-memory":40}]

OpenTSDB是站在视角1来对metric进行处理.因此metricName+metricTag+timestamp和metricValue组成metric统计项目和统计值,在一个时间序列下,就组成统计值序列TS

总结OpenTSDB的HBase的scheme设计:

+   DataTable/RowKey的设计亮点:
    -    由metricName+timestamp+metricTag 次序组成的key:<metric_uid><timestamp><tagk1><tagv1><tagkN><tagvN>,
    将timestamp放到metricTag前面利于针对metricTag的不同进行对比
    -   timestamp存储为小时级别的时间戳,将小时级别以下时间的统计值作为CF的一个Column Qualifiers进行表示,这样有两个好处:一是减少记录行数,二十提高查询吞吐量,
    针对metric类型的统计数据,很少会按分钟级别单条去获取,而是一次按照小时级别获取回来进行绘制统计图.
    -   tag值kv对按照key的字符序进行排列,从而可以通过设计key值来提高特定tag的优先级,从而实现针对特定tag进行查询的优化.
    
+   DataTable/Columns的设计亮点:
    -   单CF的设计,受HBase的实现原理,单CF是最优化的设计.优点参考上面
    -  rowKey按照小时级别进行存储,而小时级别以下按照时间偏移量存储为qualifiers,
    针对秒级别和毫秒级别的偏移量,qualifiers分为2字节和4字节两种类型进行区别,其中2字节划分前12位来存储时间偏移(最大表示4095s),
    4字节划分22位(最大表示4194303毫秒)来存储毫秒偏移 ,针对4字节,开头4位用于hex存储,22位存储毫秒偏移,2位保留.  
   -     对于2字节和4字节两种类型都保留最后的4位用于存储columns值的类型(type)信息.
    该4位中,第一位为0/1来表示值为int/float,后面三位分别取值为000/010/011/100分别表示值的大小为1/2/4/8字节.
    -   value的存储严格按照1/2/4/8字节大小进行存储,从qualifiers中可以获取值的大小,从而可以最小化存储value.
    
+   上述的qualifiers和value都是针对int/float类型进行描述,TSDB还支持存储object类型.  
    -   上述的qualifiers为2/4字节,针对object类型,qualifiers使用3/5字节进行存储,第1字节为01,后面2/4字节直接存储秒和毫秒级别的偏移量.
    不存储type信息,那么存储时间偏移量的2/4字节高位肯定是0,这样好处:010开头的qualifiers在进行查询时候肯定是排在行首.
    -   object value是按照UTF-8编码的JSON对象
    这种类型可以用来存储聚合信息,比如t:01012={sum:100,avg:20,min:10,max:25}存储10分钟的聚合值.
    
+    DataTable的qualifiers/value聚合的设计亮点:     
    -   在写入TSDB时候,每个偏移量是作为一个单独的qualifiers进行存储,这样方便写入,但是不适合查询,因为查询会针对每个qualifiers作为一行返回.    
    因此TSDB针对数据进行一次聚合(Compactions,和hbase内部的Compactions不是同一个意思).    
    -   TSDB合并很简单,qualifiers大小是固定的,value的大小可以从qualifiers中获取,因此可以直接连接起来就可以.
    合并发生的时间是当前行已经过去了一个小时,或者读取未合并的行(如果合并以后再次写入,可以再次合并)
    
+    tsdb-uid Table和UID的设计:
    -   做了太多用户产品,第一映像就把UID理解为用户ID,它本身是Unique ID,在TSDB里面,matrixName,tag-key,tag-value都映射为UID,
    进而可以进一步编码到rowkey中.UID默认是一个3字节的无符号数目
    -   利于tsdb-uid表存储UID和stringName(stringName类型有matrixName,tag-key,tag-value)之间的"双向"映射;
    -   stringName到UID的映射:RowKey=stringName,CF=id,qualifiers=上述stringName一种,value=UID.
    -   UID到stringName的映射:RowKey=UID,CF=name,qualifiers=上述stringName一种,value=stringName
    -   UID需要保证唯一信息,tsdb-uid表的第一行,rowKey=\x00,CF=id,qualifiers=上述stringName一种,value=8字节的递增int
    每次分配一个新的UID,就递增指定qualifiers的value值.

##   [Coprocessor的设计](https://blogs.apache.org/hbase/entry/coprocessor_introduction)   
二级索引的设计和实现是现在HBase应用中一个很重要的环节.最近使用phoenix来支持hbase的sql操作和二级索引,那么二级索引在hbase里面是什么实现的?  
先看一个phoenix创建的表的scheme

> {NAME => 'WEB_STAT', 
>coprocessor$5 => '|org.apache.phoenix.hbase.index.Indexer|1073741823|',   
>coprocessor$4 => '|org.apache.phoenix.coprocessor.ServerCachingEndpointImpl|1|',   
>coprocessor$3 => '|org.apache.phoenix.coprocessor.GroupedAggregateRegionObserver|1|',   
>coprocessor$2 => '|org.apache.phoenix.coprocessor.UngroupedAggregateRegionObserver|1|',   
>coprocessor$1 => '|org.apache.phoenix.coprocessor.ScanRegionObserver|1|',   
>FAMILIES =} 

结论:二级索引的实现是基于coprocessor来实现的.

coprocessor是HBase中一个功能插件系统,它由observer和endpoint两部分组成,这两部分其实就相对于传统关系数据库中触发器和存储过程.

Observer相当于触发器,可以监听hbase内部相应的事件,并进行处理.根据监听的对象不同,Hbase内部有Master/Region/WAL三种类型的Observer.
其中Master可以监听Table和Region的DDL操作,Region可以监听GET/PUT/SCAN/DELETE/OPEN/FLUSH,WAL可以监听write的操作.  
在HBase内部,每个监听对象维持了一个Observer链,如上图的表就有多个coprocessor,根据优先级来确定执行次序.  

Endpoint相对于存储过程,可以被定义并加载在HBase内部,本质上,它是一个动态RPC,从而可以实现在region内部进行数据处理,  
减少通过scan操作把数据拉取到客户端进行处理的代价.
Endpoint的执行过程相对于一个mapreduce过程,client将一个table指定的endpoint操作map到每个region上进行处理,并获取每个region的处理结果进行reduce合并操作.

回到上面的二级索引的问题,所谓的二级索引其实就是实现一个Observer,并绑定到table上, 
从而可以捕获表的get/put/scan操作,写的时候写一次索引,读的时候先去查索引,根据索引的结果来真正查数据.

##   [BlockCache的设计](http://hbase.apache.org/book/regionserver.arch.html)  
在做scan/get操作中,都会涉及到BlockCache的加载与清除,对BlockCache的理解和优化,对hbase性能优化有很大的影响

##   [HBase HMaster Architecture](http://blog.zahoor.in/2012/08/hbase-hmaster-architecture/)   
HMaster在设计上还是比较轻量级别,HBase集群可以在无Master的情况运行短时间,那么具体HMaster充当了什么功能,需要仔细研究.