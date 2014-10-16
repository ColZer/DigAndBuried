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
Hadoop metric在代码实现上有两个版本,分别位于org.apache.hadoop.metrics/metrics2两个包中,本节我们核心来讨论一下Metric v1的实现.  

##  Metric v1
从功能和设计上来看,Metric v1的实现很简单,核心类也只有ContextFactory,MetricsContext,Updater,MetricsRecord四个.

在开始讨论Metric v1每个类的具体实现前,我们先剖析一下什么是metric.  
在上一节我们讨论的MBean,抛开Operation不说(metric系统仅仅围绕信息的收集,不关心操作和交互),那么MBean的逻辑结构可以表示为:

>ObjectName.domain+一组KeyValue组成的ObjectName为索引,值为一组Attribute(每个attribute由变量名加上变量值组成).
  
抽象到metric系统,一条metric记录的逻辑结构可以表示为:

>recordName表示这条metric记录的名称
>一组KV组合起来的tagMap,每个KV描述该记录的一个Tag信息,比如key=host,value=bj-01就表示该metric记录归属的host为bj-01
>一组KV组合起来的metricTable,每个KV描述该记录的一个统计项(metric项),比如key=memory-used,value=10g表示memory-used这个metric项的统计值.
>每个metric统计项的值类型有两种ABSOLUTE和INCREMENT,分别表示绝对值和增量值,比如当前空闲内存就是一个ABSOLUTE,而软件启动到现在处理的请求数就是一个增量值.

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

