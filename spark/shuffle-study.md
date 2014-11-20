# Spark shuffle研究
版本：1.1.0

不管是hadoop中map/reduce还是spark中各种算子，shuffle过程都是其中核心过程，shuffle的设计是否高效，基本确定了整个计算过程是否高效。
设计难点在于shuffle过程涉及到大数据的IO操作（包括本地临时文件IO和网络IO），以及可能存在的cpu密集型排序计算操作。  

刚刚发布的spark1.1版本，spark针对大型数据引入一个新的shuffle实现，即“sort-based shuffle”
> This release introduces a new shuffle implementation optimized for very large scale shuffles. 
> This “sort-based shuffle” will be become the default in the next release, and is now available to users. 
> For jobs with large numbers of reducers, we recommend turning this on. 

本文针对shuffle相关的代码逻辑做一次串读，其中包括shuffle的原理，以及shuffle代码级别的实现。

## Job，Stage，Task, Dependency

在Spark中，RDD是操作对象的单位，其中操作可以分为转换(transformation)和动作(actions),只有动作操作才会触发一个spark计算操作。  
以rdd.map操作和rdd.count操作做比较  

    def map[U: ClassTag](f: T => U): RDD[U] = new MappedRDD(this, sc.clean(f))
    def count(): Long = sc.runJob(this, Utils.getIteratorSize _).sum   

map是一个转换操作，它只是在当前的rdd的基础上创建一个MappedRDD对象，而count是一个动作操作，它会调用sc.runJob向spark提交一个Job  

Job是一组rdd的转换以及最后动作的操作集合，它是Spark里面计算最大最虚的概念，甚至在spark的任务页面中都无法看到job这个单位。
但是不管怎么样，在spark用户的角度，job是我们计算目标的单位，每次在一个rdd上做一个动作操作时，都会触发一个job，完成计算并返回我们想要的数据。  

Job是由一组RDD上转换和动作组成，这组RDD之间的转换关系表现为一个有向无环图(DAG)，每个RDD的生成依赖于前面1个或多个RDD。  
在Spark中，两个RDD之间的依赖关系是Spark的核心。站在RDD的角度，两者依赖表现为点对点依赖，
但是在Spark中，RDD存在分区（partition）的概念，两个RDD之间的转换会被细化为两个RDD分区之间的转换。

![job to rdd,rdd to part](../image/job.jpg)

如上图所示，站在job角度，RDD_B由RDD_A转换而成，RDD_D由RDD_C转换而成，最后RDD_E由RDD_B和RDD_D转换，最后输出RDD_E上做了一个动作，将结果输出。
但是细化到RDD内分区之间依赖，RDD_B对RDD_A的依赖，RDD_D对RDD_C的依赖是不一样，他们的区别用专业词汇来描述即为窄依赖和宽依赖。

所谓的窄依赖是说子RDD中的每一个数据分区只依赖于父RDD中的对应的有限个固定的数据分区，而宽依赖是指子RDD中的每个数据分区依赖于父RDD中的所有数据分区。 

宽依赖很好理解，但是对于窄依赖比较绕口，特别是定义中有限与固定两个要求，宽依赖也满足有限和固定这两个要求？难道他们俩个之间区别也仅仅在于“有限”这个数字的大小？
其实就是这样的理解，“有限”就表现为所依赖的分区数目相比完整分区数相差很大，而且spark靠窄依赖来实现的RDD基本上都大部分都是一对一的依赖，所以就不需要纠结这个有限的关键字。

这里还有一个问题，count操作是依赖父RDD的所有分区进行计算而得到，那么它是宽依赖吗？这么疑问，答案肯定就是否定的，首先这里依赖是父RDD和子RDD之间的关系描述，count操作只有输出，
没有子rdd的概念，就不要把依赖的关系硬套上给你带来麻烦。看上面的实现，count只是把sc.runJob计算返回的Array[U]做一次sum操作而已。

窄依赖和宽依赖的分类是Spark中很重要的特性，不同依赖在实现，任务调度机制，容错恢复上都有不同的机制。

+   实现上：对于窄依赖，rdd之间的转换可以直接pipe化，而宽依赖需要采用shuffle过程来实现。  
+   任务调度上：窄依赖意味着可以在某一个计算节点上直接通过父RDD的某几块数据（通常是一块）计算得到子RDD某一块的数据；
而相对的，宽依赖意味着子RDD某一块数据的计算必须等到它的父RDD所有数据都计算完成之后才可以进行，而且需要对父RDD的计算结果需要经过shuffle才能被下一个rdd所操作。  
+   容错恢复上：窄依赖的错误恢复会比宽依赖的错误恢复要快很多，因为对于窄依赖来说，只有丢失的那一块数据需要被重新计算，
而宽依赖意味着所有的祖先RDD中所有的数据块都需要被重新计算一遍，这也是我们建议在长“血统”链条特别是有宽依赖的时候，
需要在适当的时机设置一个数据检查点以避免过长的容错恢复。  

理清楚了Job层面RDD之间的关系，RDD层面分区之间的关系，那么下面讲述一下Stage概念。  
  
