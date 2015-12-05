# 进程分析之磁盘IO

标签（空格分隔）： 进程分析

---

## 机械磁盘

虽然现在大范围的使用SSD，但是在一些存储系统中HDD还是大批量被使用，毕竟便宜；HDD是机械设备，对HDD的数据操作，有95%的耗时是消耗在机械运动上，一次数据操作为一次IO服务，它包括以下几个环节：

 1. 寻道时间，是指将读写磁头移动至正确的磁道上所需要的时间。寻道时间越短，I/O操作越快，目前磁盘的平均寻道时间一般在3－15ms；
 2. 旋转延迟，是指盘片旋转将请求数据所在扇区移至读写磁头下方所需要的时间。旋转延迟取决于磁盘转速，通常使用磁盘旋转一周所需时间的1/2表示。比如，7200rpm的磁盘平均旋转延迟大约为60*1000/7200/2 = 4.17ms
 3. 内部接口传送时间，即从磁盘盘片到内部磁盘缓冲传送的时间，量级是440微秒。
 4. 外部接口传输时间，即从磁盘缓冲到接口传送的时间，量级是110微秒。

对于一个确定的磁盘，旋转延迟保持不变，而内部接口传送时间和外部接口传输时间微秒级别，基本可以忽略不计，那么影响HDD性能核心就是寻道时间；

对磁盘性能的度量有两个指标：IOQPS，IO吞吐/带宽；

 1. IOQPS指的每秒可以完成的IO服务的次数，一次IO服务主要的耗时是寻道时间上，如果是大量的随机IO，那么每次寻道时间都处于上限值，IOQPS下降；理论上下限=[1000ms/(3ms+4.17ms), 1000ms/(15ms+4ms)]=[139,53];但是真实环境下，一个磁盘IOQPS的值受每次IO读写的数据大小，比如512B，4k，1M等各个值下IOQPS肯定是有区别的；
 2. IO吞吐表示在指定时间内存，完成的IO读写数据字节数，它的值和每次IO读写的数据大小有密切关系，也从而会影响IOQPS，比如每次读写数据块较大，从而最大化的利用了寻道带来的开销，从而提高吞吐，但是此时IOQPS就会减小；

> 实例：
> 写入10000个大小为1kb的文件到硬盘上,耗费的时间要比写入一个10mb大小的文件多得多,虽然数据总量都是10mb,因为写入10000个文件时,根据文件分布情况和大小情况,可能需要做几万甚至十几万次IO才能完成.而写入一个10mb 的大文件,如果这个文件在磁盘上是连续存放的,那么只需要几十个io就可双完成.
对于写入10000个小文件的情况,因为每秒需要的IO非常高,所以此时如果用具有较高IOPS的磁盘,将会提速不少,而写入一个10mb文件的情况,就算用了有较高IOPS的硬盘来做,也不会有提升,因为只需要很少的IO就可以完成了,只有换用具有较大传输带宽的硬盘,才能体现出优势


对磁盘性能IOQPS和IO吞吐的追求与业务有密切关系，比如大文件存储希望吞吐可以做到最高，而小文件或随机读写的业务更加追求IOQPS；如果业务对吞吐和IOQPS都有一定依赖，那么就需要找到一个合适的blocksize来设置每次IO大小，从而在IOPQS和吞吐之间均衡，即（IOQPS × block_size = IO吞吐）；

> 提供IO性能的方法：
> 1） IO合并，即将多个IO请求进行合并
> 2)  IO拆封，采用raid等磁盘阵列，将一个普通IO请求，并发的分发到多个物理磁盘，提供IO吞吐；
> 3)  IO大cache，每次IO写操作都不直接写到物理设备，而是存储在IO cache中，直到cache满了再更新到磁盘设备中
> 4) IO预读Buffer，通过IO预判读，虽然一次只读4k，但是预判512k到buffer，当然这个对只想对4k的应用来说是一个坑！

##磁盘性能测试

拿到一台机器，如果对磁盘有一定的依赖，那么第一件就是对磁盘的IO性能有一定的了解；

### dd（磁盘的顺序读写性能测试）
dd是最为常用并且简单的磁盘io性能测试工具，它主要测试顺序读写的环境下，小文件的读写IOQPS和大文件读写的吞吐（即block较小时，测试的主要是iops。block较大时，测试的主要是吞吐）。

