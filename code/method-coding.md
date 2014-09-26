# Spark 闭包中ClosureCleaner操作

在Scala，函数是第一等公民，可以作为参数的值传给相应的rdd转换和动作，进而进行迭代处理。
阅读spark源码，我们发现，spark对我们所传入的所有闭包函数都做了一次sc.clean操作，如下

    def map[U: ClassTag](f: T => U): RDD[U] = new MappedRDD(this, sc.clean(f))
    private[spark] def clean[F <: AnyRef](f: F, checkSerializable: Boolean = true): F = {
        ClosureCleaner.clean(f, checkSerializable)
        f
    }
函数clean对闭包做了一次清理的操作，那么什么是闭包清理呢？

## 闭包
我们首先看ClosureCleaner里面一个函数：

    // Check whether a class represents a Scala closure
    private def isClosure(cls: Class[_]): Boolean = {
        cls.getName.contains("$anonfun$")
    }
该函数用来检测一个Class是不是闭包类，我们看到，如果一个对象的Class-name包含"$anonfun$",那么它就是一个闭包。再看一个实例：

    //BloomFilter.scala这个文件里面有一个contains函数，函数内部使用了一个匿名函数：
    def contains(data: Array[Byte], len: Int): Boolean = {
        !hash(data,numHashes, len).exists {
          h => !bitSet.get(h % bitSetSize)       //这里是一个匿名函数
        } 
     }
对BloomFilter.scala进行编译，我们会发现，它会针对这个匿名函数生成一个"BloomFilter$$anonfun$contains$1"Class，对于该类，spark将其识别闭包。

那么闭包到底是什么？

> 在计算机科学中，闭包（Closure）是词法闭包（Lexical Closure）的简称，是引用了自由变量的函数。
> 这个被引用的自由变量将和这个函数一同存在，即使已经离开了创造它的环境也不例外。
> 所以，有另一种说法认为闭包是由函数和与其相关的引用环境组合而成的实体。
> 闭包在运行时可以有多个实例，不同的引用环境和相同的函数组合可以产生不同的实例。

从上面的描述来看，闭包本身就是类，它的特点是它所创建的对象实例可以引用outer函数/类里面的变量。
朴素的说法就是：闭包就是能够读取外部函数的内部变量的函数。

另外，在本质上匿名函数和闭包是不同的概念，但是匿名函数一般都会被outer函数所包含，它有读取outer函数变量的能力，因此可以简单的把匿名函数理解为闭包。
哪怕匿名函数什么事情都没有。这也spark判断一个对象是否为闭包对象，只需要检查他的classname里面是否包含$anonfun$字符串。

简单的总结一下：闭包就是拥有对outer函数/类的变量的引用，从而可以在外面函数栈执行结束以后，依然握有外面函数栈/堆变量的引用，并可以改变他们的值。
说到这里，相信大家也看到闭包有对外部变量的引用的能力，这个能力是有潜在风险的。首先它会影响变量的GC，另外他会影响函数对象的序列化。
再回头看一下clean函数第三个参数checkSerializable: Boolean = true，即是否检查序列化的问题，默认是true。
在scala中函数对象都是可以被序列化，从而可以传输到各个slave中进行计算，
但是如果一个函数对象引用了outer函数/对象的变量是不可以被序列化，那么就导致整个函数对象序列化失败。

     
