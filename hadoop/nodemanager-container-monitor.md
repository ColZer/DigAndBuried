NodeManager解析系列一：内存Monitor解析
====

通过yarn调度在NodeManager中每一个计算任务都是container，NM负责对container的运行状态进行监控。这里强调一下，运行在NodeManager中每个
container在NodeManager中是一个线程，但是该线程会以进程的方式起一个计算任务，堵塞并等待进程退出。  同时由于启动的进程会再次生成新的进程，因此
yarn需要对整个进程树进行监控才能正确获取该进程树所占用的内存等信息。

为了控制container在运行过程中所占用的内存和cpu，NodeManager有两种实现方式：

+   第一种是使用linux内部的cgroup来进行监控和限制
+   第二种是在NodeManager中起monitor线程对运行在该NodeManager上所有的container进行监控，在发现超过内存的限制时会请求NodeManager杀死相应container。

第一种方式除了控制内存还可以在cpu等多个方面进行控制，但除非yarn集群是完全公用化，需要进行很强度的控制，否则第二种方式基本满足业务的需求。  
本文也主要针对第二种方式中container-monitor进行讨论。

### 进程基本信息和内存占用
container-monitor需要对container进程树进行信息获取，并对它所占用的内存进行监控，那么首先第一个需要解决的问题就是需要这些信息。

NodeManager提供了ResourceCalculatorProcessTree接口来获取进程的基本信息，用户可以通过NodeManager的配置项"yarn.nodemanager.container-monitor.process-tree.class"
进行配置来提供基于ResourceCalculatorProcessTree的实现。在NodeManager中默认针对window环境提供WindowsBasedProcessTree的实现，针对linux提供ProcfsBasedProcessTree的实现。
window就不考虑了，这里简单分析一下ProcfsBasedProcessTree的实现，也简单回顾一下怎么获取linux下进程的详细信息。

linux中，每个运行中的进程都/proc/目录下存在一个子目录，该目录下的所有的文件记录了该进程运行过程中所有信息，通过对这些子文件进行解析，就可以获取进程详细的信息。  
其中，ProcfsBasedProcessTree利用到的文件有：cmdline,stat,smaps

cmdline文件中记录该进程启动的命令行信息，如果是java程序，就相当于通过命令"jps -v"获取的进程信息，不过cmdline记录文件中用\0来代替空格，需要做一次反替代。

        work@node:~$ cat /proc/6929/cmdline 
        /home/work/opt/jdk1.7/bin/java-Xms128m-Xmx750m-XX:MaxPermSize=350m-XX:ReservedCodeCacheSize=96m-ea
        -Dsun.io.useCanonCaches=false-Djava.net.preferIPv4Stack=true-Djsse.enableSNIExtension=false-XX:+UseCodeCacheFlushing
        -XX:+UseConcMarkSweepGC-XX:SoftRefLRUPolicyMSPerMB=50-Dawt.useSystemAAFontSettings=lcd
        -Xbootclasspath/a:/home/work/opt/idea/bin/../lib/boot.jar-Didea.paths.selector=IdeaIC13-Djb.restart.code=88com.intellij.idea.Main

stat文件是一堆数字堆砌而成，其中包含的信息比较多，没有必要可以不全了解。如下
    
        work@node:~$ cat /proc/6929/stat
        6929 (java) S 6892 1835 1835 0 -1 1077960704 254628 201687 317 391 120399 23093 3098 329 20 0 65 0 99371 3920023552 
        206380 18446744073709551615 4194304 4196452 140735679649776 140735679632336 140462360397419 0 0 4096 16796879 
        18446744073709551615 0 0 17 1 0 0 0 0 0 6293608 6294244 28815360 140735679656977 140735679657483 
        140735679657483 140735679659993 0
        
