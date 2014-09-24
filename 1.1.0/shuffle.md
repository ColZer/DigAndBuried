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

map操作只是在当前的rdd的基础上创建一个MappedRDD对象，而count操作会调用sc.runJob向spark提交一个job  
Job是一组rdd的转换以及最后的动作的操作集合，它是Spark里面计算最大最虚的概念，甚至在spark的任务页面中都无法看到job这个单位。
但是不管怎么样，在spark用户的角度，job是我们计算目标的单位，每次在一个rdd上做动作操作时，都会触发一个job。  

job既然是由一组RDD上转换和动作组成，那么job内部肯定是由一组RDD组成的有向无环图(DAG)，图中每个节点都为一个RDD，RDD之间的连接线为
转换操作，图有一组输入和输出，如下图所示。  
![job to stage1][job2stage]
[job2stage]:../image/job2stage.jpg


