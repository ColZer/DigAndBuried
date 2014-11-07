HBase Filter学习
==============
Hbase中针对GET和SCAN操作提供了filter（过滤器）的功能，从而可以实现row过滤和column过滤，在最近项目中正好要大量使用Filter来进行查询，
下面我们从api的层面对Hbase的filter进行整理。

重要:Filter是一个名词.站在应用的角度来看,Filter是过滤出我们想要的数据.但是站在HBase源码的角度,filter是指一条记录是否被过滤.参考下面的例子:
    
    public boolean filterRowKey(byte[] buffer, int offset, int length) throws IOException {
        return false;
    }
    
filterRowKey是对RowKey进行过滤,如果没有被过滤,是返回false,即INCLUDE在返回列表中,而true则理解为被过滤掉,即EXCLUDE.  

这点不同在理解Filter的实现很重要. 下面我们来开始看Filter的实现.

## Filter的实现

    public abstract class Filter {
        abstract public boolean filterRowKey(byte[] buffer, int offset, int length) throws IOException;
        abstract public boolean filterAllRemaining() throws IOException;
        abstract public ReturnCode filterKeyValue(final Cell v) throws IOException;
        abstract public Cell transformCell(final Cell v) throws IOException;
        abstract public void filterRowCells(List<Cell> kvs) throws IOException;
        abstract public boolean filterRow() throws IOException;
    }

Filter是所有Filter的基类,针对RowKey,Cell都提供了过滤的功能,现在一个问题来了,对于上面那么多过滤接口,在针对一个Row过滤,这些接口调用次序是
什么样呢?下面我就按照调用顺序来解释每个接口的功能.

+   filterRowKey:对rowKey进行过滤,如果rowKey被过滤了,那么后面的那些操作都不需要进行了
+   针对Row中cell进行过滤,由于一个row含有多个cell,因此这是一个循环过程
    +   filterAllRemaining:是否需要结束对这条记录的filter操作
    +   filterKeyValue:对每个cell进行过滤
    +   transformCell:如果一个cell通过过滤,我们可以对过滤后的cell进行改写/转变
+   filterRowCells:对通过cell过滤后的所有cell列表进行修改
+   filterRow:站在row整体角度来进行过滤

Filter在HBase里面有两大类,一种是集合Filter,一种是单个Filter;下面我们一一进行分析.

##FilterList
FilterList是一个Filter的集合,所谓集合Filter其实就是提供Filter或/Filter集合之间的And和Or组合.每个FilterList由两部分组成:

+   一组Filter子类组成的Filter集合:这个Filter可以是FilterList也可以是基础Filter,如果是FilterList那么就相当形成了一个树形的过滤器

        private List<Filter> filters = new ArrayList<Filter>();
        
+   一组Filter之间的关联关系,

        public static enum Operator {
            /** !AND */
            MUST_PASS_ALL,
            /** !OR */
            MUST_PASS_ONE
          }

其他的操作这里就不描述了,本质上就是维护一组Filter之间的逻辑关系而已;

## 基础Filter

在HBase中,所有基础Filter都继承自FilterBase类,该类提供了所有Filter默认实现,即"不被过滤".比如filterRowKey默认返回false.下面是
所有实现FilterBase的子Filter,针对几个特定的Filter下面进行分析.

+   org.apache.hadoop.hbase.filter.FilterBase
    +   org.apache.hadoop.hbase.filter.CompareFilter
        +   org.apache.hadoop.hbase.filter.DependentColumnFilter
        +   org.apache.hadoop.hbase.filter.FamilyFilter
        +   org.apache.hadoop.hbase.filter.QualifierFilter
        +   org.apache.hadoop.hbase.filter.RowFilter
        +   org.apache.hadoop.hbase.filter.ValueFilter
    +   org.apache.hadoop.hbase.filter.ColumnCountGetFilter
    +   org.apache.hadoop.hbase.filter.ColumnPaginationFilter
    +   org.apache.hadoop.hbase.filter.ColumnPrefixFilter
    +   org.apache.hadoop.hbase.filter.ColumnRangeFilter
    +   org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter
    +   org.apache.hadoop.hbase.filter.FirstKeyValueMatchingQualifiersFilter
    +   org.apache.hadoop.hbase.filter.FuzzyRowFilter
    +   org.apache.hadoop.hbase.filter.InclusiveStopFilter
    +   org.apache.hadoop.hbase.filter.KeyOnlyFilter
    +   org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter
    +   org.apache.hadoop.hbase.filter.PageFilter
    +   org.apache.hadoop.hbase.filter.PrefixFilter
    +   org.apache.hadoop.hbase.filter.RandomRowFilter
    +   org.apache.hadoop.hbase.filter.SingleColumnValueFilter
    +   org.apache.hadoop.hbase.filter.SingleColumnValueExcludeFilter
    +   org.apache.hadoop.hbase.filter.SkipFilter
    +   org.apache.hadoop.hbase.filter.TimestampsFilter
    +   org.apache.hadoop.hbase.filter.WhileMatchFilter
    
