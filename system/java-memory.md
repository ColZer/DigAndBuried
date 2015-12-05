# 进程分析之JAVA内存

在[《进程分析之内存》](./system/memory.md)中，分析怎么样对系统或进程的内存使用情况进行分析；本节专门针对JAVA进程进行分析，JAVA是一种虚拟机形式的进程模块，对于虚拟机JAVA进程可以使用上面的工具进行分析，但是对于虚拟机内部运行的业务代码需要通过JAVA提供的工具进行进一步和分析;

###JAVA虚拟机数据分类
在普通的应用程序中，都是通过malloc/new等系统调用直接去系统内存中分配相应的内存，并由程序自身通过free/delete去释放，但是在JAVA中，内存的分配和释放（GC）都由JAVA虚拟机所接管；

在JAVA虚拟机中，根据数据区的功能不同，分为以下几种：

 1. 【线程私有】程序计数器；我们知道JAVA内部的多线程是通过线程轮流切换的方式来分配CPU的处理时间，而在切换过程中，需要保存每个线程当前执行的“字节码”的行号指示器，这个数据就是保存在程序计数器中，为每个线程私有的内存空间；
 2. 【线程私有】虚拟机栈内存；在普通应用程序中，内存分为栈内存和堆内存，在JAVA虚拟机中，对应的栈内存为虚拟机栈内存，和普通应用程序的栈也是有区别的；它是用于存储**线程执行过程中的局部变量表**；虚拟机栈以slot为单位进行划分，其中long/double两种变量占据两个slot，其他类型（包括其他基本类型，对象引用）都只占用一个slot；在编译过程中，已经确定了每一个函数运行时需要占用的栈空间大小；在执行过程中，如果出现爆栈会提示StackOverflowError错误或OutofMemoryError错误，此时可以通过`-Xss1M`来控制每个线程栈大小
 3. 【线程私有】本地方法栈；JAVA执行对象包括JAVA字节码和Native程序，其中执行字节码就会使用到虚拟机栈内存，执行Native就会使用本地方式栈（也不完全一定，sun hotspot虚拟机把两种栈合并了）；
 4. 【线程共享】方法区；用于存储整个JAVA进程执行过程中需要类信息，常量信息，静态变量，以及JNI（即时编译）后的代码；在方法区中有一块特殊空间：常量池需要关注，对于运行时产生一个变量如果频繁复制并且不可变（比如字符串），可以通过intern()将其放到常量池，算是一个小的优化区；和虚拟机栈内存一样，方法区的大小和优化的空间不大，只需要设置不爆就可以，通过`-XX:PermSize=512M -XX:MaxPermSize=512M`两个设置它的大小就可以；
 5. 【线程共享】JAVA堆；用于存储运行过程中创建的对象实例，对JAVA内存的监控与控制也是集中在堆上面（对于栈只要控制不爆栈，基本就没有优化空间）；JAVA虚拟机内部核心工作模块（GC模块）也主要是用于对堆内存进行回收和清理；

###JAVA堆内存
JAVA虚拟机内部对堆内存的管理是根据对象的存活时间长短不同（即“堆分代”)来进行管理的，即将堆分为“新生代”和“老生代”，新生代继续细分为Eden，Survivor-0（S0）和Survivor-1（S1）三个区域；对象根据存活时间会先后进入Eden，S0/S1以及老生代四个区域（基本思想是这样，但是也不是绝对，比如针对大对象，虚拟机会假设它存活较久，直接将其放到老生代，算是一种优化）；对这四部分内存的大小或比例进行合理设置，是JAVA堆内存管理最核心的工作；

 1. 整体堆大小的控制；通过`–Xms500M、-Xmx500M`来控制堆内存最大值和最小值，JAVA堆占用的物理内存大小可以在执行过程从系统内存中动态分配和释放，通过这两个参数来控制可以分配系统内存的最大或最小值；但是堆的动态分配和释放在线上不推荐使用，通常是设置两个值一样，来关闭该功能，动态分配过程中对程序的运行是否有影响依赖虚拟机的实现；
 2. 新生代/老生代内存大小比例；两个区域的大小是通过`–XX:NewRatio=n`比例来控制，默认值为2，表示年轻代与年老代比值为1：2，年轻代占整个堆内存1/3，老年代占2/3；
 3. 直接控制新生代大小;`-Xmn30M`如果比例的控制满足不了需求，也可以通过该变量来直接控制新生代内存大小，剩下来的内存将会全部作为老生代使用
 4. 新生代内部三个区域的内存大小比例；通过`-XX:SurvivorRatio=n`来控制Eden，Survivor的比例,由于Survivor区有两个。如：3，表示Eden：Survivor=3：2，即eden占3/5，S0/S1分别占1/5；默认值为4；
 5. Survivor内存中的对象在gc过程会根据存活的代数来将其移动到老年代，可以通过`-XX:MaxTenuringThreshold=15`来控制这个代数；

