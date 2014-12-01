关于Scala的implicit(隐式转换)的思考
==========

隐式转换是Scala的一大特性, 如果对其不是很了解, 在阅读Spark代码时候就会很迷糊,有人这样问过我?

>   RDD这个类没有reduceByKey,groupByKey等函数啊,并且RDD的子类也没有这些函数,但是好像PairRDDFunctions这个类里面好像有这些函数
>   为什么我可以在RDD调用这些函数呢?

答案就是Scala的隐式转换; 如果需要在RDD上调用这些函数,有两个前置条件需要满足:

+   首先rdd必须是RDD[(K, V)], 即pairRDD类型
+   需要在使用这些函数的前面Import org.apache.spark.SparkContext._;否则就会报函数不存在的错误;

参考SparkContext Object, 我们发现其中有上10个xxToXx类型的函数:

     implicit def intToIntWritable(i: Int) = new IntWritable(i)    
     implicit def longToLongWritable(l: Long) = new LongWritable(l)    
     implicit def floatToFloatWritable(f: Float) = new FloatWritable(f)
     implicit def rddToPairRDDFunctions[K, V](rdd: RDD[(K, V)])
          (implicit kt: ClassTag[K], vt: ClassTag[V], ord: Ordering[K] = null) = {
        new PairRDDFunctions(rdd)
     }

这么一组函数就是隐式转换,其中rddToPairRDDFunctions,就是实现:隐式的将RDD[(K, V)]类型的rdd转换为PairRDDFunctions对象,从而可以在原始的rdd对象上
调用reduceByKey之类的函数;类型隐式转换是在需要的时候才会触发,如果我调用需要进行隐式转换的函数,隐式转换才会进行,否则还是传统的RDD类型的对象;

还说一个弱智的话,这个转换不是可逆的;除非你提供两个隐式转换函数; 这是你会说,为什么我执行reduceByKey以后,返回的还是一个rdd对象呢? 这是因为reduceByKey函数
是PairRDDFunctions类型上面的函数,但是该函数会返回一个rdd对象,从而在用户的角度无法感知到PairRDDFunctions对象的存在,从而精简了用户的认识,
不知晓原理的用户可以把reduceByKey,groupByKey等函数当着rdd本身的函数

上面是对spark中应用到隐式类型转换做了分析,下面我就隐式转换进行总结;

从一个简单例子出发,我们定义一个函数接受一个字符串参数,并进行输出

    def func(msg:String) = println(msg)
    
这个函数在func("11")调用时候正常,但是在执行func(11)或func(1.1)时候就会报error: type mismatch的错误. 这个问题很好解决

+   针对特定的参数类型, 重载多个func函数,这个不难, 传统JAVA中的思路, 但是需要定义多个函数
+   使用超类型, 比如使用AnyVal, Any;这样的话比较麻烦,需要在函数中针对特定的逻辑做类型转化,从而进一步处理

上面两个方法使用的是传统JAVA思路,虽然都可以解决该问题,但是缺点是不够简洁;在充满了语法糖的Scala中, 针对类型转换提供了特有的implicit隐式转化的功能;

隐式转化是一个函数, 可以针对一个变量在需要的时候,自动的进行类型转换;针对上面的例子,我们可以定义intToString函数
    
     implicit def intToString(i:Int)=i.toString

此时在调用func(11)时候, scala会自动针对11进行intToString函数的调用, 从而实现可以在func函数已有的类型上提供了新的类型支持,这里有几点要说一下:

+   隐式转换的核心是from类型和to类型, 至于函数名称并不重要;上面我们取为intToString,只是为了直观, int2str的功能是一样的;隐式转换函数只关心from-to类型之间的匹配
比如我们需要to类型,但是提供了from类型,那么相应的implicit函数就会调用
+   隐式转换只关心类型,所以如果同时定义两个隐式转换函数,from/to类型相同,但是函数名称不同,这个时候函数调用过程中如果需要进行类型转换,就会报ambiguous二义性的错误,
即不知道使用哪个隐式转换函数进行转换

上面我们看到的例子是将函数的参数从一个类型自动转换为一个类型的例子,在Scala中, 除了针对函数参数类型进行转换以外,还可以对函数的调用者的类型进行转换.

比如A+B,上面我们谈到是针对B进行类型自动转换, 其实可以在A上做类型转换,下面我们拿一个例子来说明

    class IntWritable(_value:Int){
      def value = _value
      def +(that:IntWritable): IntWritable ={
        new IntWritable(that.value + value)
      }
    }
    implicit  def intToWritable(int:Int)= new IntWritable(int)
    new IntWritable(10) + 10

上面我们首先定义了一个类:IntWritable, 并为int提供了一个隐式类型转换intToWritable, 从而可以使得IntWritable的+函数在原先只接受IntWritable类型参数的基础上,
接受一个Int类型的变量进行运算,即new IntWritable(10) + 10可以正常运行

现在换一个角度将"new IntWritable(10) + 10" 换为"10 + new IntWritable(10)"会是什么结果呢?会报错误吗?

按道理是应该报错误,首先一个Int内置类型的+函数,没有IntWritable这个参数类型; 其次,我们没有针对IntWritable类型提供到Int的隐式转换, 即没有提供writableToInt的implicit函数.

但是结果是什么?10 + new IntWritable(10)的是可以正常运行的,而且整个表达的类型为IntWritable,而不是Int, 即Int的10被intToWritable函数隐式函数转换为IntWritable类型;

结论:隐式转换可以针对函数参数类型和函数对象进行类型转换; 现在问题来了,看下面的例子

    implicit  def intToWritable(int:Int)= new IntWritable(int)
    implicit  def writableToInt(that:IntWritable)=that.value
    
    val result1 = new IntWritable(10) + 10
    val result2 = 10 + new IntWritable(10)

在上面的IntWritable类的基础上,我们提供了两个隐式类型转换函数, 即Int和IntWritable之间的双向转换;这样的情况下result1和result2两个变量的类型是什么?

答案:result1的类型为IntWritable, result2的类型Int;很好理解, result1中的Int类型的10被intToWritable隐式转换为IntWritable;而result2中的IntWritable(10)被writableToInt
隐式转换为Int类型;

你肯定会问?result2中为什么不是像上面的例子一样, 把Int类型的10隐式转换为IntWritable类型呢?原因就是隐式转换的优先级; 

>    发生类型不匹配的函数调用时, scala会尝试进行类型隐式转换;首先优先进行函数参数的类型转换,如果可以转换, 那么就完成函数的执行;
>   否则尝试去对函数调用对象的类型进行转换; 如果两个尝试都失败了,就会报方法不存在或者类型不匹配的错误;

OK, Scala的隐式转换是Scala里面随处可见的语法, 在Spark中也很重要, 这里对它的讲解,算是对Shuffle做一个补充了, 即一个RDD之所以可以进行基于Key的Shuffle操作
是因为RDD被隐式转换为PairRDDFunctions类型.
