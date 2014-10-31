NodeManager Container的启动
==============

在分析Container启动源码之前，我们先自己思考一下怎么实现。  

+   NodeManager是调度模块，它复杂接受来自AM的startContainer的请求来启动一个container
+   container是一个进程级别的执行，NodeManager需要从请求信息中生成进程执行命令并写到命令行脚本中
+   NodeManager针对每个container起一个线程，将container进程起起来，该线程将会一直堵塞，直到container进程结束
+   NodeManager可以通过向container进程发送sig来kill掉进程
+   container执行结束以后，NodeManager读取进程结束代码来判断进程是否是正常退出，被kill还是异常退出。

上面简单的陈述了一下container进程的调度过程，其中最重要的内容是：NodeManager中维持一个线程池，针对每个container请求，创建一个
线程并监听线程的结束。

我们就这个线程开始进行分析。

###org.apache.hadoop.yarn.server.nodemanager.containermanager.launcher.ContainerLaunch
ContainLaunch就是我们上面说明线程，它通过继承Callable<Integer>接口，从而可以被线程池调度，而且线程执行会返回一个Integer的值，
这个值就是线程所监控的进程退出错误码。

首先从最简单的进程退出错误码来看container状态。
    
        public enum ExitCode {
            FORCE_KILLED(137),
            TERMINATED(143),
            LOST(154);
          }
          
container处理结束有下面几种可能状态：

+   CONTAINER_EXITED_WITH_FAILURE：ContainLaunch会尝试的去将container进程调度起来，但是调度可能会失败，比如创建一些基础目录之类的。
这种情况container进程都没有调度起来，也就没有上面错误码之说。此时ContainLaucher返回-1，-1也不是在ExitCode中
+   CONTAINER_KILLED_ON_REQUEST：container进程在调度过程中被kill。此时container进程会返回FORCE_KILLED和TERMINATED
+   CONTAINER_EXITED_WITH_FAILURE：和第一条不同，这个是针对container进程被调度起来，但是执行失败，返回非0的错误码，比如java命令执行失败之类
+   CONTAINER_EXITED_WITH_SUCCESS：container进程执行成功，返回0

至于ExitCode.LOST超出本文讨论的访问，这里就不描述了。。。

下面看一个NameNode中一些目录规范，NodeManager中很多任务都和这几个目录在打交道。

+   hadoop.tmp.dir：Hadoop的全局tmp目录，包括Yarn在内的各个模块在运行过程中默认都是在该目录中创建相应的临时目录
+   yarn.nodemanager.local-dirs：nodemanager负责container的运行和调度，运行过程中涉及到大量的文件包括map的输出，这个目录就是nodemanager运行过程中所有目录的根目录
+   yarn.nodemanager.log-dirs：nodemanager在运行过程中所调度的所有进程和container都有相应的日志目录，而该配置就是配置所有日志目录的根目录
+   yarn在nodemanager中实现传统mapreduce中的distribuction cache的，和DC一样，文件cache分为public,private,app三个级别。
    +   public：全局权限，由NodeManager直接下载到{*.local-dirs}/filecache目录下面
    +   private：用户私有权限。NodeManager为每个用户维护一个{*.local-dirs}/usercache/{username}目录，其中{*.local-dirs}/usercache/{username}/filecache为用户层面的文件
    +   application：应用层面。NodeManager为指定用户的每个app创建一个本地目录{*.local-dirs}/{username}/{app_id}，
    其中{*.local-dirs}/usercache/{username}/appcache/{app_id}/filecache内部维护app层面的文件，app结束以后会被清理。  
    具体文件本地化后面会专门进行分析，差不多是Nodemanager中一个饿比较拗口的模块。    
+   {*.local-dirs}/nmPrivate:是NodeManager运行过程中目录，这个运行不包括container进程运行过程中生成的数据。NodeManager在调度过程中，
会在该{*.local-dirs}/nmPrivate目录下为每个app的每个container创建一个临时目录，从而可以为container运行之前做好一些准备。这些准备包括
    +   container运行之前，需要针对进程生成执行脚本，脚本就放在{*.local-dirs}/nmPrivate/appid/containerid/launch_container文件中
    +   container运行之前，需要针对进程生成token文件，内容就放在{*.local-dirs}/nmPrivate/appid/containerid/containerid.tokens文件中
    +   container运行过程中，会在{*.local-dirs}/nmPrivate/appid/containerid/containerid.pid中创建container的pid文件，从而实现运行过程中监控该文件来确定container进程是否退出
    +   container运行结束后，会在{*.local-dirs}/nmPrivate/appid/containerid/containerid.pid.exitcode中写入container进程执行退出码，从而实现NodeManager获取container的执行结果。
+   nmPrivate目录是NodeManager运行的私有目录，而不是container运行的pwd。上述生成container执行脚本和token在进行container启动时，会将它复制到container的pwd中。
默认pwd={*.local-dirs}/appcache/{username}/appcache/{app_id}/{containerid}

在上面谈到{*.local-dirs}/nmPrivate目录时候，我们说到在真正对container的进程进行调度之前，需要创建container执行脚本和token文件等步骤，下面针对ContainLaunch在将
container调度起来之前所做的事情做一个详细的描述：

