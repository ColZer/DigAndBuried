HBase 研究总结
=====

hbase需要研究的知识备忘

+   [MSLAB:A memstore-local allocation buffer](http://www.taobaotest.com/blogs/2310) 用来解决Memstore在flush到hfile以后
对内存造成碎片的问题.
+   [单RS上的region个数对性能的影响](http://hbase.apache.org/book/regions.arch.html) RS对region进行管理,但是单个RS的处理能力还是有限,
过多的region,会带来性能的问题.
+   [HBase HMaster Architecture](http://blog.zahoor.in/2012/08/hbase-hmaster-architecture/) HMaster在设计上还是比较轻量级别,
HBase集群可以在无Master的情况运行短时间,那么具体HMaster充当了什么功能,需要仔细研究.
+   [OpenTSDB的Scheme设计](http://opentsdb.net/docs/build/html/user_guide/backends/hbase.html) openTSDB在处理时间序列数据上有很大的优势,
可以进行一次仔细研究.
+   [Coprocessor的设计](https://blogs.apache.org/hbase/entry/coprocessor_introduction) 协处理器在RS充当了很重要的角色,
也是二级索引实现的一个主要途径
+   [Hbase scheme的设计总结](http://hbase.apache.org/book/schema.html),[2](https://communities.intel.com/community/itpeernetwork/datastack/blog/2013/11/10/discussion-on-designing-hbase-tables)
Hbase的性能严重依赖Scheme的设计,从rowkey的设计,TTL/版本的个数, HFile的blockSize的大小,BlockCache的选择.
+   [BlockCache的设计](http://hbase.apache.org/book/regionserver.arch.html)在做scan/get操作中,都会涉及到BlockCache的加载与清除,
对BlockCache的理解和优化,对hbase性能优化有很大的影响