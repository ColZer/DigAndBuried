NodeManager Container Localization的研究
==============
在Hadoop v1版本中，通过Distributed Cache可以实现将MapReduce运行过程中需要的jar，数据文件等cache到jobTrack本地。在Hadoop v2中，废弃了Distributed Cache
接口，将Localization模块放到NodeManager中，由yarn来统一实现Localization。

##  Localization的使用
分析Localization实现之前，我们先站在用户的角度来看怎么使用Localization。

在传统的MapReduce中，我们是使用Distributed Cache提供的接口，参考实例如下：
    
    1.Copy the requisite files to the FileSystem:
     $ bin/hadoop fs -copyFromLocal lookup.dat /myapp/lookup.dat 
    2.Setup the application's JobConf:  
     JobConf job = new JobConf();
     DistributedCache.addCacheFile(new URI("/myapp/lookup.dat#lookup.dat"),  job);
    3.Use the cached files in the Mapper or Reducer:
     File f = new File("./lookup.dat");
     
从上面的case我们可以看到，DistributedCache将文件cache到mapper/reducer的本地目录中。

但是在新一代的MapReduce中，我们已经不是使用DistributedCache，而是直接使用org.apache.hadoop.mapreduce.Job类的相关函数，如下：

    addArchiveToClassPath(Path archive) 
    addCacheArchive(URI uri) 
    addCacheFile(URI uri) 
    addFileToClassPath(Path file) 
    
MapReduce v2中实现Localization是基于NodeManager来实现。下面我们来一步一步的分析怎么将cache的需求传递给NodeManager。

我们知道NodeManager的交互主要是请求NodeManager请求启动一个Container，而Container运行过程中所需要的资源都是包含在请求中，NodeManager所做的工作
就是对请求的资源进行Localizer化。  

Container请求接口是向NodeManager发送一个startContainers操作，请求参数为StartContainersRequest容器，容器的每个元素都为一个StartContainerRequest，
该request对应的proto描述如下。

    message StartContainerRequestProto {
      optional ContainerLaunchContextProto container_launch_context = 1;
      optional hadoop.common.TokenProto container_token = 2;
    }
    message ContainerLaunchContextProto {
      repeated StringLocalResourceMapProto localResources = 1;
      optional bytes tokens = 2;
      repeated StringBytesMapProto service_data = 3;
      repeated StringStringMapProto environment = 4;
      repeated string command = 5;
      repeated ApplicationACLMapProto application_ACLs = 6;
    }
    message StringLocalResourceMapProto {
      optional string key = 1;
      optional LocalResourceProto value = 2;
    }
    message LocalResourceProto {
      optional URLProto resource = 1;
      optional int64 size = 2;
      optional int64 timestamp = 3;
      optional LocalResourceTypeProto type = 4;
      optional LocalResourceVisibilityProto visibility = 5;
      optional string pattern = 6;
    }
    
StartContainer请求中包含了多个 LocalResource。每个LocalResource包含了url地址，大小，文件时间戳，资源类型和资源可见性，以及pattern。

+   资源类型比较简单，包括ARCHIVE，FILE和PATTERN三种。如果是ARCHIVE比如a.zip，在container的当前目录中，a.zip中的所有文件都会解压到a.zip目录中。
+   资源可见性分为PUBLIC，PRIVATE，APPLICATION三种。在“NodeManager Container的启动”中介绍目录结构时，三种类型资源会被放到不同的目录中，并且因为APPLICATION
是有生命周期的，在APPLICATION运行结束以后会被清理掉，而PUBLIC，PRIVATE则由Localization内部的cache清理模块进行清理。

AM在与NodeManager进行通信会将需要的所有的Resource封装到StartContainerRequest，NodeManager完成这批Resource的本地化操作，从而保证container运行过程中，
所有的资源都在container的pwd目录中。

下面我们就来一步一步来解析NodeManager是怎么完成Localizer的过程。

##StartContainerRequest中LocalResources如何开始被Localization
LocalResources是一个名词，它由AM打包在一个请求中发送到NodeManager中，站在AM的角度，每个LocalResourceRequest和LocalResource之间一一关联。
但是站在NodeManager角度，由两个对象来维护这个关联。

首先每个LocalResource都使用“LocalizedResource”对象来维护，该对象是维护一个状态机来存储每个需要被Localizer的状态:包括初始化，下载中，已经下载，以及出错四个状态

    enum ResourceState {
      INIT,
      DOWNLOADING,
      LOCALIZED,
      FAILED
    }   

