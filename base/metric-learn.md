Metric系统的剖析
====

#JMX剖析
metric信息用于对软件系统的运行状态进行监控,在JAVA中,JVM提供了一套功能强大的框架来完成这个工作:JMX.它可以实现对运行时的软件各种metric信息收集,传输,甚至
可以通过远程修改当前正在运行的软件系统的内置参数.  
在JMX有两个核心概念MBeanServer和MBean. (这里没有考虑远程JMX,如果考虑远程,还有JMX ServerAgent,Protocol,Connectors,它们实现远程来管理一组MBean对象)
MBean与其他Bean一样,它是一个蕴含状态的实体对象,每个MBean对外提供了一组可以访问的Attribute,Operation,Constructor,和Notification
(通过Notification来实现Bean之间通信).与metric接头,那么每个bean中就相当与一组我们可以远程监控的metric;  
JMX中对MBean划分为四种类型:

>+   standard MBean:最简单的MBean,它通过接口来定义需要管理的Attribute,Operation,Constructor,然后基于这个接口来实现MBean.
>+   dynamic MBean:动态MBean通过实现javax.management.DynamicMBean接口中的getAttribute()和invoke()方法来对外暴露Attribute,Operation
>+   model MBean:与标准和动态MBean相比，你可以不用写MBean类，只需使用javax.management.modelmbean.RequiredModelMBean即可。
RequiredModelMBean实现了ModelMBean接口，而ModelMBean扩展了DynamicMBean接口，因此与DynamicMBean相似，
Model MBean的管理资源也是在运行时定义的。与DynamicMBean不同的是，
DynamicMBean管理的资源一般定义在DynamicMBean中（运行时才决定管理那些资源），
而model MBean管理的资源并不在MBean中，而是在外部（通常是一个类），只有在运行时，才通过set方法将其加入到model MBean中。
>+   open MBean:没有详细去了解

MBean类型不同,只是她们实现的方法不同而已,核心本质都是对外提供一组可以访问的Attribute,Operation,Constructor,和Notification.

下面来讨论MBeanServer,一个MBean对象就是一个普通的对象,在应用程序内部,它可以被其他类调用(比如按照MBean规范定义一个应用程序配置信息的class,
维护应用程序运行时所有配置信息),要让一个按照MBean规范定义的MBean对象被JMX应用程序框架所管理,那么它们就需要将其"注册到MBeanServer"中.  

MBeanServer它是JMX中所有MBean对象的容器和代理.  
首先它是一个容器,通过registerMBean和unregisterMBean接口可以将一个MBean对象注册到MBeanServer中.
MBean对象这里就表示为Object,一个注册到MBeanServer的Object必须指定一个ObjectName,从而通过Object+ObjectName作为容器中的KV进行一一关联.  
ObjectName也是JMX里面一个核心类,它规范化"MBean对象名称"的定义,每个ObjectName由"domain:key1 = value1,key2 = value2"格式进行描述,比如Hadoop
中所有对象的MBean对象的ObjectName为"Hadoop:service="+ serviceName +",name="+ nameName;"    
由Object+ObjectName组合的MBean对象就组成一个ObjectInstance对象.

其次MBeanServer也是一个代理,它充当所有注册注册到MBeanServer中的MBean对象的Proxy,通过该Proxy实现对MBean的操作.我们可以通过createMBean,instantiate操作
新创建和获取已有的MBean的ObjectInstance对象.也可以通过getAttributes来获取指定ObjectName所对应MBean对象的属性,通过invoke来执行MBean对象中的方法.

另外一个更加重要的是,我们通过Object+ObjectName将一个MBean对象注册到MBeanServer中,该MBean对象将会被解析,通过MBeanInfo来描述这个MBean对象的包含的所有
Attribute,Operation,Constructor,和Notification.  
从一定程度上来说,MBeanInfo也是通过MBeanServer的代理将一个MBean对外进行开放;由于MBeanInfo对象是根据MBean来生成的,所有APi建议不要对MBeanInfo对象进行修改.
保持它的不变性.