###CompareFilter:比较器过滤器

CompareFilter是最常用的一组Filter,它提供了针对RowKey,Family,Qualifier,Column,Value的过滤,首先它是"比较过滤器",HBase针对比较提供了
CompareOp和一个可被比较的对象ByteArrayComparable.

其中CompareOP抽象了关系比较符,

    public enum CompareOp {
        /** less than */
        LESS,
        /** less than or equal to */
        LESS_OR_EQUAL,
        /** equals */
        EQUAL,
        /** not equal */
        NOT_EQUAL,
        /** greater than or equal to */
        GREATER_OR_EQUAL,
        /** greater than */
        GREATER,
        /** no operation */
        NO_OP,
      }

而ByteArrayComparable提供了可以参考的比较对象,比如过滤掉Value为Null
    
    new ValueFilter(CompareOp.NOT_EQUAL, new NullComparator());

目前支持的ByteArrayComparable有:

+   org.apache.hadoop.hbase.filter.ByteArrayComparable
    +   org.apache.hadoop.hbase.filter.BinaryComparator:对两个字节数组做Bytes.compareTo比较.
    +   org.apache.hadoop.hbase.filter.BinaryPrefixComparator:和BinaryComparator基本一直,但是考虑到参考值和被比较值之间的长度
    +   org.apache.hadoop.hbase.filter.BitComparator:位计算比较器,通过与一个byte数组做AND/OR/XOR操作,判读结果是否为0
    +   org.apache.hadoop.hbase.filter.NullComparator: 是否为空比较
    +   org.apache.hadoop.hbase.filter.RegexStringComparator: 正则比较,即是否符合指定的正则
    +   org.apache.hadoop.hbase.filter.SubstringComparator: 子字符串比较
    
从上面的描述我们可以看到BitComparator/NullComparator/RegexStringComparator/SubstringComparator只返回0或者1,即一般只会在上面进行
EQUAL和NOT_EQUAL的CompareOp操作.而BinaryComparator/BinaryPrefixComparator存在-1,0,1,可以进行LESS和GREATER等比较.

CompareFilter下面的五个比较器是提供了对Row多个层面进行filter,分别描述如下:

+   org.apache.hadoop.hbase.filter.FamilyFilter:对Family进行过滤
+   org.apache.hadoop.hbase.filter.QualifierFilter:对Qualifier进行过滤
+   org.apache.hadoop.hbase.filter.RowFilter:对RowKey进行过滤
+   org.apache.hadoop.hbase.filter.ValueFilter:对所有Cell的value进行过滤,没有区分是哪个Column的值,是针对所有Column
+   org.apache.hadoop.hbase.filter.DependentColumnFilter

其中RowFilter是提供filterRowKey实现,而QualifierFilter/RowFilter/ValueFilter/DependentColumnFilter都只针对filterKeyValue进行实现.

实现和使用都很简单,就不摊开的说了.