其次使用一个Track组件来维护当前所有需要被Localizer或者已经Localizer的Resource，这个Track组件就是LocalResourcesTracker，它维护一个Map容器来存储
每个LocalResourceRequest和LocalizedResource之间的关联，并且对外提供了接口可以创建一个新的关联，也可以将一个资源从Track中删除。

总而言之，LocalResourcesTracker负责将AM发送出来的LocalResourceRequest转化为NodeManager中可以维护的LocalizedResource对象。

下面我们来核心讨论一下LocalResourcesTracker这个东西是怎么存在的，整个NodeManager仅仅有一个全局的Tracker还是怎么样，已经它是什么时候创建的。

首先LocalResourcesTracker不是全局唯一的；在NodeManager的ResourceLocalizationService（Localization的中控模块，归属ContainerManager中一个子服务）中
维护了多个LocalResourcesTracker，如下所示：

    private LocalResourcesTracker publicRsrc;
    private final ConcurrentMap<String,LocalResourcesTracker> privateRsrc =
        new ConcurrentHashMap<String,LocalResourcesTracker>();
    private final ConcurrentMap<String,LocalResourcesTracker> appRsrc =
        new ConcurrentHashMap<String,LocalResourcesTracker>();
    
针对public类型的资源维护一个Tracker，针对每个User维护一个User类型的Tracker，同时针对每个Application维护一个App类型的Tracker。

其中publicRsrc在NodeManager启动时就完成了初始化工作，而privateRsrc和appRsrc的创建在每个Application的第一个Container的请求时进行初始化并添加到容器中。

>这里简单描述一下Container在NodeManager中初始化过程，即startContainers过程中：  
> 在NodeManager中，针对每个Application维护了Application对象，针对特定的Application在当前NodeManager中启动的每个Container维护一个Container对象
>Application对象的创建是发生在该Application的第一个Container创建的时候，如果NodeManager发现该Container的Application没有创建，在完成该Container的创建以及初始化之前
>首先创建该Container的Application，并进行初始化，比如初始化Application对应的目录，日志Handle等

而privateRsrc和appRsrc针对特定user和application的Tracker创建与初始化工作就发生在Application的初始化工作中。过程如下：

+    Application在ApplicationState.NEW状态时候，接受由ContainerManager发送的ApplicationInitEvent事件，Application完成该事件的处理，
+   处理结束后，发送LogHandlerAppStartedEvent请求LogHandler的初始化，LocaHandler成功初始化后会向Application发送完成事件
+   Application处理AppLogInitDoneEvent，并对外发送一个ApplicationLocalizationEvent事件

ApplicationLocalizationEvent事件就是请求ResourceLocalizationService对该Application需要进行Localization进行准备过程中，参见ResourceLocalizationService中
handleInitApplicationResources的实现。

    private void handleInitApplicationResources(Application app) {
        String userName = app.getUser();
        privateRsrc.putIfAbsent(userName, new LocalResourcesTrackerImpl(userName,
            null, dispatcher, true, super.getConfig(), stateStore));
        String appIdStr = ConverterUtils.toString(app.getAppId());
        appRsrc.putIfAbsent(appIdStr, new LocalResourcesTrackerImpl(app.getUser(),
            app.getAppId(), dispatcher, false, super.getConfig(), stateStore));
        dispatcher.getEventHandler().handle(new ApplicationInitedEvent(
              app.getAppId()));
      }

该过程逻辑很简单，就是在ResourceLocalizationService中针对该Application完成User类型和Application类型的LocalResourcesTracker的创建与初始化工作。

+   完成LocalResourcesTracker的初始化以后，ResourceLocalizationService会发送一个ApplicationInitedEvent被Application处理
+   Application在处理ApplicationInitedEvent完以后就完成Application所有的初始化过程，此时就会向该Application所有container发送一个ContainerInitEvent事件，请求Container的初始化。
+   如果Container不是第一次初始化，此时Application已经完成初始化工作，处于RUNNING状态，此时每个Container的初始化过程中，ContainerManager都会想Application发送一个ApplicationContainerInitEvent事件
+   Application在处理ApplicationContainerInitEvent过程中，如果当前Application已经初始化，那么会向container发送一个ContainerInitEvent事件，否则什么事情不做，等待Application初始化以后由Application向所有的Container发送ContainerInitEvent事件

到目前为止，Application完成了初始化，每个Application对应的User和App的Track也完成初始化。但是我们知道真正需要Localization的Resource是由Container发送过来的，
即在Container的初始化过程中会请求ResourceLocalizationService完成LocalResources的初始化。