+   container.getLaunchContext().getCommands()返回一个字符串list。AM在请求NodeManager启动container会写这个List。
+   AM提交的commands列表中对于container执行过程中日志目录和操作系统类型不清楚，因此NodeManager针对这块提供三个常量
    +   <LOG_DIR>：表示container运行时的日志目录，AM采用该常量进行标示
    +   <CPS>：window和linux针对目录分隔符的不同，AM提供的commands中如果有目录路径分隔符，用该常量进行替换
    +   {{和}}来对系统常量进行替换。window下面用%VAR%来表示系统常量，而linux用$VAR。为了保证代码平台无关，采用{{}}来对系统常量进行标示
+   ENV的设置。AM提交的container请求中包含一部分用户自定义的container，但是NodeManager需要对这部分进行处理，主要是添加一些内部环境变量，用内部的环境变量覆盖用户
设置可能存在风险和错误的环境变量。涉及到环境变量还是很多，参阅ContainerLaunch.sanitizeEnv()函数。
+   最后就写token和执行脚本到上面谈到两个文件中，执行脚本的生成内容很丰富，上面设置的环境变量也会写到该文件中。

到目前为止ContainLaunch已经完成对container所有的初始化工作，此时需要做的工作就是将container的进程起起来，这个过程是通过调用ContainerExecutor来实现的

        exec.activateContainer(containerID, pidFilePath);
        ret = exec.launchContainer(container, nmPrivateContainerScriptPath,
                nmPrivateTokensPath, user, appIdStr, containerWorkDir,
                localDirs, logDirs);

注意该函数的调用是堵塞的，在调度的进程退出之前，该函数是不会退出。

最后，针对ContainerLaunch附上一个所生成的ContainLaunch脚本的，通过该脚本，可以看出ContainerLaunch对环境变量等做了什么工作。
        
        #!/bin/bash        
        export JAVA_HOME="/home/java"
        export NM_AUX_SERVICE_mapreduce_shuffle="AAA0+gAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        export NM_HOST="××××××"
        export HADOOP_YARN_HOME="/home/hadoop"
        export HADOOP_ROOT_LOGGER="INFO,CLA"
        export JVM_PID="$$"
        export STDERR_LOGFILE_ENV="/home/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705/stderr"
        export PWD="/home/data/hadoop/tmp/nm-local-dir/usercache/work/appcache/application_1413959353312_0110/container_1413959353312_0110_01_000705"
        export NM_PORT="18476"
        export LOGNAME="work"
        export MALLOC_ARENA_MAX="4"
        export LD_LIBRARY_PATH="$PWD:/home/hadoop/lib/native"
        export LOG_DIRS="/home/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705"
        export NM_HTTP_PORT="8042"
        export SHELL="/bin/bash"
        export LOCAL_DIRS="/home/data/hadoop/tmp/nm-local-dir/usercache/work/appcache/application_1413959353312_0110"
        export HADOOP_COMMON_HOME="/home/hadoop"
        export HADOOP_TOKEN_FILE_LOCATION="/home/data/dataplatform/data/hadoop/tmp/nm-local-dir/usercache/work/appcache/application_1413959353312_0110/container_1413959353312_0110_01_000705/container_tokens"
        export CLASSPATH="$PWD:$HADOOP_CONF_DIR:$HADOOP_COMMON_HOME/share/hadoop/common/*:。。。。
        export STDOUT_LOGFILE_ENV="/home/data/dataplatform/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705/stdout"
        export USER="data"
        export HADOOP_CLIENT_OPTS="-Xmx1024m-Xmx1024m  -Dlog4j.configuration=container-log4j.properties -Dyarn.app.container.log.dir=/home/data/dataplatform/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705 -Dyarn.app.container.log.filesize=0 -Dhadoop.root.logger=INFO,CLA"
        export HADOOP_HDFS_HOME="/home/hadoop"
        export CONTAINER_ID="container_1413959353312_0110_01_000705"
        export HOME="/home/"
        export HADOOP_CONF_DIR="/home/hadoop/etc/hadoop"
        
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/filecache/535/guava-11.0.2.jar" "guava-11.0.2.jar"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/filecache/57/partitions_b545f344-5ebc-4265-a691-7c3a4f764796" "_partition.lst"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/filecache/538/zookeeper-3.4.5.jar" "zookeeper-3.4.5.jar"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/filecache/537/protobuf-java-2.5.0.jar" "protobuf-java-2.5.0.jar"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/appcache/application_1413959353312_0110/filecache/15/job.xml" "job.xml"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/filecache/536/hadoop-mapreduce-client-core-2.2.0.jar" "hadoop-mapreduce-client-core-2.2.0.jar"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/filecache/539/hbase-0.94.6.jar" "hbase-0.94.6.jar"
        ln -sf "/home/data/hadoop/tmp/nm-local-dir/usercache/work/appcache/application_1413959353312_0110/filecache/14/job.jar" "job.jar"
       
        exec /bin/bash -c "$JAVA_HOME/bin/java -Djava.net.preferIPv4Stack=true -Dhadoop.metrics.log.level=WARN  -Xmx1024m -Djava.io.tmpdir=$PWD/tmp -Dlog4j.configuration=container-log4j.properties
         -Dyarn.app.container.log.dir=/home/data/dataplatform/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705 
         -Dyarn.app.container.log.filesize=0 -Dhadoop.root.logger=INFO,CLA 
         org.apache.hadoop.mapred.YarnChild 10.214.19.62 38007 attempt_1413959353312_0110_m_000775_0 705 
         1>/home/data/dataplatform/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705/stdout 
         2>/home/data/dataplatform/log/hadoop/yarn/userlogs/application_1413959353312_0110/container_1413959353312_0110_01_000705/stderr "
         
###org.apache.hadoop.yarn.server.nodemanager.ContainerExecutor