ProcfsBasedProcessTree针对stat文件提供了ProcessInfo类的实现，它通过读取stat文件来动态更新每个进程的基本信息
    
        private static class ProcessInfo {
              private String pid; // process-id=6929 进程号
              private String name; // command name=(java) 进程名称
              //stat=S 进程状态，R:runnign，S:sleeping，D:disk sleep ， T: stopped，T:tracing stop，Z:zombie，X:dead
              private String ppid; // parent process-id =6892 父进程ID            
              private Integer pgrpId; // process group-id=1835 进程组号
              private Integer sessionId; // session-id=6723 c该任务所在的会话组ID              
              private Long utime = 0L; // utime=120399 该任务在用户态运行的时间，单位为jiffies
              private BigInteger stime = new BigInteger("0"); // stime=23093 该任务在核心态运行的时间，单位为jiffies
              private Long vmem; // 单位（page） 该任务的虚拟地址空间大小
              private Long rssmemPage; // (page) 该任务当前驻留物理地址空间的大小      

其中utime的单位为jiffies可以通过命令"getConf CLK_TCK"获取，page的页大小单位可以通过“getConf PAGESIZE”获得。  

另外可以通过两次获取一个进程的ProcessInfo，利用两次的utime+stime之和的增量值来表示期间该进程所占用的cpu片大小。

smaps文件是在Linux内核 2.6.16中引入了进程内存接口，它相比stat文件中统计的rssmem要更加准确。  
但是当前的hadoop版本是默认关闭该版本，用户可以配置yarn.nodemanage.container-monitor.procfs-tree.smaps-based-rss.enabled=true来启用。

对于每个进程，smape在逻辑上是由多段虚拟内存端组成，因此统计一个进程树的真实内存大小，需要对进程树中的每个进程的所有虚拟机内存段进行遍历迭代，
求出所有的内存和。因此通过smaps来获取rss的复杂度比stat文件要高。  
下面为一个内存段的信息。

    00400000-00401000 r-xp 00000000 08:07 131577                             /home/work/opt/jdk1.7/bin/java
    //00400000-00401000表示该虚拟内存段的开始和结束位置。
    //00000000 该虚拟内存段在对应的映射文件中的偏移量，                                                  
    //08:07 映射文件的主设备和次设备号
    //131577 被映射到虚拟内存的文件的索引节点号
    //home/work/opt/jdk1.7/bin/java为被映射到虚拟内存的文件名称
    // r-xp为虚拟内存段的权限信息，其中第四个字段表示该端是私有的:p，还是共享的:s
    
    //进程使用内存空间，并不一定实际分配了内存(VSS)
    Size:                  4 kB
    //实际分配的内存(不需要缺页中断就可以使用的)
    Rss:                   4 kB
    //是平摊共享内存而计算后的使用内存(有些内存会和其他进程共享，例如mmap进来的)
    Pss:                   4 kB
    //和其他进程共享的未改写页面
    Shared_Clean:          0 kB
    //和其他进程共享的已改写页面
    Shared_Dirty:          0 kB
    //未改写的私有页面页面
    Private_Clean:         4 kB
    //已改写的私有页面页面
    Private_Dirty:         0 kB
    //标记为已经访问的内存大小
    Referenced:            4 kB
    Anonymous:             0 kB
    AnonHugePages:         0 kB
    //存在于交换分区的数据大小(如果物理内存有限，可能存在一部分在主存一部分在交换分区)
    Swap:                  0 kB
    //内核页大小 
    KernelPageSize:        4 kB
    //MMU页大小，基本和Kernel页大小相同
    MMUPageSize:           4 kB
    Locked:                0 kB
    VmFlags: rd ex mr mw me dw sd 
          
在NodeManager中，每个进程的内存段也由这几部分组成，参考ProcessSmapMemoryInfo的实现
        
      static class ProcessSmapMemoryInfo {
            private int size;
            private int rss;
            private int pss;
            private int sharedClean;
            private int sharedDirty;
            private int privateClean;
            private int privateDirty;
            private int referenced;
            private String regionName;
            private String permission;
       }
           
计算整个进程树的RSS，并不是简单的将所有rss相加，而是有一个计算规则。

+   对于没有w权限的内存段不进行考虑，即权限为r--s和r-xs
+   对于有写权限的内存段，该内存段对应的rss大小为Math.min(info.sharedDirty, info.pss) + info.privateDirty + info.privateClean;

如上所说，通过smaps文件计算的rss更加准确，但是复杂度要高。一般情况下没有必要开启整个开关，保持默认的关闭。

另外上述获取的RSS内存大小的大小都为pagesize，比如，超过内存被container-monitor杀死的日志：
    
    Container [pid=21831,containerID=container_1403615898540_0028_01_000044] is running beyond physical memory limits. 
    Current usage: 1.0 GB of 1 GB physical memory used; 1.9 GB of 3 GB virtual memory used. Killing container.
    Dump of the process-tree for container_1403615898540_0028_01_000044 :
    |- PID PPID PGRPID SESSID CMD_NAME USER_MODE_TIME(MILLIS) SYSTEM_TIME(MILLIS) VMEM_USAGE(BYTES) RSSMEM_USAGE(PAGES) FULL_CMD_LINE
    |- 21837 21831 21831 21831 (java) 2111 116 1981988864 263056 java
        
打印的进程rss大小为263056，而该机器的页大小为4098，那么实际内存大小为1027m。

### container-Monitor的实现
从NodeManager的逻辑结构来解释container-Monitor在其中的位置：

+   每个NodeManager都一个containerManager，否则该节点上所有container的管理，所有的container的启停都需要通过containerManager进行调度
+   containerManager管理的container的启停，在container内部，每一个container启停都会调用containerManager传递过来的dispatcher，
向其中传递ContainerStartMonitoringEvent事件。
+   ContainersMonitorImpl会接受ContainerStartMonitoringEvent事件进而处理，如果为START_MONITORING_CONTAINER，则想Monitor中提供该container并进行监控，
如果为STOP_MONITORING_CONTAINER，则将container从monitor中移除；不过这个过程是异步的，只有在监控的下一个运行时间片刻，才会真正去影响监控列表。

对container-monitor有些配置参数可以进行设置：

+   yarn.nodemanager.contain_monitor.interval_ms，设置监控频率，默认为3000ms
+   yarn.nodemanager.resource.memory_MB,该项设置了整个NM可以配置调度的内存大小，如果监控发现超过物理内存的80%，会抛出warn信息。
+   yarn.nodemanager.vmem-pmem-ratio,默认为2.1,用户app设置单container内存大小是物理内存，通过该比例计算出每个container可以使用的虚拟内存大小。
+   yarn.nodemanager.pmem-check-enabled/vmem-check-enabled启停对物理内存/虚拟内存的使用量的监控


后面的工作就是启动一个线程（“Container Monitor”）调用ResourceCalculatorProcessTree接口获取每个container的进程树的内存。具体就不分析了，挺简单的！！！

这么简单，我写干嘛？好吧!!就当这回忆proc相关信息吧。

慢！！！还有一个逻辑很重要，container是基于进程了来调度，创建子进程采用了“fork()+exec()”的方案，子进程启动的瞬间，
它使用的内存量和父进程一致。一个进程使用的内存量可能瞬间翻倍，因此需要对进程进行"age"区分。参考如下代码：

    //其中curMemUsageOfAgedProcesses为age>0的进程占用内存大小，而currentMemUsage不区分age年龄大小
    boolean isProcessTreeOverLimit(String containerId,
                                      long currentMemUsage,
                                      long curMemUsageOfAgedProcesses,
                                      long vmemLimit) {
        boolean isOverLimit = false;
    
        if (currentMemUsage > (2 * vmemLimit)) {
          LOG.warn("Process tree for container: " + containerId
              + " running over twice " + "the configured limit. Limit=" + vmemLimit
              + ", current usage = " + currentMemUsage);
          isOverLimit = true;
        } else if (curMemUsageOfAgedProcesses > vmemLimit) {
          LOG.warn("Process tree for container: " + containerId
              + " has processes older than 1 "
              + "iteration running over the configured limit. Limit=" + vmemLimit
              + ", current usage = " + curMemUsageOfAgedProcesses);
          isOverLimit = true;
        }
    
        return isOverLimit;
     }

通过该逻辑，可以避免因为进程新启动瞬间占用的内存翻倍，导致进程被kill的风险。