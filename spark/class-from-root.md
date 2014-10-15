# 根目录下面的基本功能研究

## 编译
相比以前版本，spark1.1版本对编译做了很多限制，老的命令是无法直接编译命令，详细编译命令[参考](http://spark.apache.org/docs/latest/building-with-maven.html)  
我们自己的执行命令  

    sh make-distribution.sh --tgz -Phadoop-2.2 -Dhadoop.version=2.2.0 -Pyarn -Phive  
## stage和task
1.1.0版本引入attempt概念，即task一次执行的实例；那么一个task的实际运行结果由**stageid**，**partitionID**，**attempID**三个维度来标识。  
task的状态由是否完成isCompleted以及是否失败isInterrupted来表示；同时每个task可以添加TaskCompletionListener，当task完成时候，会执行每个Listener的注册函数。参考代码：TaskContext
task失败原因由TaskEndReason和TaskFailedReason以及下面的case类来表示，TaskEndReason包括成功；TaskFailedReason包括执行器丢失，fetch失败，重新提交，异常，执行结果丢失，被kill，未知失败。参考代码：TaskEndReason
其中Task的状态，可以参考TaskState单例对象，并且针对Mesos做了适配。参考代码TaskState