> Survivor为啥有两个？
> JAVA堆内存的GC是分代进行的，对于每一代内存的清理的方式不同；比如Eden和老年代，每次清理都是清理掉垃圾内存（Eden存活下来进入Survivor），而对于Survivor区域的内存清理需要反复迭代的进行清理，而且清理频率较高；因此就有了S0，S1，每次清理都把存活下来的对象从S0移动到S1中，如果对象迭出N次存活下来，即移动到老年代中；因此在非SurvivorGC过程中，S0和S1总有一块内存是空闲的；

> 大对象的内存分配
> 上面说了，在JAVA虚拟机内部，对象的都是优先在Eden区域分配，并随着GC的过程逐步的从Eden到Survivor再到老生代的移动；但是如果虚拟机可以知道一个对象后面肯定会进入老生代，那么一开始就在老生代中分配对象内存将会是一个很大的优化；当然这个知道肯定是不可能，只能是猜测；
> 在JAVA虚拟机内部，Serial和ParNew两个堆内存分配/GC器就针对大对象做了这么一个假设，如果对象的大小超过`-XX:PretenureSizeThreshold=3m`,那么将会直接在老生代中进行分配；
>这是一个优化，也是一个坑，如果一个程序有大量的短命的大对象，那么这个机制将会导致老生代内存紧张，频繁进行full GC，最后影响进程的性能

###JAVA堆内存的GC原理

JAVA程序与普通应用程序的区别就是对象的内存释放完全有JAVA虚拟机接管，不需要JAVA程序主动的进行free；而这块就有JAVA虚拟机的GC器（垃圾回收器）负责；

GC是垃圾对象清理，第一步就是要识别垃圾对象；

 1. 第一种方法就是是通过引用计数的方式来实现，即记录每一个对象被引用的次数，新增一个引用就+1，释放一个引用就-1，如果对象的引用为0的时候，即将该对象视为垃圾对象，在下一次垃圾回收过程中会进行清理；但是引用计数引用计数的方式存在一个很大的bug，即无法解决循环引用；
 2. 第二中方法也是目前HotSpot使用的方式，即“可到达”的机制；在JAVA虚拟机内部，从根对象开始，对全局所有的对象进行变量，在整个遍历树上，任何一个可访问的到对象都视为有效对象，否则视为垃圾对象，该方法可以屏蔽局部循环引用；

知道了那些对象是垃圾对象了，第二步就是对垃圾对象从内存中清理掉，腾出内存空间；

 1. 标记擦除；将堆上每一块内存设为一个单元，如果单元为垃圾对象，直接进行内容擦除，并用于后面分配和使用；这种方案效率高，但是它有一个很多的缺点就是导致内存碎片，在需要分配大对象的时候无有效的内存空间；
 2. 标记-清除-压缩；它是对标记擦除以后的内存空间进行一次优化压缩，即保证回收后内存块是足够连续性的。但是这种算法会涉及到不停的内存间的拷贝和复制，性能会非常差。
 3. 标记-清除-复制；它采用冗余镜像内存的方法，每次将内存空间分配成两块相同的区域A和B。当内存回收的时候，将A中的内存块拷贝到B中，然后一次性清空A。但是这种算法会对内存要求比较大一些，并且长期复制拷贝性能上也会受影响。

