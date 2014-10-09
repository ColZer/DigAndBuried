HBase 研究总结
=====

hbase需要研究的知识备忘

+   [MSLAB:A memstore-local allocation buffer](http://www.taobaotest.com/blogs/2310)   
Memstore功能是保存并索引所有临时的cell,每个cell的在物理内存层面上占用的内存是不连续的,此时如果对menstore进行flush操作,势必就会在内存中
清除这部分内存,后果就是造成内存碎片,Lab的功能就是预分配一块内存,将所有需要被menstore索引的 cell复制到这块内存中进行管理,从而可以实现对flush以后,
减小内存碎片.  
上述文章对MSLAB进行测试,从测试结果来看,优化效果不是特别明显.但是重要的是,LAB的内存是预分配的,默认2m,如果单RS上的region太多,
会造成内存占用过大的问题.而且默认的"hbase.hregion.memstore.mslab.enabled"是启用的
    
+   [单RS上的region个数对性能的影响](http://hbase.apache.org/book/regions.arch.html)  
RS以region为管理对象,每个region有自身store管理,在资源上,每个region都有一定的资源占用,因此RS对region管理也是有一定数量的限制,  
    1.  上述MSLAB是一个预分配的内存,在region中不含任何的数据时候,都会占用2M的内存,如果region过多,哪怕region不被read/write.对内存都是一个占用
    2.   memstore的flush受多个条件控制;RS上所有的region的memstore所占用的堆内存大小由"hbase.regionserver.global.memstore.size"进行设置.
    对于单个memstore,如果大小超过"hbase.hregion.memstore.flush.size",那么会被flush.
    对于整个RS,如果所有的region的memstore大小之和超过"hbase.regionserver.global.memstore.size.lower.limit",也会提前触发flush,
    提前粗放flush也会为后面的compact增加压力
    3.  RS的重启过程需要进行region的迁移,region数目也确定了迁移的效率.           
总之,RS的能力还是有限,根据官网的建议,100是最好的配置.

+   [Hbase scheme的设计总结](http://hbase.apache.org/book/schema.html),[资料二](https://communities.intel.com/community/itpeernetwork/datastack/blog/2013/11/10/discussion-on-designing-hbase-tables)
Hbase的性能严重依赖Scheme的设计,从rowkey的设计,TTL/版本的个数, HFile的blockSize的大小,BlockCache的选择.

+   [region split](http://zh.hortonworks.com/blog/apache-hbase-region-splitting-and-merging/)  

HBase的table的split可以通过pre-splitting,auto-splitting,forced-splitting三个过程来实现.    
pre-splitting为预先对region进行切割,可以在create table时指定splits或通过org.apache.hadoop.hbase.util.RegionSplitter工具进行分区

        //自创建cf=f1的test_table表,并使用 SplitAlgorithm. HexStringSplit算法进行pre-splitting,或UniformSplit算法 
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

我们可以同设置ConstantSizeRegionSplitPolicy和hbase.hregion.max.filesize足够大来关闭auto-splitting  
使用建议:一般情况下不需要预分配太多splits,让auto-splitting根据每个分区的大小来自动分配可能达到更好的平衡

forced-splitting:在shell里面可以使用split命令对table,region进行线上强制split.

+   [OpenTSDB的Scheme设计](http://opentsdb.net/docs/build/html/user_guide/backends/hbase.html) openTSDB在处理时间序列数据上有很大的优势,
可以进行一次仔细研究.
+   [Coprocessor的设计](https://blogs.apache.org/hbase/entry/coprocessor_introduction) 协处理器在RS充当了很重要的角色,
也是二级索引实现的一个主要途径
+   [BlockCache的设计](http://hbase.apache.org/book/regionserver.arch.html)在做scan/get操作中,都会涉及到BlockCache的加载与清除,
对BlockCache的理解和优化,对hbase性能优化有很大的影响
+   [HBase HMaster Architecture](http://blog.zahoor.in/2012/08/hbase-hmaster-architecture/) HMaster在设计上还是比较轻量级别,
HBase集群可以在无Master的情况运行短时间,那么具体HMaster充当了什么功能,需要仔细研究.