Stage的划分是对一个Job里面一系列RDD转换和动作进行划分。  

+   首先job是因动作而产生，因此每个job肯定都有一个ResultStage，否则job就不会启动。  
+   其次，如果Job内部RDD之间存在宽依赖，Spark会针对它产生一个中间Stage，即为ShuffleStage，严格来说应该是ShuffleMapStage，这个stage是针对父RDD而产生的，
相当于在父RDD上做一个父rdd.map().collect()的操作。ShuffleMapStage生成的map输入，对于子RDD，如果检测到所自己所“宽依赖”的stage完成计算，就可以启动一个shuffleFectch，
从而将父RDD输出的数据拉取过程，进行后续的计算。  

因此一个Job由一个ResultStage和多个ShuffleMapStage组成。

## 无Shuffle Job的执行过程
对一个无Shuffle的job执行过程的剖析可以知晓我们执行一个"动作"时,spark的处理流程. 下面我们就以一个简单例子进行讲解:

    sc.textFile("filepath").count
    //def count(): Long = sc.runJob(this, Utils.getIteratorSize _).sum   

这个例子很简单就是统计这个文件的行数;上面一行代码,对应了下面三个过程中:

+   sc.textFile("filepath")会返回一个rdd,
+   然后在这个rdd上做count动作,触发了一次Job的提交sc.runJob(this, Utils.getIteratorSize _)
+   对runJob返回的Array结构进行sum操作;

核心过程就是第二步,下面我们以代码片段的方式来描述这个过程,这个过程肯定是线性的,就用step来标示每一步,以及相关的代码类:

    //step1:SparkContext
    def runJob[T, U: ClassTag](
          rdd: RDD[T],
          func: (TaskContext, Iterator[T]) => U,
          partitions: Seq[Int],
          allowLocal: Boolean
          ): Array[U] = {
        val results = new Array[U](partitions.size)
        runJob[T, U](rdd, func, partitions, allowLocal, (index, res) => results(index) = res)
        results
    }

sc.runJob(this, Utils.getIteratorSize _)的过程会经过一组runJob的重载函数,进入上述step1中的runJob函数,相比原始的runJob,到达这边做的工作不多,比如设置partitions个数,
Utils.getIteratorSize _到func转化等,以后像这样简单的过程就不再描述.

Step1做的一个很重要的工作是构造一个Array,并构造一个函数对象"(index, res) => results(index) = res"继续传递给runJob函数,然后等待runJob函数运行结束,将results返回;
对这里的解释相当在runJob添加一个回调函数,将runJob的运行结果保存到Array到, 回调函数,index表示mapindex, res为单个map的运行结果,对于我们这里例子.res就为每个分片的
文件行数.

    //step2:SparkContext
    def runJob[T, U: ClassTag](
          rdd: RDD[T],
          func: (TaskContext, Iterator[T]) => U,
          partitions: Seq[Int],
          allowLocal: Boolean,
          resultHandler: (Int, U) => Unit) {
        if (dagScheduler == null) {
          throw new SparkException("SparkContext has been shutdown")
        }
        val callSite = getCallSite
        val cleanedFunc = clean(func)
        dagScheduler.runJob(rdd, cleanedFunc, partitions, callSite, allowLocal,
          resultHandler, localProperties.get)
        rdd.doCheckpoint()
      }

Step2中runJob就有一个resultHandler参数,这就是Step1构造的回调函数,dagScheduler是Spark里面最外层调度器,通过调用它的runJob函数,将相关参见传入到Spark调度器中.
只有Step1中的runJob函数的返回值有返回值,这里的runJob,包括dagScheduler.runJob都是灭有返回值的;返回是通过Step1的回调函数进行设置的.

>为什么我要一再强调返回值是通过Step1的回调函数来设置的?这个很重要,否则你都不知道spark调度的job的运行结果是怎么样被我们自己的逻辑代码所获取的!!