知道了回收的方法了，第三部就是需要这么进行进行回收，比如针对新生代有新生的清理方法，老生代有老生代的清理方法；是并行清理，还是串行清理；是全量清理还是增量清理；清理方法的不同结果就是在吞吐量和停顿时间之间寻找一个合适值，如果追求吞吐量，那么可以每次全量进行清理，当然进程的停顿时间将会延长；对于实时服务，是不能接受长停顿，那么只能控制对吞吐量的追求，采用增量的方法，逐步细微的去释放垃圾对象所占用的内存统计；

JAVA虚拟机内部根据堆内存区域功能的不同，采用不同的GC方法；比如针对新生代的GC采用的Minor GC，将存活的对象从eden区域移动到Survivor区（如果Survivor区域内存不够，也会直接移动到老生代），或将对象从Survivor区域的对象从S0/S1之间移动，或移动到老生代；针对老生代的GC采用的Major GC/Full GC，MajorGC 的速度一般会比 Minor GC慢10倍以上。


###JAVA堆内存的GC器

上面说到了，JAVA虚拟机针对不同的内存区域采用不同的GC机制，不同的GC机制也对象不同的GC实现‘

新生代GC器有：

1. Serial GC器【串行】：单线程的串行GC器。它在垃圾收集的时候会暂停其它所有工作线程。直到收集结束。client模式下默认的新生代GC器；
2. ParNew GC器【并行】；ParNewGC器是Serial的多线程版本。是目前首选的新生代GC器。如果老年代使用CMS GC器，基本也只能和它进行合作。参数：`-XX:+UseConcMarkSweepGC`，比较适合web服务的GC器。一般ParNew和CMS组合；
3. Parallel Scavenge GC器【并行】；它使用复制算法的GC器，并且是多线程的。该收集器主要目的就是达到一个可控制的吞吐量；`-XX:MaxGCPauseMillis`每次年轻代垃圾回收的最长时间(最大暂停时间)，GC器尽量保证内存回收时间不大于这个值，应该设置一个合理的值；`-XX:GCTimeRatio`设置垃圾回收时间占程序运行时间的百分比；`-XX:+UseAdaptiveSizePolicy`设置此选项后,并行GC器会自动选择年轻代区大小和相应的Survivor区比例,以达到目标系统规定的最低相应时间或者收集频率等,此值建议使用并行GC器时,一直打开;server模式下默认的新生代GC器

老生代GC器有：

 1. Serial GC器【串行】：单线程串行的老生代收集器。client模式下，默认老生代GC器
 2. Parallel GC器【并行】；使用“标记-整理”的算法。该收集器比较适合和ParallelScavenge收集器进行组合。`-XX:+UseParallelOldGC`启用，它也是server模式下默认的老生代GC器

其中CMS GC器【并行】是一种以获取最短回收停顿时间为目标的收集器，比较适合实时在线业务，与我目前的需求相比（离线数据处理，追求的是吞吐量），所以也不就分析了！！

可选的组合方式有：

|    参数     | 新生代GC器  |  老生代GC器  |
| --------   | -----  | ----  |
|-XX:+UseSerialGC |Serial 串行GC|Serial Old 串行GC|
|-XX:+UseParallelGC|Parallel Scavenge  并行回收GC|Parallel Old 并行GC|
|-XX:+UseConcMarkSweepGC|ParNew 并行GC|CMS 并发GC或Serial Old 串行GC|
|-XX:+UseParNewGC|ParNew 并行GC|Serial Old 串行GC|
|-XX:+UseParallelOldGC|Parallel Scavenge  并行回收GC|Parallel Old 并行GC|
|-XX:+UseConcMarkSweepGC -XX:+UseParNewGC|Serial 串行GC|CMS 并发GC或Serial Old 串行GC|