对于一个Metric系统,JMX中的MBean和MBeanServer充当着metric表示和存储容器,进而可以通过JMX中定义的ServerAgent等组件将容器中的MBean对象的状态暴露出去,
并接受对MBean对象的操作.目前支持的ServerAgent很多,比如HTTP,SNMP,RMI,JINI等.不管采用哪一种Agent,它们做的工作都是将MBeanServer中所有MBeanInfo进行可视化展示而已.  
所以这里就不详细去描述每种ServerAgent的实现.

上述的内容都在javax.management包中,另外在java.lang.management包中定义了一组用于获取JVM运行时的内存信息,GC信息,ClassLoad信息等MXBean对象的接口. 
对的,是MXBean而不是MBean,在上面描述的4中MBean类型中,MXBean是属于standard MBean的一种变种.

>而MXBean与MBean的区别主要是在于在接口中会引用到一些其他类型的类时，其表现方式的不一样。
>在MXBean中，如果一个MXBean的接口定义了一个属性是一个自定义类型，如MemoryMXBean中定义了heapMemoryUsage属性，
>这个属性是MemoryUsage类型的，当JMX使用这个MXBean时，这个MemoryUsage就会被转换成一种标准的类型，这些类型被称为开放类型，
>是定义在javax.management.openmbean包中的。而这个转换的规则是，如果是原生类型，如int或者是String，则不会有变化，
>但如果是其他自定义类型，则被转换成CompositeDataSupport类。
>详细描述参考:[http://clarenceau.iteye.com/blog/1827026](http://clarenceau.iteye.com/blog/1827026)

JMX基本就这样,框架很简单,但是功能很强大,特别在分布式系统中,通过JMX可以实现远程来对应用程序进行监控.

#Hadoop Metric
Hadoop metric在代码实现上有两个版本,分别位于org.apache.hadoop.metrics/metrics2两个包中,下面我们会分别进行分析

##  Metric v1
从功能和设计上来看,Metric v1的实现很简单,核心类也只有ContextFactory,MetricsContext,Updater,MetricsRecord四个.

在开始讨论Metric v1每个类的具体实现前,我们先剖析一下什么是metric.  
在上一节我们讨论的MBean,抛开Operation不说(metric系统仅仅围绕信息的收集,不关心操作和交互),那么MBean的逻辑结构可以表示为:

>ObjectName.domain+一组KeyValue组成的ObjectName为索引,值为一组Attribute(每个attribute由变量名加上变量值组成).
  
抽象到metric系统,一条metric记录的逻辑结构可以表示为:

>+  recordName表示这条metric记录的名称
>+  一组KV组合起来的tagMap,每个KV描述该记录的一个Tag信息,比如key=host,value=bj-01就表示该metric记录归属的host为bj-01
>+  一组KV组合起来的metricTable,每个KV描述该记录的一个统计项(metric项),比如key=memory-used,value=10g表示memory-used这个metric项的统计值.
>+  每个metric统计项的值类型有两种ABSOLUTE和INCREMENT,分别表示绝对值和增量值,比如当前空闲内存就是一个ABSOLUTE,而软件启动到现在处理的请求数就是一个增量值.

由recordName+tagMap+metricTable就组成了一条metric记录,它反应一个recordName统计对象在特定的tag环境下面,每个统计项的统计值.   
上面我们对metric的逻辑结构进行分析,其实它就是MetricsRecord类的实现.

        public class MetricsRecordImpl implements MetricsRecord {
            private TagMap tagTable = new TagMap();
            private Map<String,MetricValue> metricTable = new LinkedHashMap<String,MetricValue>();
            private String recordName;
            private AbstractMetricsContext context;        
        ....

每个recordName都需要一个实体对象去和业务代码交互,读取业务代码运行过程中各种运行时信息,并把这些运行时的信息转化metric记录.  
同时该实体对象需要对外提供接口,定时的从中获取一条当前的metric记录.  
在metric v1中,该实体对象就是一个实现Updater接口的对象,该接口只有一个函数doUpdates,每次实体对象的该函数被调用,就需要向对方返回一条metric记录.

        public interface Updater {
          public abstract void doUpdates(MetricsContext context);        
        }
        
参考ShuffleClientMetrics实体类的实现,它继承Updater接口,每次doUpdates函数被调用,就返回一条当前运行shuffle运行的统计数据.

Updater实体类是一个被动类,它不会主动的去采集metric信息,而是将自己注册到一个中控.中控类会定时的去请求所有已经注册的Updater的doUpdates的函数,
获取一条记录,并把记录保存在内部容器中,或写到File/Ganglia中,这里谈到的中控本质就是MetricsContext的实现.  

MetricsContext提供registerUpdater/unregisterUpdater接口,从而接受Updator的注册,并在内部维护一个定时器,定时遍历所有注册的Updater获取一条Record,
并对record进行相应的持久化处理.

MetricContext的创建,依赖ContextFactory,该工厂类在初始化时候,从hadoop配置文件目录中读取hadoop-metrics.properties,比如针对dfs这个MetricContext配置:  

        dfs.class=org.apache.hadoop.metrics.file.FileContext  

那么我们就可以通过ContextFactory.getContext("dfs");获取一个FileContext对象,从而可以把从每个Updater中收集到metric信息写到文件中.

总结:Hadoop metric V1版本是一个过时的metric系统,系统整体设计很简单,对于一般应用是够用.但是它有很大的缺点,它目前只提供了File/Ganglia两种context的实现,  
而且它没有对JVM内部强大的JMX进行支持.  这点对于metric信息的收集和展现带来很大的局限性. 下面我们讨论Metric2就考虑到与JMX的继承.

## Metric v2
Metric2在设计上比Metric1要复杂多了,下面我们一点点的剖析.
#### record表示
和metric1基本一致,一条metric record由recordName+tagMap+metricTable,但是metric做了两处简单的变动.

>+  变动一,recordName,tagName,metricName都从现在的string抽象为MetricsInfo,由原先单单的name抽象为name+description,
这点更加符合日常使用,因为name不够描述的时候,可以使用description进行详细描述该"name"的作用.
>+  变动二,从tagMap中抽离出一个特有TAG,即context,该tag的description字段为"Metrics context",context可以翻译为metric所处于的上下文,
比如QueueMetrics用于yarn中队列的metric信息,那么该metric的context值就为"yarn";

这两点变动都比较小,都容易理解.另外针对metric值做了一个很大的改变,在metric v1很简单,直接表示为name+value,而在metric v2中引入了MutableMetric类
以及一组针对特定类型的类,如MutableGaugeInt.来对metric的值进行表示.

MutableMetric对外提供一个metric"到目前为止是否改变"的语义和改变这个语义的接口,另外针对一个metric值提供一个返回当前快照的snapshot接口

        public void snapshot(MetricsRecordBuilder builder, boolean all) {
            if (all || changed()) {
              builder.addGauge(info(), value);
              clearChanged();
            }
        }

当一个MutableMetric的snapshot方法被调用,内部会判断从上一次快照到现在,metric的值是否被改变,如果没有改变,那么就不会返回任何信息,否则会将当前改变的值
写到参数MetricsRecordBuilder中,并清除当前的改变.

另外针对record的容器,metric引入collector和recordBuilder的两个概念概念

>+  MetricsCollector类可以理解为metric record容器的表示,通过addRecord向该容器添加一条记录:MetricsRecordBuilder addRecord(MetricsInfo info);
>+  上面的addRecord并不是把一个metric-record作为参数直接添加到collector中,而是针对当前的record返回一个builder,客户端根据该builder进行
设置tag,context,metric的值.

#### MetricsSource
在metric v1中,一个Updater表示一个数据源,对外提供doUpdates接口向外部反馈一条metric record记录.而在metric v2中,updater抽象为MetricsSource,和updater一样,
对外提供getMetrics接口.

        public interface MetricsSource {
          void getMetrics(MetricsCollector collector, boolean all);
        }
        
任何想被监控的metric信息都需要继承MetricSource接口,并实现getMetrics方法.在metric2中,MetricSource的实现是一大亮点,
在详细讲解这个实现之前我们想看一个类:MetricsRegistry

MetricsRegistry类很重要,它会强制成为每个MetricSource类字段,如果用户类没有包含这个字段,该source会被系统进行封装.因此一个好建议每个自定义的source都包含该字段.

MetricsRegistry在source中充当record的生成器,它提供了一条record的tag和metric的"对象真正的分配",这句话的意思很重要,打个比如:  
一个source里面定义一个MutableGaugeInt变量来表示我们对外反馈一个Int的metric信息,注意这里说的是定义,没有对这个变量指向对象的分配(new),
对这个变量的分配,我们需要调用source内部的MetricsRegistry的newGauge(MetricsInfo info, int iVal) 函数进入分配,看一下这个函数的源码:

        public synchronized MutableGaugeInt newGauge(MetricsInfo info, int iVal) {
            checkMetricName(info.name());
            MutableGaugeInt ret = new MutableGaugeInt(info, iVal);
            metricsMap.put(info.name(), ret);
            return ret;
        }
看这个函数的源码我们知道,它在内部做了对象的初始化,返回一个对象引用,并把该对象添加到MetricsRegistry内部的一个metricsMap中,对于tag也一样,MetricsRegistry
内部有一个tagsMap.这里MetricsRegistry就相当于维护一个source内部metric和tag对象的注册表,通过MetricsRegistry内部的snapshot,我们就可以返回一条当前record的镜像.

        public synchronized void snapshot(MetricsRecordBuilder builder, boolean all) {
            for (MetricsTag tag : tags()) {
              builder.add(tag);
            }
            for (MutableMetric metric : metrics()) {
              metric.snapshot(builder, all);
            }
        }
到目前为止,我们还没有看到MetricsRegistry在Source中的作用,只是把一个简单new过程复杂为一个函数调用.病提供一个snapshot函数简化record的构建.  

但是Source的设计亮点在处于可以直接省略掉newGauge之类的方法的调用,通过属性Field的标注来设置metric的属性以及自动进行初始化.
参考一个例子:

        @Metrics(context="yarn")
        public class QueueMetrics implements MetricsSource {
          @Metric("# of apps submitted") MutableCounterInt appsSubmitted;
          @Metric({"Snapshot", "Snapshot stats"}) MutableStat snapshotStat;
        }
对于QueueMetrics,通过对appsSubmitted和snapshotStat两个MutableMetric进行@Metric标注,系统会自动对两个metric进行初始化,并设置metric的name和description.
在QueueMetrics中不需要对appsSubmitted进行初始化,就可以直接进行赋值. 

通过这种类型的标注,可以最大化的节省source的开发. 

另外除了对field进行标注以外,针对source本身,也可以进行@Metrics(context="yarn")来设置source的context属性,从而从该source产生的所有record的context属性都该值.

话说这一系列是怎么实现的?它有一个前提就是该source必须被注册到到MetricsSystem中,MetricsSystem和metric v1的context概念一致,通过调用register函数来进行注册.

        @Override public synchronized <T>
        T register(String name, String desc, T source) {
            MetricsSourceBuilder sb = MetricsAnnotations.newSourceBuilder(source);
            final MetricsSource s = sb.build();
            MetricsInfo si = sb.info();
            ...
        }
在register函数内部调用MetricsSourceBuilder sb = MetricsAnnotations.newSourceBuilder(source)对原始的source进行封装,并build出一个初始化以后的MetricsSource.
具体MetricsSourceBuilder我就不写出来,逻辑还是比较简单,从事的工作就是对所有@Metric标注的metric变量在source的MetricsRegistry进行初始化,并设置引用到变量上.

另外因为MetricsRegistry的存在,任何在source中对metric变量的修改都会反应到MetricsRegistry内部变量的改变,通过基于MetricsRegistry内部的snapshot接口,
实现MetricsSource的getMetrics接口会显得十分简单,比如:

        public synchronized void getMetrics(MetricsCollector collector, boolean all) {
            registry.snapshot(collector.addRecord(registry.info()), all);
        }
一切是不是如此简单!