具体过程中怎么样，下面我来分析Container的初始化过程中。

+   上面我们谈到Application会向container发送一个INIT_CONTAINER事件，Container接受INIT_CONTAINER事件后，由RequestResourcesTransition进行处理，
从名称我们也可以看出来，主要工作是Resource的Localization操作（主要但是不是全部）

        //RequestResourcesTransition
        // Send requests for public, private resources
        final ContainerLaunchContext ctxt = container.launchContext;
        Map<String,LocalResource> cntrRsrc = ctxt.getLocalResources();
        if (!cntrRsrc.isEmpty()) {
            try {
              for (Map.Entry<String,LocalResource> rsrc : cntrRsrc.entrySet()) {
                try {
                  LocalResourceRequest req = new LocalResourceRequest(rsrc.getValue());
                  List<String> links = container.pendingResources.get(req);
                  if (links == null) {
                    links = new ArrayList<String>();
                    container.pendingResources.put(req, links);
                  }
                  links.add(rsrc.getKey());
                  switch (rsrc.getValue().getVisibility()) {
                  case PUBLIC:
                    container.publicRsrcs.add(req);
                    break;
                  case PRIVATE:
                    container.privateRsrcs.add(req);
                    break;
                  case APPLICATION:
                    container.appRsrcs.add(req);
                    break;
                  }
                } catch (URISyntaxException e) {
                  throw e;
                }
              }
            } catch (URISyntaxException e) {
              return ContainerState.LOCALIZATION_FAILED;
            }
            Map<LocalResourceVisibility, Collection<LocalResourceRequest>> req =
                new HashMap<LocalResourceVisibility, Collection<LocalResourceRequest>>();
            if (!container.publicRsrcs.isEmpty()) {
              req.put(LocalResourceVisibility.PUBLIC, container.publicRsrcs);
            }
            if (!container.privateRsrcs.isEmpty()) {
              req.put(LocalResourceVisibility.PRIVATE, container.privateRsrcs);
            }
            if (!container.appRsrcs.isEmpty()) {
              req.put(LocalResourceVisibility.APPLICATION, container.appRsrcs);
            }
            container.dispatcher.getEventHandler().handle( new ContainerLocalizationRequestEvent(container, req));
            return ContainerState.LOCALIZING;
        } else {
            container.sendLaunchEvent();
            container.metrics.endInitingContainer();
            return ContainerState.LOCALIZED;
        }
    
+   从上面我们看到，RequestResourcesTransition从Container的launchContext中解析出PUBLIC/PRIVATE/APPLICATION三种类型的资源，并封装为类型为
LocalizationEventType.INIT_CONTAINER_RESOURCES的ContainerLocalizationRequestEvent事件发送出去；自身进入LOCALIZING状态；当然如果没有资源需要Localization，那么会直接进入LOCALIZED状态。
+   与ResourceLocalizationService在处理Application的初始化一直，ResourceLocalizationService处理INIT_CONTAINER_RESOURCES相当于处理Container的初始化，
事件中包含所有需要进行Localization的LocalResource，整体过程由ResourceLocalizationService中handleInitContainerResources进行处理。
        
        private void handleInitContainerResources(ContainerLocalizationRequestEvent rsrcReqs) {
            Container c = rsrcReqs.getContainer();
            Map<LocalResourceVisibility, Collection<LocalResourceRequest>> rsrcs =
              rsrcReqs.getRequestedResources();
            for (Map.Entry<LocalResourceVisibility, Collection<LocalResourceRequest>> e :
                 rsrcs.entrySet()) {
              LocalResourcesTracker tracker =
                  getLocalResourcesTracker(e.getKey(), c.getUser(),
                      c.getContainerId().getApplicationAttemptId()
                          .getApplicationId());
              for (LocalResourceRequest req : e.getValue()) {
                tracker.handle(new ResourceRequestEvent(req, e.getKey(), ctxt));
              }
            }
         }
从上面的代码我们可以看到，ResourceLocalizationService根据资源的类型不同，选用不同LocalResourcesTracker，并将资源封装为一个ResourceRequestEvent
发送给每个Track进行处理。

总结：我们针对Application的初始化和Container的初始化来解析LocalResourcesTracker初始化工作，到目前为止，我们已经将App需要进行Localization的Resource
从Container层面传递到LocalizationService，下面我们需要解析的就是LocalizationService如果利用LocalResourcesTracker来完成每个资源的Localization工作。