还有一点很重要,Step2是Step1以后的直接步骤,所以Step2中的dagScheduler.runJob是堵塞的操作,即直到Spark完成Job的运行之前,rdd.doCheckpoint()是不会执行的;

    //Step3:DAGScheduler
    def runJob[T, U: ClassTag](rdd: RDD[T],func: (TaskContext, Iterator[T]) => U,
          partitions: Seq[Int],callSite: CallSite,
          allowLocal: Boolean,resultHandler: (Int, U) => Unit,properties: Properties = null)
      {
        val start = System.nanoTime
        val waiter = submitJob(rdd, func, partitions, callSite, allowLocal, resultHandler, properties)
        waiter.awaitResult() match {
          case JobSucceeded => {
            logInfo("Job %d finished: %s, took %f s".format
              (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
          }
          case JobFailed(exception: Exception) =>
            logInfo("Job %d failed: %s, took %f s".format
              (waiter.jobId, callSite.shortForm, (System.nanoTime - start) / 1e9))
            throw exception
        }
      }

Step2中说了dagScheduler.runJob是堵塞的,堵塞就堵塞在Step3的waiter.awaitResult()操作,即submitJob会返回一个waiter对象,而我们的awaitResult()就堵塞了; 

到目前为止,我们终于从runJob这个多处出现的函数名称跳到submitJob这个函数名称;继续下一步

    //Step4:DAGScheduler
    def submitJob[T, U](){//省略了函数的参数
        val maxPartitions = rdd.partitions.length
        partitions.find(p => p >= maxPartitions || p < 0).foreach { p =>
          throw new IllegalArgumentException(
            "Attempting to access a non-existent partition: " + p + ". " +
              "Total number of partitions: " + maxPartitions)
        }
    
        val jobId = nextJobId.getAndIncrement()
        if (partitions.size == 0) {
          return new JobWaiter[U](this, jobId, 0, resultHandler)
        }
    
        assert(partitions.size > 0)
        val func2 = func.asInstanceOf[(TaskContext, Iterator[_]) => _]
        val waiter = new JobWaiter(this, jobId, partitions.size, resultHandler)
        eventProcessActor ! JobSubmitted(
              jobId, rdd, func2, partitions.toArray, allowLocal, callSite, waiter, properties)
        waiter
    }

在Step4的submitJob中,我们给这次job分配了一个jobID, 通过创建了一个JobWaiter对象,返回给Step3;最重要的步骤就是调用eventProcessActor ! JobSubmitted向DAG调度器
发送一个JobSubmitted的消息;

到目前为止我们都没有关系函数的参数,这里我们要分析一下发送的JobSubmitted的消息包:

+   jobId,rdd,func2,partitions.toArray这几个都比较好理解,就不阐述了
+   allowLocal:是否运行直接在本地完成job的运行,毕竟有些操作是没有必要通过task提交给spark运行,当然这里不是重点
+   callSite/properties:个人不是很感兴趣,姑且理解为不重要的
+   waiter就是上面创建的JobWaiter对象,这个很重要,因为这个对象封装了几个重要的参数:
    +   jobId:Job编号
    +   partitions.size:分区编号
    +   resultHandler:我们Step1设置的回调函数

>为什么JobWaiter重要,这个对象包含了我们分区的个数.我们知道分区的个数和task个数是相同的,因此JobWaiter成功返回的前提是:
>它接受到partitions.size个归属于jobid的task成功运行的结果,并通过resultHandler来将这些task运行结果回调给Step2的Array

这句话应该不难理解,其实这句话也包含了我们后面job调度的整体过程,
下面我们就一步一步来分析从job到Stage,到task以及直到task运行成功,调用我们的resultHandler回调的过程.

    //Step5:DAGScheduler
    private[scheduler] def handleJobSubmitted() {
        var finalStage: Stage = null
        try {
          finalStage = newStage(finalRDD, partitions.size, None, jobId, callSite)
          submitStage(finalStage)       
        }
      }

Step4发送的消息最后被Step5中的handleJobSubmitted函数进行处理,我这里删除了handleJobSubmitted中很多我们不关心的代码,Step5的核心代码就是创建一个finalStage,
并调用 submitStage将stage提交给Dag进行调度;这里我们从Job单位层面进入Stage层;

这个Stage命名很好:finalStage,它是整个DAG上的最后一个stage,它不是一个集合,而是单一的stage,这说明一个道理,runJob肯定只对应一个finalStage,即最终的输出肯定只有一个,
中间的stage就是我们传说中的shuffleStage,shuffleStage的生成就是在生成finalStage过程中生成的,即newStage.

那么我们就进入newStage这个函数,等一下我们还会回到submitStage,来分析怎么将Stage解析为Task提交给Spark进行运行;

    //Step5.1:DAGScheduler
    private def newStage(): Stage =
      {
        val parentStages = getParentStages(rdd, jobId)
        val id = nextStageId.getAndIncrement()
        val stage = new Stage(id, rdd, numTasks, shuffleDep, parentStages, jobId, callSite)
        stageIdToStage(id) = stage
        updateJobIdStageIdMaps(jobId, stage)
        stage
      }

Step5.1中首先是在当前的rdd上调用getParentStages来生成父Stage,父Stages是一个列表;我们这里分析的cache是没有Shuffle的,那么肯定就没有父Stage这个过程;我们就不深入
去分析这个过程;

然后就创建一个Stage对象,并更新Stage和job之间的关系.

下面我们要从维度5.1跳转到一个和执行流程无关的代码,即Stage类的实现,毕竟是Spark的核心对象,对它的理解还是很重要的;

    private[spark] class Stage(
        val id: Int,
        val rdd: RDD[_],
        val numTasks: Int,
        val shuffleDep: Option[ShuffleDependency[_, _, _]],  // Output shuffle if stage is a map stage
        val parents: List[Stage],
        val jobId: Int,
        val callSite: CallSite){
    }

首先我们看Stage几个参数,其中shuffleDep和parents最为重要,首先如果一个Stage的shuffleDep不为空,那么当前的Stage是因为shuffleMap输出而生成的Stage;

>怎么解释呢?就是这个Stage生成原因吧;因为下游rdd对当前的rdd有这个依赖而生成需要为当前的rdd生成一个Stage,而对于FinalStage,shuffleDep就为空,

parents参数就是父RDD列表,当前rdd被调度的前提是所有的父Stage都调度完成;那么

        