### JAVA进程内存监控工具
在整个JAVA虚拟机层面，我们可以采用系统的方法来对内存进行监控和分析，在对于虚拟机内部，我们需要采用GC日志，以及相关工具(jps/jstat/jstack/jinfo/jmap/jhat)等对内部进行分析；

####GC 日志的阅读
对GC日志阅读相当重要，它是对JAVA进程历史运行情况分析的核心工具，如果JAVA服务在某个时间出现假死，如果是因为内存造成的，只要一看GC日志就可以直接了解当时的进程运行情况

GC日志的主要参数包括如下几个：
-XX:+PrintGC 输出GC日志
-XX:+PrintGCDetails 输出GC的详细日志
-XX:+PrintGCTimeStamps 输出GC的时间戳（以基准时间的形式）
-XX:+PrintGCDateStamps 输出GC的时间戳（以日期的形式，如 2013-05-04T21:53:59.234+0800）
-XX:+PrintHeapAtGC 在进行GC的前后打印出堆的信息
-Xloggc:../logs/gc.log 日志文件的输出路径

    组合：-XX:+PrintGCDetails -XX:+PrintGCTimeStamps
    0.756: [Full GC (System) 0.756: [CMS: 0K->1696K(204800K), 0.0347096 secs] 11488K->1696K(252608K), [CMS Perm : 10328K->10320K(131072K)], 0.0347949 secs] [Times: user=0.06 sys=0.00, real=0.05 secs]
    1.728: [GC 1.728: [ParNew: 38272K->2323K(47808K), 0.0092276 secs] 39968K->4019K(252608K), 0.0093169 secs] [Times: user=0.01 sys=0.00, real=0.00 secs]
    2.642: [GC 2.643: [ParNew: 40595K->3685K(47808K), 0.0075343 secs] 42291K->5381K(252608K), 0.0075972 secs] [Times: user=0.03 sys=0.00, real=0.02 secs]
    4.349: [GC 4.349: [ParNew: 41957K->5024K(47808K), 0.0106558 secs] 43653K->6720K(252608K), 0.0107390 secs] [Times: user=0.03 sys=0.00, real=0.02 secs]
    5.617: [GC 5.617: [ParNew: 43296K->7006K(47808K), 0.0136826 secs] 44992K->8702K(252608K), 0.0137904 secs] [Times: user=0.03 sys=0.00, real=0.02 secs]
    7.429: [GC 7.429: [ParNew: 45278K->6723K(47808K), 0.0251993 secs] 46974K->10551K(252608K), 0.0252421 secs]
    针对第一条：0.756为时间戳，FullGC/GC分别表示是新生代GC还是老生代GC；ParNew/CMS表示GC器的类型；[]内部的0K->1696K(204800K)表示指定每一部内存区GC前后占用的大小以及总大小，0.0347096secs为GC耗时；[]外部的39968K->4019K(252608K)表示GC前后，总的堆内存大小的变化；而后面三个耗时分别表示用户层/内核层，以及实际GC耗时；
    如果在组合增加-XX:+PrintHeapAtGC，将会打印GC前后，堆各部分内存详细的信息
    比如
    {Heap before GC invocations=1 (full 0):   //GC之前的情况
    PSYoungGen      total 152896K, used 131072K //新生代总的以及每部分内存使用情况
      eden space 131072K, 100% used
      from space 21824K, 0% used
      to   space 21824K, 0% used
    PSOldGen        total 349568K, used 0K //老生代内存使用情况
      object space 349568K, 0%
    PSPermGen       total 26432K, used 26393K //持久代内存使用情况
      object space 26432K, 99% used
     [PSYoungGen: 131072K->8319K(152896K)] 131072K->8319K(502464K), 0.0176346 secs] [Times: user=0.03 sys=0.00, real=0.02 secs]   //GC
    Heap after GC invocations=1 (full 0): //GC以后的情况
     PSYoungGen      total 152896K, used 8319K
      eden space 131072K, 0% used
      from space 21824K, 38% used
      to   space 21824K, 0% used
     PSOldGen        total 349568K, used 0K
      object space 349568K, 0% used
     PSPermGen       total 26432K, used 26393K
      object space 26432K, 99% used
    }