磁盘IO有BufferIO、DirectIO两种，其中BufferIO的读写会经过设备->内核->用户的一种转换，而DirectIO是直接从设备->用户，因此使用DirectIO可以更好的了解磁盘的读写性能

    iops—写测试 dd if=/dev/zero of=./a.dat bs=8k count=1M oflag=direct
                ----------------------HDD--------------------------
                1048576+0 records in
                1048576+0 records out
                8589934592 bytes (8.6 GB) copied, 102.629 s, 83.7 MB/s
                那么IOQPS=1M/102.629=1 W/s,吞吐=83.7MB/s
                ----------------------SSD--------------------------
                1048576+0 records in
                1048576+0 records out
                8589934592 bytes (8.6 GB) copied, 47.5207 s, 181 MB/s
                那么IOQPS=1M/47.5207=2.2 W/s,吞吐=181MB/s
    iops—读测试 dd if=./a.dat of=/dev/null bs=8k count=1M iflag=direct
                ----------------------HDD--------------------------
                1048576+0 records in
                1048576+0 records out
                8589934592 bytes (8.6 GB) copied, 102.062 s, 84.2 MB/s
                那么IOQPS=1M/102.629=1 W/s,吞吐=84.2MB/s
                ----------------------SSD--------------------------
                1048576+0 records in
                1048576+0 records out
                8589934592 bytes (8.6 GB) copied, 73.1328 s, 117 MB/s
                那么IOQPS=1M/73=1.4 W/s,吞吐=117 MB/s
    //
    //小数据的顺序读写，相比HDD，SSD的提升不是很明显，才1.5～2倍！！
    //
    吞吐—写测试 dd if=/dev/zero of=./a.dat bs=1M count=8k oflag=direct
                ----------------------HDD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 104.326 s, 82.3 MB/s
                那么IOQPS=8k/104=78/s，吞吐=82.3 MB/s变化不大
                ----------------------SSD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 5.15269 s, 1.7 GB/s
                那么IOQPS=8k/5=1638/s，吞吐=1.7 GB/s
    吞吐—读测试 dd if=./a.dat of=/dev/null bs=1M count=8k iflag=direct
                ----------------------HDD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 99.2913 s, 86.5 MB/s
                那么IOQPS=8k/99=82/s，吞吐=86.5 MB/s
                ----------------------SSD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 5.22082 s, 1.6 GB/s
                那么IOQPS=8k/5=1638/s，吞吐=1.6 GB/s
    //
    //对于大块数据的读写，SSD提升还是很明显，接近20倍！

不过BufferIO在对大文件的读写测试还是有很大用处，即特别是大文件的吞吐量的测试，不过测试过程中，需要增加一个conv=fdatasync，使用该参数，在完成所有读写后会调用一个sync确保数据全部刷到磁盘上，否则就是主要在测内存读写了；另外还有一个参数是oflag=dsync，使用该参数也是走的BufferIO，但却是会在每次IO操作后都执行一个sync。

    吞吐—写测试 dd if=/dev/zero of=./a.dat bs=1M count=8k conv=fdatasync
                ----------------------HDD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 112.771 s, 76.2 MB/s
                那么IOQPS=8k/112=73/s，吞吐=76.2 MB/s变化不大
                ----------------------SSD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 10.4019 s, 826 MB/s
                那么IOQPS=8k/10s=819/s，吞吐=1.6 GB/s
    //
    //大数据的写，BufferIO相比DirectIO，吞吐和iops都有下降，原因是数据最终都是要落盘，但是BufferIO落盘前经过一次内核buffer的缓存时间，算是浪费了！
    //
    吞吐—读测试 dd if=./a.dat of=/dev/null bs=1M count=8k
                ----------------------HDD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 2.77366 s, 3.1 GB/s
                ----------------------SSD--------------------------
                8192+0 records in
                8192+0 records out
                8589934592 bytes (8.6 GB) copied, 3.27296 s, 2.6 GB/s
    //
    //BufferIO的读就不一样了，buffer的存储，基本就相当于读内存了


### hdparam（磁盘参数修改，支持对磁盘顺序读写性能进行测试）

 hdparam可以针对磁盘每个分区进行性能测试了，也可以修改分配和磁盘的配置参数，需要root指向权限；
 针对参数的需求，这里就不描述了，这里就看一下怎么测试分区的读写性；


    ----------------------HDD--------------------------
    hdparm -Tt /dev/sda6
    Timing cached reads:   12144 MB in  2.00 seconds = 6081.48 MB/sec
    Timing buffered disk reads:  322 MB in  3.01 seconds = 106.81 MB/sec
    ----------------------SSD--------------------------
    hdparm -Tt /dev/sdb1
    Timing cached reads:   13244 MB in  2.00 seconds = 6632.48 MB/sec
    Timing buffered disk reads:  2268 MB in  3.00 seconds = 755.97 MB/sec
    //这个数值是读写的平均数据，HDD与SSD之间的7倍平均性能差也是大家一致公认的一个平均值！

### fio

磁盘IO的工具，支持测试顺序读写、随机读写、顺序混合读写、随机混合读写，并且可以调整IO并发量.