###org.apache.hadoop.hbase.filter.KeyOnlyFilter
为什么要挑KeyOnlyFilter出来讲呢?在上面我们谈到Filter的实现中有一个Cell transformCell(final Cell v)的操作,该操作不是过滤而是在过滤以后,
对Cell进行改写,KeyOnlyFilter就是对transformCell的一个实现.

    public class KeyOnlyFilter extends FilterBase {    
      boolean lenAsVal;
      public Cell transformCell(Cell kv) {
        KeyValue v = KeyValueUtil.ensureKeyValue(kv);    
        return v.createKeyOnly(this.lenAsVal);
      }
KeyOnlyFilter有一个参数lenAsVal,值为true和false. KeyOnlyFilter的作用就是将scan过滤后的cell value设置为null,或者设置成原先value的大小(lenAsVal设置为true).

这个Filter很好的诠释了transformCell的功能,还有一个用处获取数据的meta信息用于展现.

### org.apache.hadoop.hbase.filter.WhileMatchFilter
上面讨论到KeyOnlyFilter是对transformCell功能的诠释,而这里我们要讲的WhileMatchFilter是对filterAllRemaining进行诠释.

filterAllRemaining在Scan过程中,是一个前置判读,它确定了是否结束对当前记录的scan操作,即如果filterAllRemaining返回true
那么当前row的scan操作就结束了
    
     public MatchCode match(Cell cell) throws IOException {
        if (filter != null && filter.filterAllRemaining()) {
          return MatchCode.DONE_SCAN;
        }
        int ret = this.rowComparator.compareRows(row, this.rowOffset, this.rowLength,
            cell.getRowArray(), cell.getRowOffset(), cell.getRowLength());
        ...

在进行match操作时候,先判读filterAllRemaining是否为true,如果为true,那么就不需要进行后面到compareRows操作,直接返回DONE_SCAN.

这里我们谈到的WhileMatchFilter它是一个wrapped filter,含义直译为一旦匹配到就直接过滤掉后面的记录,即结束scan操作. 
它内部通过包装了一个filter,对于外面的WhileMatchFilter,它的假设只要被包装的filter的filterRowKey, filterKeyValue,filterRow,filterAllRemaining
任何一个filter返回为true,那么filterAllRemaining就会true. 

它还有其他功能吗?没有!

###org.apache.hadoop.hbase.filter.SkipFilter
上面讨论的WhileMatchFilter仅仅影响filterAllRemaining功能,这里讨论的SkipFilter它是影响filter过程中最后的环节,即:filterRow.

filterRow是对Row是否进行过滤最后一个环节.本质上,它是一个逻辑判断,并不一定是对rowkey,value指定指定对象进行filter.

SkipFilter和WhileMatchFilter一样,也是一个wrapped filter,它的功能是如果内部filter任何一个filterKeyValue返回true,那么filterRow就直接返回true,
即任何一个cell被过滤,那么整条row就被过滤了.所以含义为跳过记录,只有任何一个cell被过滤.

还有其他的功能吗?没有!!

###org.apache.hadoop.hbase.filter.SingleColumnValueFilter
到目前为止,我们讨论的filter包括CompareFilter在内,都无法针对如下场景进行过滤:对于一条row,如果指定的colume的值满足指定逻辑,那么该
row就会被提取出来. 上面讨论到ValueFilter是针对所有的所有的column的value进行过滤,而不是特定的column.

而这里讨论的SingleColumnValueFilter就是实现这个功能:
    
    public class SingleColumnValueFilter extends FilterBase {    
      protected byte [] columnFamily;
      protected byte [] columnQualifier;
      protected CompareOp compareOp;
      protected ByteArrayComparable comparator;
  
和CompareFilter不同,SingleColumnValueFilter需要提供Family和Qualifier.而且在进行filterKeyValue过程中,如果已经找到满足指定filter的cell,
那么后面cell的filterKeyValue都会返回Include

### org.apache.hadoop.hbase.filter.ColumnPrefixFilter
上面讨论SingleColumnValueFilter解决ValueFilter不能指定column的问题,这里讨论的ColumnPrefixFilter是解决QualifierFilter只能做大小比较,
或者使用SubstringComparator进行子字符串匹配.这里讨论的ColumnPrefixFilter是要求Column必须满足指定的prefix前缀

prefix和substring是有区别的,这个可以理解,但是ColumnPrefixFilter是可以从使用RegexStringComparator的QualifierFilter来实现,

所以在本质上来说,ColumnPrefixFilter没有什么特殊的.
和ColumnPrefixFilter相似的两个filter:

+   org.apache.hadoop.hbase.filter.MultipleColumnPrefixFilter,它可以指定多个prefix.本质上也可以用regex来实现
+   org.apache.hadoop.hbase.filter.ColumnRangeFilter,支持对Column进行前缀区间匹配,比如匹配column的列名处于[a,e]之间


###org.apache.hadoop.hbase.filter.PageFilter
这个也是一个很重要的PageFilter,它用于分页,即限制scan返回的row行数.它怎么实现的呢?其实很简单

上面我们讨论到filterRow是对当前row进行filter的最后一个环节,如果一个row通过了filterRowKey, filterKeyValue等过滤,此时FilterRow将可以最终确定
该row是否可以被接受或者被过滤.

    public class PageFilter extends FilterBase {
      private long pageSize = Long.MAX_VALUE;
      private int rowsAccepted = 0;
      
      public PageFilter(final long pageSize) {
        this.pageSize = pageSize;
      }
      public ReturnCode filterKeyValue(Cell ignored) throws IOException {
        return ReturnCode.INCLUDE;
      }
      public boolean filterAllRemaining() {
        return this.rowsAccepted >= this.pageSize;
      }
      public boolean filterRow() {
        this.rowsAccepted++;
        return this.rowsAccepted > this.pageSize;
      }

每个PageFilter都有一个pageSize,即表示页大小.在进行filterRow时,对已经返回的行数进行+1,即this.rowsAccepted++. filterAllRemaining操作用于
结束scan操作.

和传统的sql不一样,PageFilter没有start和limit,start的功能需要使用Scan.setStartRow(byte[] startRow)来进行设置

### org.apache.hadoop.hbase.filter.ColumnCountGetFilter
该filter不适合Scan使用,仅仅适用于GET

上述讨论的PageFilter是针对row进行分页,但是在get一条列很多的row时候,需要ColumnCountGetFilter来limit返回的列的数目.

    public class ColumnCountGetFilter extends FilterBase {
      private int limit = 0;
      private int count = 0;
      public ColumnCountGetFilter(final int n) {
        this.limit = n;
      }
      public boolean filterAllRemaining() {
        return this.count > this.limit;
      }
      public ReturnCode filterKeyValue(Cell v) {
        this.count++;
        return filterAllRemaining() ? ReturnCode.NEXT_COL : ReturnCode.INCLUDE_AND_NEXT_COL;
      }

和PageFilter一样,该Filter没有start,可以通过Get.setRowOffsetPerColumnFamily(int offset)进行设置

### org.apache.hadoop.hbase.filter.ColumnPaginationFilter
上面谈到的ColumnCountGetFilter只适合GET,而且不能指定start

这里我们谈到的ColumnPaginationFilter就是解决这个问题;

    public class ColumnPaginationFilter extends FilterBase
    {
      private int limit = 0;
      private int offset = -1;      
      private byte[] columnOffset = null;
      public ColumnPaginationFilter(final int limit, final int offset)
      {
        this.limit = limit;
        this.offset = offset;
      }

它包含limit和offset两个变量用来表示每个row的返回offset-limit之间的列;其中offset可以通过指定的column来指定,即columnOffset

从实现角度来看,它就是基于filterKeyValue的NEXT_ROW,NEXT_COL,INCLUDE_AND_NEXT_COL三个返回值来影响column的filter来实现

    public ReturnCode filterKeyValue(Cell v)
    {
          if (count >= offset + limit) {
            return ReturnCode.NEXT_ROW;
          }    
          ReturnCode code = count < offset ? ReturnCode.NEXT_COL :
                                             ReturnCode.INCLUDE_AND_NEXT_COL;
          count++;
          return code;
      }
  
org.apache.hadoop.hbase.filter.FirstKeyOnlyFilter是一个极端的limit和offset,即offset=0,limit=1,即每个row只返回第一个column记录

###还有最后几个比较简单的filter

+   org.apache.hadoop.hbase.filter.TimestampsFilter:通过指定一个timestamp列表,所有不在列表中的column都会被过滤
+   org.apache.hadoop.hbase.filter.RandomRowFilter:每行是否被过滤是按照一定随机概率的,概率通过一个float进行指定