###工具的基本使用


- jps 显示当前正在运行JAVA虚拟机，配合不同的参数显示不同内存，比如`-p`显示运行的虚拟机的pid，`-m`显示pid加上简化后类名称和参数；`-v`就是显示详细的类名称和参数信息；
- jinfo 还是很重要的，可以运行过程中显示并动态修改JAVA虚拟机配置信息，比如`jinfo -flag +PrintHeapAtGC 19935`可以让正在运行的程序打印GC前后的堆情况;`jinfo 19935`可以显示详细配置和环境变量
- jstat查看Jvm运行过程中的所有信息；常见的使用有：

查看进程GC内存大小以及使用情况：

    [work@ ~]$ jstat -gc 14685
     S0C    S1C    S0U    S1U      EC       EU        OC         OU       PC     PU    YGC     YGCT    FGC    FGCT     GCT
    832.0  1024.0 674.7   0.0   14528.0   952.3    228096.0   217528.9  131072.0 79774.3  10254   38.969  26      4.841   43.810
    前10项分别表示S0/S1，eden，Old，Perm五个区域当前分配的容量和使用量，其中C表示容量，U表示使用量；
    后5项分别表示YourGC/FullGC的次数以及耗时，最后一项表示总共的GC耗时情况

查看进程GC内存使用比例已经使用情况：

    [work@ ~]$ jstat -gcutil 14685
      S0     S1     E      O      P     YGC     YGCT    FGC    FGCT     GCT
     72.59   0.00  73.81  97.26  60.86  10276   39.045    26    4.841   43.887
    前5项分别表示每一块内存使用比例，后5项jstat -gc

查看进程GC内存的可以分配和已分配的情况：

     NGCMN    NGCMX     NGC     S0C   S1C       EC      OGCMN      OGCMX       OGC         OC      PGCMN    PGCMX     PGC       PC     YGC    FGC
    171072.0 1048576.0  27008.0 1024.0 1088.0  20928.0   342272.0  2097152.0   228096.0   228096.0 131072.0 131072.0 131072.0 131072.0  10282    26
    NGCMN，NGCMX，NGC与OGCMN，OGCMX，OGC和GCMN，PGCMX，PGC分别表示新生代，老生代，持久代的内存最小，最大以及当前分配量；S0C，S1C，EC与OC和PC表示每个代里面的每部分内存的分配量；最后两个表示gc次数

显示进程当前类加载情况

    [work@nj01-inf-ensl-yun-mola211.nj01.baidu.com ~]$ jstat -class 14685
    Loaded  Bytes  Unloaded  Bytes     Time
     11692 27162.5       42    69.3      22.15
    加载类型总数，大小，卸载次数，大小，以及总共耗时情况

显示进程jni（即时编译）的情况

    [work@nj01-inf-ensl-yun-mola211.nj01.baidu.com ~]$ jstat -compiler 14685
    Compiled Failed Invalid   Time   FailedType FailedMethod
        3328      2       0    30.90          2 org/apache/spark/ContextCleaner$$anonfun$org$apache$spark$ContextCleaner$$keepCleaning$1 apply$mcV$sp
    编译的次数，失败次数，以及总耗时，以及最后一次失败的原因和方法

 - jstack：显示当前进程的stack，在分析进程每一个线程的进程栈的时候，非常有效；
 - jmap：显示当前进程的内存map，在分析程序因为什么对象而占用内存的时候十分有效;

对于jstack和jmap，对他们的分析需要对进程程序的本身比较了解，不是很好写，就直接跳过了！！但是合适的使用它们，是日常JAVA问题跟进的两个主要工具；
