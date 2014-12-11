MLLib Pipeline的实现分析
===============

在北京的最近一次Spark MeetUp中,期间连线孟祥瑞谈到下一步MLLib发展计划时, 他说到MLLib未来的发展更加注重可实践性,每一个被MLLib的所收录的算法都是可以解决企业中真实的问题.
另外谈到要把MLLine做的更加灵活,其中核心功能就是流水线, 另外还谈到将Spark Sql中schemeRDD引入到mllib中, 这两个功能即今天要分析的MLLib Pipeline的实现
 
 在2014年11月,他就在Spark MLLib代码中CI了一个全新的package:"org.apache.spark.ml", 和传统的"org.apache.spark.mllib"独立, 这个包即Spark MLLib的
[Pipeline and Parameters](https://docs.google.com/document/d/1rVwXRjWKfIb-7PI6b86ipytwbUH7irSNLF1_6dLmh8o/edit#heading=h.kaihowy4sg6c)

pipeline即机器学习流水线, 在实际的机器学习应用过程中,一个任务(Job)由很多过程(Stage)组成, 比如日志的收集,清理,到字段/属性/feature的提取, 多算法的组合而成的模型,
模型的离线训练和评估,到最后的模型在线服务和在线评估,所进行的每一个stage都可以概况为数据到数据的转换以及数据到模型的转换. 

后面我们会看到,mllib pipeline中的stage也做了相应的划分.

Parameters即参数化,机器学习大部分的过程是一个参数调优的过程,一个模型应该很显示的告诉使用者,模型有那些参数可以进行设置,以及这些参数的默认值是怎么样的;在现有的机器学习算法中,
模型的参数可能是以模型类字段或通过一些函数进行设置,不是很直观的展现当前模型有哪些可以调优的参数;

针对这个问题,在这个版本中,提出了Parameters功能,通过trait的方式显式地指定stage拥有那些参数.

下面我们就针对这两个部分进行分析, pipeline到我今天学习为止,还只有三个PR,很多东西应该还在实验中,这里做一个分析仅供学习和备忘.

##Parameters

    class LogisticRegressionModel (
        override val weights: Vector,
        override val intercept: Double)
      extends GeneralizedLinearModel(weights, intercept) with ClassificationModel with Serializable {
    
      private var threshold: Option[Double] = Some(0.5)

上面是传统的org.apache.spark.mllib包中一个分类器:LogisticRegressionModel, 如果理解logistics分类器,那么我知道其中的threshold为模型一个很重要的参数.
但是从对于一般的用户来说,我们只知道这个模型类中有一个threshold字段,并不能很清楚了该字段是否是模型可调优的参数,还是只是类的一个"全局变量"而已;

针对这个问题, 就有了Parameters参数化的概念,先直接看结果:

    class LogisticRegressionModel private[ml] (
        override val parent: LogisticRegression,
        override val fittingParamMap: ParamMap,
        weights: Vector)
      extends Model[LogisticRegressionModel] with LogisticRegressionParams {
    
    private[classification] trait LogisticRegressionParams extends Params
      with HasRegParam with HasMaxIter with HasLabelCol with HasThreshold with HasFeaturesCol
      with HasScoreCol with HasPredictionCol {

我们看到这里的LogisticRegressionModel实现了LogisticRegressionParams,而LogisticRegressionParams继承了Params类,并且"拥有"一组Param,即HasMaxIter, HasRegParam之类.
相比传统的LogisticRegressionModel, 这个版本我们可以清楚的看到,该模型有RegParam, MaxIter等7个可控参数,其中包括我们上面谈到的threshold参数, 即HasThreshold.

即Parameters 将mllib中的组件参数进行标准化和可视化,下面我们继续针对Parameters进行分析.

    class Param[T] (val parent: Params,val name: String,val doc: String,
        val defaultValue: Option[T] = None)extends Serializable {

Param表示一个参数,从概念上来说,一个参数有下面几个属性:

+   param的类型:即上面的[T], 它表示param值是何种类型
+   param的名称:即name
+   param的描述信息,和name相比, 它可以更长更详细, 即doc
+   param的默认值, 即defaultValue

针对param的类型,ml提供了一组默认的子类, 如IntParam,FloatParam之类的.就不详细展开

另外针对Param, 提供了接口来设置Param的值

      def w(value: T): ParamPair[T] = this -> value
      def ->(value: T): ParamPair[T] = ParamPair(this, value)
      case class ParamPair[T](param: Param[T], value: T)

即将Param和value封装为paramPair类, paramPair是一个case类,没有其他的方法, 仅仅封装了Param和Value对. 因此我们可以通过param->value来创建一个ParamPair, 后面我们会看到怎么对ParamPair
进行管理.

上面谈到Param其中一个参数我们没有进行描述, 即"parent: Params". 如果站在模型, 特征提取器等组件角度来, 它们应该是一个param的容器,它们的逻辑代码可以使用自身的容器中的param.
换句话说,如果一个组件继承自Params类,那么该组件就是一个被参数化的组件.

参考上面的LogisticRegressionModel, 它继承了LogisticRegressionParams, 而LogisticRegressionParams实现了Params, 此时LogisticRegressionModel就是一个被参数化的模型. 即自身是一个param容器

现在问题来了, 一个组件继承自Params, 然而我们没有通过add等方式来将相应的param添加到这个容器里面, 而是通过"with HasRegParam"方式来进行设置的,到底是怎么实现的呢?看一个具体param的实现

    private[ml] trait HasRegParam extends Params {
      val regParam: DoubleParam = new DoubleParam(this, "regParam", "regularization parameter")
      def getRegParam: Double = get(regParam)
    }
    
    private[ml] trait HasMaxIter extends Params {
      val maxIter: IntParam = new IntParam(this, "maxIter", "max number of iterations")
      def getMaxIter: Int = get(maxIter)
    }

我们看到每个具体的RegParam都是继承自Params, 这个继承感觉意义不大,所有这里就不纠结它的继承机制, 核心是它的val regParam: DoubleParam常量的定义,这里的常量会被编译为一个函数,
其中函数为public, 返回值为DoubleParam, 参数为空. 为什么要强调这个呢?这是规范. 一个具体的Param只有这样的实现, 它才会被组件的Params容器捕获. 怎么捕获呢? 在Params中有这样一个代码:
    
    def params: Array[Param[_]] = {
        val methods = this.getClass.getMethods
        methods.filter { m =>
            Modifier.isPublic(m.getModifiers) &&
              classOf[Param[_]].isAssignableFrom(m.getReturnType) &&
              m.getParameterTypes.isEmpty
          }.sortBy(_.getName)
          .map(m => m.invoke(this).asInstanceOf[Param[_]])
      }

这里就清晰了,一个组件,  继承Params类, 成为一个可参数化的组件, 该组件就有一个params方法可以返回该组件所有配置信息,即 Array[Param[_]]. 因为我们组件使用With方式继承了符合上面规范的"常量定义",
这些param会被这里的def params所捕获, 从而返回该组件所有的Params;

不过还有一个问题,我们一直在说, 组件继承Params类, 成为一个可参数化的组件,但是我这里def params只是返回 Array[Param[_]], 而一个属性应该有Param和value组成, 即上面我们谈到ParamPair,
因此Params类中应该还有一个容器,维护Array[ParamPair], 或者还有一个Map,维护param->value对.

没错,这就是ParamMap的功能, 它维护了维护param->value对.并且每个实现Params的组件都有一个paramMap字段. 如下:

    trait Params extends Identifiable with Serializable {
        protected val paramMap: ParamMap = ParamMap.empty
    }

具体的ParamMap实现这里就不分析, 就是一个Map的封装, 这里我们总结一下Parameters的功能: 

>   Parameters即组件的可参数化, 一个组件,可以是模型,可以是特征选择器, 如果继承自Params, 那么它就是被参数化的组件, 将具体参数按照HasMaxIter类的规范进行定义, 然后通过With的
>   方式追加到组件中,从而表示该组件有相应的参数, 并通过调用Params中的getParam,set等方法来操作相应的param.

整体来说Parameters的功能就是组件参数标准化

### Pipeline
如上所言, mllib pipeline是基于Spark SQL中的schemeRDD来实现, pipeline中每一次处理操作都表现为对schemeRDD的scheme或数据进行处理, 这里的操作步骤被抽象为Stage,
即PipelineStage

    abstract class PipelineStage extends Serializable with Logging {
      private[ml] def transformSchema(schema: StructType, paramMap: ParamMap): StructType
    }

抽象的PipelineStage的实现很简单, 只提供了transformSchema虚函数, 由具体的stage进行实现,从而在一定参数paramMap作用下,对scheme进行修改(transform). 

上面我们也谈到, 具体的stage是在PipelineStage基础上划分为两大类, 即数据到数据的转换(transform)以及数据到模型的转换(fit).
 
+   Transformer: 数据到数据的转换
+   Estimator:数据到模型的转换


我们首先来看Transformer, 数据预处理, 特征选择与提取都表现为Transformer, 它对提供的SchemaRDD进行转换生成新的SchemaRDD, 如下所示:

    abstract class Transformer extends PipelineStage with Params {
        def transform(dataset: SchemaRDD, paramMap: ParamMap): SchemaRDD
    }

在mllib中, 有一种特殊的Transformer, 即模型(Model), 下面我们会看到模型是Estimator stage的产物,但是model本身是一个Transformer, 模型是经过训练和挖掘以后的一个
对象, 它的一个主要功能就是预测/推荐服务, 即它可以对传入的dataset:SchemaRDD进行预测, 填充dataset中残缺的决策字段或评分字段, 返回更新后的SchemaRDD

Estimator stage的功能是模型的估计/训练, 即它是一个SchemaRDD到Model的fit过程. 如下所示fit接口. 

    abstract class Estimator[M <: Model[M]] extends PipelineStage with Params {
      def fit(dataset: SchemaRDD, paramMap: ParamMap): M
    }

模型训练是整个数据挖掘和机器学习的目标, 如果把整个过程看着为一个黑盒, 内部可以包含了很多很多的特征提取, 模型训练等子stage, 但是站在黑盒的外面,
整个黑盒的输出就是一个模型(Model), 我们目标就是训练出一个完美的模型, 然后再利于该模型去做服务. 

这句话就是pipeline的核心, 首先pipeline是一个黑盒生成的过程, 它对外提供fit接口, 完成黑盒的训练, 生成一个黑盒模型, 即PipelineModel

如果站在白盒的角度来看, pipeline的黑盒中肯定维护了一组stage, 这些stage可以是原子的stage,也可能继续是一个黑盒模型, 在fit接口调用时候, 会按照流水线的
顺序依次调用每个stage的fit/transform函数,最后输出PipelineModel.  

下面我们就来分析 pipeline和PipelineModel具体的实现.

    class Pipeline extends Estimator[PipelineModel] {
    
      val stages: Param[Array[PipelineStage]] = new Param(this, "stages", "stages of the pipeline")
      def setStages(value: Array[PipelineStage]): this.type = { set(stages, value); this }
      def getStages: Array[PipelineStage] = get(stages)
      
      override def fit(dataset: SchemaRDD, paramMap: ParamMap): PipelineModel = {
      }
    
      private[ml] override def transformSchema(schema: StructType, paramMap: ParamMap): StructType = {
          val map = this.paramMap ++ paramMap
          val theStages = map(stages)
          require(theStages.toSet.size == theStages.size,
            "Cannot have duplicate components in a pipeline.")
          theStages.foldLeft(schema)((cur, stage) => stage.transformSchema(cur, paramMap))
        }
    }

Pipeline首先是一个Estimator, fit输出的模型为PipelineModel,  其次Pipeline也继承Params类, 即被参数化, 其中有一个参数, 即stages, 它的值为Array[PipelineStage],
即该参数存储了Pipeline拥有的所有的stage;

Pipeline提供了fit和transformSchema两个接口,其中transformSchema接口使用foldLeft函数, 对schema进行转换.但是对fit接口的理解,需要先对PipelineModel进行理解,
分析完PipelineModel,我们再回头来看fit接口的实现. 先多说一句, 虽然pipeline里面的元素都是stage, 但是两种不同类型stage在里面功能不一样, 所在位置也会有不同的结果,
不过这点还是挺好理解的. 

    class PipelineModel private[ml] (
        override val parent: Pipeline,
        override val fittingParamMap: ParamMap,
        private[ml] val stages: Array[Transformer])
      extends Model[PipelineModel] with Logging {
          
      override def transform(dataset: SchemaRDD, paramMap: ParamMap): SchemaRDD = {
        // Precedence of ParamMaps: paramMap > this.paramMap > fittingParamMap
        val map = (fittingParamMap ++ this.paramMap) ++ paramMap
        transformSchema(dataset.schema, map, logging = true)
        stages.foldLeft(dataset)((cur, transformer) => transformer.transform(cur, map))
      }
    }

我们看到PipelineModel是由一组Transformer组成, 在对dataset进行预测(transform)时,  是按照Transformer的有序性(Array)逐步的对dataset进行处理. 换句话说, Pipeline的
输出是一组Transformer, 进而构造成PipelineModel. 那么我再回头来看看Pipeline的fit接口. 

    override def fit(dataset: SchemaRDD, paramMap: ParamMap): PipelineModel = {
        transformSchema(dataset.schema, paramMap, logging = true)
        val map = this.paramMap ++ paramMap
        val theStages = map(stages)
        // Search for the last estimator.
        var indexOfLastEstimator = -1
        theStages.view.zipWithIndex.foreach { case (stage, index) =>
          stage match {
            case _: Estimator[_] =>
              indexOfLastEstimator = index
            case _ =>
          }
        }
        var curDataset = dataset
        val transformers = ListBuffer.empty[Transformer]
        theStages.view.zipWithIndex.foreach { case (stage, index) =>
          if (index <= indexOfLastEstimator) {
            val transformer = stage match {
              case estimator: Estimator[_] =>
                estimator.fit(curDataset, paramMap)
              case t: Transformer =>
                t
              case _ =>
                throw new IllegalArgumentException(
                  s"Do not support stage $stage of type ${stage.getClass}")
            }
            curDataset = transformer.transform(curDataset, paramMap)
            transformers += transformer
          } else {
            transformers += stage.asInstanceOf[Transformer]
          }
        }
    
        new PipelineModel(this, map, transformers.toArray)
      }

拿实例来解析:

    Transformer1 ---> Estimator1 --> Transformer2 --> Transformer3 --> Estimator2 --> Transformer4

我们知道Estimator和Transformer的区别是, Estimator需要经过一次fit操作, 才会输出一个Transformer, 而Transformer就直接就是Transformer;

对于训练过程中的Transformer,只有一个数据经过Transformer操作后会被后面的Estimator拿来做fit操作的前提下,该Transformer操作才是有意义的,否则训练数据不应该经过该Transformer.

拿上面的Transformer4来说,  它后面没有Estimator操作, 如果对训练数据进行Transformer操作没有任何意义,但是在预测数据上还是有作用的,所以它可以直接用来构建PipelineModel.

对于训练过程中Estimator(非最后一个Estimator), 即上面的Estimator1,非Estimator2, 它训练数据上训练出的模型以后, 需要利用该模型对训练数据进行transform操作,输出的数据
继续进行后面Estimator和Transformer操作. 

拿缺失值填充的例子来说, 我们可以利用当前数据,训练出一个模型, 该模型计算出每个字段的平均值, 然后我们理解利用这个模型对训练数据进行缺失值填充. 

> 但是对于最后一个Estimator,其实是没有必要做这个"curDataset = transformer.transform(curDataset, paramMap)"操作的, 所以这里还是可以有优化的!!嘿嘿!!!

总结:好了,到目前为止,已经将Pipeline讲解的比较清楚了, 利用Pipeline可以将数据挖掘中各个步骤进行流水线化, api方便还是很简介清晰的!

最后拿孟祥瑞CI的描述信息中一个例子做结尾,其中pipeline包含两个stage,顺序为StandardScaler和LogisticRegression

    val scaler = new StandardScaler() 
        .setInputCol("features") 
        .setOutputCol("scaledFeatures") 
    val lr = new LogisticRegression() 
        .setFeaturesCol(scaler.getOutputCol) 
        
    val pipeline = new Pipeline() 
        .setStages(Array(scaler, lr)) 
    val model = pipeline.fit(dataset) 
    val predictions = model.transform(dataset) 
        .select('label, 'score, 'prediction)
    

@end








