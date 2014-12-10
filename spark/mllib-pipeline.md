MLLib Pipeline的实现分析
===============

在北京的最近一次Spark MeetUp中,期间连线孟祥瑞谈到下一步MLLib计划时, 它说到MLLib未来的发展更加注重可实践性,每一个被MLLib的所收录的算法都是可以解决企业中真实的问题.
另外谈到要把MLLine做的更加灵活,其中核心功能就是pipeline;
 
 在2014年11月,他就在Spark MLLib代码中CI了一个全新的package:"org.apache.spark.ml", 和传统的"org.apache.spark.mllib"独立, 这个包即Spark MLLib即
[Pipeline and Parameters](https://docs.google.com/document/d/1rVwXRjWKfIb-7PI6b86ipytwbUH7irSNLF1_6dLmh8o/edit#heading=h.kaihowy4sg6c)

pipeline即机器学习流水线, 在实际的机器学习应用过程中,一个机器学习和数据挖掘的任务(Job)由很多过程(Stage)组成, 比如日志的收集,清理,到字段/属性/feature的提取, 多算法的组合而成的模型,
模型的离线训练和评估,到最后的模型在线服务和在线评估,所进行的每一个stage都可以概况为数据到数据的转换以及数据到模型的转换. 后面我们会看到,mllib对pipeline中的stage做了相应的划分.

Parameters即参数化,机器学习大部分的过程是一个参数调优的过程,一个模型应该很显示的告诉使用者,模型有那些参数可以进行设置,以及这些参数的默认值是怎么样的;在现有的机器学习算法中,
模型的参数可能是以模型类字段和通过一些函数进行设置,不能很直观的了解当前模型有哪些可以调优的参数;针对这个问题,在这个版本中,提出了Parameters的功能,通过train的方式显示的指定了
模型或者其他stage拥有那些参数.

下面我们就针对这两个部分进行分析, pipeline到我今天学习为止,还只有三个PR,很多东西应该还在实验中,这里做一个分析仅供学习和备忘.

##Parameters

    class LogisticRegressionModel (
        override val weights: Vector,
        override val intercept: Double)
      extends GeneralizedLinearModel(weights, intercept) with ClassificationModel with Serializable {
    
      private var threshold: Option[Double] = Some(0.5)

上面是传统的org.apache.spark.mllib包中一个分类器:LogisticRegressionModel, 如果理解logistics分类器,那么我知道其中的threshold为模型一个很重要的参数.
但是从对于一般的用户来说,我们只知道这个模型类总有一个threshold字段,并不能很清楚了该字段是否是模型可调优的参数,还是只是类的一个"全局变量"而已;

针对这个问题, 就有了Parameters参数化的概念,先直接看结果:

    class LogisticRegressionModel private[ml] (
        override val parent: LogisticRegression,
        override val fittingParamMap: ParamMap,
        weights: Vector)
      extends Model[LogisticRegressionModel] with LogisticRegressionParams {
    
    private[classification] trait LogisticRegressionParams extends Params
      with HasRegParam with HasMaxIter with HasLabelCol with HasThreshold with HasFeaturesCol
      with HasScoreCol with HasPredictionCol {

我们看到这里的LogisticRegressionModel实现了LogisticRegressionParams,而LogisticRegressionParams继承了Params类,并且实现了一组Param,即HasMaxIter, HasRegParam之类.
从这块我们可以清楚的看到,我们的LogisticRegressionModel模型有RegParam, MaxIter等7个可控参数,其中包括我们上面谈到的threshold参数, 即HasThreshold.

从上面我们看到, 基于Parameters的功能, 将mllib中的模型参数进行标准化和可视化,下面我们继续详细的针对Parameters进行分析.

    class Param[T] (val parent: Params,val name: String,val doc: String,val defaultValue: Option[T] = None)extends Serializable {

Param表示一个参数,从概念上来说,一个参数有下面几个属性:

+   param的类型:即上面的[T], 它表示param值是何种类型
+   param的名称:即name
+   param的描述信息,和name相比, 它可以更长更详细, 即doc
+   param的默认值, 即defaultValue

针对param的类型,ml提供了一组实现, 如IntParam,FloatParam之类的.

另外针对Param, 提供了接口来设置Param的值

      def w(value: T): ParamPair[T] = this -> value
      def ->(value: T): ParamPair[T] = ParamPair(this, value)
      case class ParamPair[T](param: Param[T], value: T)

即将Param和value封装为paramPair类, paramPair是一个case类,没有其他的方法, 仅仅封装了Param和Value对. 因此我们可以通过param->value来创建一个ParamPair, 后面我们会看到怎么对ParamPair
进行管理.

上面谈到Param其中一个参数我们没有进行描述, 即"parent: Params". 如果我们站在模型, 特征提取器等角度来, 这些组件应该是一个param的容器,它们的逻辑代码可以使用自身的容器中的param.
换句话说,如果一个组件继承自Params类,那么该组件就是一个被参数化的组件.

参考上面的LogisticRegressionModel, 它继承了LogisticRegressionParams, 而LogisticRegressionParams实现了Params, 此时LogisticRegressionModel就是一个被参数化的模型.

现在问题来了, 一个组件继承自Params, 然而我们没有通过add等方式来将相应的param添加到这个容器里面, 而是通过"with HasRegParam"方式来进行设置的,到底是怎么实现的呢?
那么我们就要看一个具体的"HasRegParam"之类的param是怎么实现

    private[ml] trait HasRegParam extends Params {
      val regParam: DoubleParam = new DoubleParam(this, "regParam", "regularization parameter")
      def getRegParam: Double = get(regParam)
    }
    
    private[ml] trait HasMaxIter extends Params {
      val maxIter: IntParam = new IntParam(this, "maxIter", "max number of iterations")
      def getMaxIter: Int = get(maxIter)
    }

我们看到每个具体的RegParam都是继承自Params, 这个继承感觉意义不大,所有这里就不纠结它的继承机制, 核心是它的val regParam: DoubleParam常量的定义,这里的常量会被编译为一个函数,
其中函数返回值为DoubleParam, 参数为空. 为什么要强调这个呢?这是规范. 一个具体的Param只有这样的实现, 它才会被组件的Params容器捕获. 怎么捕获呢? 在Params中有这样一个代码:
    
    def params: Array[Param[_]] = {
        val methods = this.getClass.getMethods
        methods.filter { m =>
            Modifier.isPublic(m.getModifiers) &&
              classOf[Param[_]].isAssignableFrom(m.getReturnType) &&
              m.getParameterTypes.isEmpty
          }.sortBy(_.getName)
          .map(m => m.invoke(this).asInstanceOf[Param[_]])
      }

这里就清晰了,一个组件,  继承Params类, 成为一个可参数化的组件, 该组件就有一个params, 返回该组件所有配置信息,即 Array[Param[_]]. 因为我们组件同With方式继承了符合上面规范的"常量定义",
这些会被这里的def params所捕获, 从而返回该组件所有的Params;

不过还有一个问题,我们一直在说, 组件继承Params类, 成为一个可参数化的组件,但是我这里def params只是返回 Array[Param[_]], 而一个属性应该有Param和value组成, 即上面我们谈到ParamPair,
因此Params类中应该还有一个容器,维护Array[ParamPair], 或者还有一个Map,维护param->value对.

没错,这就是ParamMap的功能, 它维护了维护param->value对.并且每个实现Params的组件都有一个paramMap字段. 如下:

    trait Params extends Identifiable with Serializable {
        protected val paramMap: ParamMap = ParamMap.empty
    }

具体的ParamMap实现这里就不分析, 就是一个Map的封装, 这里我们总结一下Parameters的功能: 

>   Parameters即组件的可参数化, 一个组件,可以是模型,可以是特征选择器等, 如果继承自Params, 那么它就是被参数化的组件, 将具体参数按照HasMaxIter类的规范进行定义, 然后通过With的
>   方式追加到组件中,从而表示该组件有相应的参数, 并通过调用Params中的getParam,set等方法来操作param.

整体来说Parameters的功能就是组件参数的标准化

### Pipeline






