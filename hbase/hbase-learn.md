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

+   [Hbase scheme的设计总结](http://hbase.apache.org/book/schema.html),[2](https://communities.intel.com/community/itpeernetwork/datastack/blog/2013/11/10/discussion-on-designing-hbase-tables)
Hbase的性能严重依赖Scheme的设计,从rowkey的设计,TTL/版本的个数, HFile的blockSize的大小,BlockCache的选择.

+   [OpenTSDB的Scheme设计](http://opentsdb.net/docs/build/html/user_guide/backends/hbase.html) openTSDB在处理时间序列数据上有很大的优势,
可以进行一次仔细研究.
+   [Coprocessor的设计](https://blogs.apache.org/hbase/entry/coprocessor_introduction) 协处理器在RS充当了很重要的角色,
也是二级索引实现的一个主要途径
+   [BlockCache的设计](http://hbase.apache.org/book/regionserver.arch.html)在做scan/get操作中,都会涉及到BlockCache的加载与清除,
对BlockCache的理解和优化,对hbase性能优化有很大的影响
+   [HBase HMaster Architecture](http://blog.zahoor.in/2012/08/hbase-hmaster-architecture/) HMaster在设计上还是比较轻量级别,
HBase集群可以在无Master的情况运行短时间,那么具体HMaster充当了什么功能,需要仔细研究.