太专业了！需要的时候在去研究吧！备忘


##系统IO压力分析

### 首先查看IOWait高不高

Iowait直面理解就是“内核态CPU用于等待IO完成的CPU耗时”，但是这句话对iowait的理解还是很字面。

磁盘作为外部设备，它是有一定的IOPS和带宽/吞吐量的限制；如果在有大量的磁盘读写的时候，IO就出现的瓶颈，IO服务时间（包括IO等待）变长，此时如果**系统空闲idle资源没有其他可运行进程进行调度**，那么就相当于有一部分的CPU在“等待”这部分IO完成后再继续其他工作，换句话说（iowait+idle的时间都是CPU空闲的时间，只是idle是真的空闲，iowait是有进程在等待io）；

如果iowait很高了，那么就代表当前很空闲的机器，上面跑的业务进程QPS上不上，是因为业务进程的性能瓶颈在IO上；但是Iowait很低不代表IO没有问题，因为它有一个条件就是当前“没有其他可运行进程进行调度”，在IO压力很大的时候，IOwait可能很小，因为空闲出来的cpu都被拿来做其他的计算调度；

### 通过iostat了解每个分区的压力

iostat可以很透彻的分析当前系统的io压力情况

    [work@# ~]$ iostat
    avg-cpu:  %user   %nice %system %iowait  %steal   %idle
               3.70    0.34    6.23    0.03    0.00   89.70
    Device:            tps   Blk_read/s   Blk_wrtn/s   Blk_read   Blk_wrtn
    sdb              62.40      3247.81      5546.55 12637971586 21582874000
    sda               6.95        67.44       197.56  262416346  768741064
    //tps：设备的当前的每秒的数据传输次数，一次传输就是一个io服务
    //Blk_read/Blk_wrtn/s 每秒的对写block次数，这里的blcok为扇区，即512Byte
    //Blk_read/Blk_wrtn 累计的block读写次数
    //
    [work@# ~]$ iostat -x
    Device:  rrqm/s   wrqm/s     r/s     w/s   rsec/s   wsec/s avgrq-sz avgqu-sz   await  svctm  %util
    sdb      0.54   649.68   19.57   42.84  3247.83  5546.58   140.93     0.06    0.99   0.10   0.63
    sda      0.09    15.80    1.11    5.85    67.44   197.56    38.12     0.07    9.56   0.90   0.63
    //-x参数可以显示每个设备的详细的数据
    //rrqm/wrqm：io读写会进入queue，并被合并，这里就被合并的次数
    //rw/s:设备每秒的读写次数
    //rwsec/s:设备每秒的读写扇区数目，基本与Blk_wrtn/s等保持相同
    //avgrq-sz：设备读写块大小（扇区数），上面用8k进行dd测试的话，这个数字就是16=8×1024/512
    //avgqu-sz：设备的queue平均大小，越大代表压力大
    //await：io请求在queue中被堵塞的平均时长+io被处理完成的平均时长，相当于成功一次io的时间
    //svctm：io被处理完成的平均时长，看await就可以
    //%util：io利用率

> 正常情况，一次io平均响应时间（await）应该是在20ms以内，如果过大，代表压力已经很大了；%util一样，如果很大也需要关注，不过%util很高，但是await不大还是可以接受，持续性的读写会造成这种情况（在用dd来测试磁盘性能的时候，%util基本是100%的）

//其他相关的工具：**vmstat，dstat**

###通过iotop来获取io压力大的进程

    //目前IO总的吞吐量以及每个进程的io开销
    Total DISK READ: 0.00 B/s | Total DISK WRITE: 45.74 M/s
    TID  PRIO  USER     DISK READ  DISK WRITE  SWAPIN      IO    COMMAND
    15903 be/4 work      0.00 B/s   55.80 M/s  0.00 % 61.54 % dd if=/dev/zero of=./a.dat bs=8k count=1M oflag=direct
    1     be/4 root        0.00 B/s    0.00 B/s  0.00 %  0.00 % init

很直白了！就不解释了！然后可以继续阅读进程的io文件来了解进程的历史io情况

    [root@# ~]# cat /proc/20811/io
    rchar: 133750354274 // 读出的总字节数，read或者pread中的长度参数总和
    wchar: 1646551816314 // 写入的总字节数
    syscr: 139026906 //read 系统调用的次数
    syscw: 65789036 //waite 系统调用的次数
    read_bytes: 79548416 //实际从磁盘中读取的字节总数
    write_bytes: 1878315008 //实际从磁盘中读取的字节总数
    cancelled_write_bytes: 20480 //由于截断pagecache导致应该发生而没有发生的写入字节数
    //
    //在以前的工作经验中，遇到过一次频繁调用syscr/syscw的case，每秒几百次的write



