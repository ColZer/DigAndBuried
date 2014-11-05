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

## LocalResourcesTracker是如何对其中每个LocalResource进行Localization
在上面部分，我们将一个需要被Localizer的资源从Container的StartContainerRequest走到LocalResourcesTracker，下面我们就来分析LocalResourcesTracker怎么进行
Resource的Localizer的。

如上所言，在LocalResourcesTracker中，将每个资源表示为一个LocalizedResource，并与LocalResourceRequest一一关联，ResourceLocalizationService通过
LocalResourcesTracker的Handle接口，将资源封装为一个ResourceRequestEvent发送到LocalResourcesTracker，下面我们来看handle接口的逻辑：

    public synchronized void handle(ResourceEvent event) {
        LocalResourceRequest req = event.getLocalResourceRequest();
        LocalizedResource rsrc = localrsrc.get(req);
        switch (event.getType()) {
        case REQUEST:
          if (rsrc != null && (!isResourcePresent(rsrc))) {
            removeResource(req);
            rsrc = null;
          }
          if (null == rsrc) {
            rsrc = new LocalizedResource(req, dispatcher);
            localrsrc.put(req, rsrc);
          }
          break;
        }    
        rsrc.handle(event);
      }

删除Handle函数中其他event.type的操作，只目前只对REQUEST进行分析。我们看到LocalResourcesTracker首先通过isResourcePresent判读指定Resource
是否已经被localizer到本地，如果没有，那么就成为该资源创建一个LocalizedResource对象，并将事件转交给Resource相对应的LocalizedResource对象处理。

从上面我们看到LocalResourcesTracker在上层为所有需要被Localizer的Resource维护一个LocalResourceRequest和LocalizedResource之间的索引而已。具体的Resource
的Localizer过程由LocalizedResource自己进行驱动。

下面我们的流程就走到分析LocalizedResource。  
LocalizedResource是一个状态机，维护了资源了INIT/DOWNLOADING/LOCALIZED/FAILED等状态。

+   当LocalResourcesTracker在处理REQUEST事件时候，会创建一个 LocalizedResource，处于INIT状态。创建后LocalResourcesTracker会将REQUEST事件转交给LocalizedResource
+   LocalizedResource处理REQUEST是由FetchResourceTransition来完成。
        
        private static class FetchResourceTransition extends ResourceTransition {
            @Override
            public void transition(LocalizedResource rsrc, ResourceEvent event) {
              ResourceRequestEvent req = (ResourceRequestEvent) event;
              LocalizerContext ctxt = req.getContext();
              ContainerId container = ctxt.getContainerId();
              rsrc.ref.add(container);
              rsrc.dispatcher.getEventHandler().handle(
                  new LocalizerResourceRequestEvent(rsrc, req.getVisibility(), ctxt, 
                      req.getLocalResourceRequest().getPattern()));
            }
         }

+   LocalizedResource在内部维护了每个Resource与container之间的关联关系，同时在处理REQUEST会将Localizer操作封装为LocalizerResourceRequestEvent发送到LocalizationService中
+   LocalizerResourceRequestEvent的事件被“特定组件”所接受，完成文件Localizer以后，会向LocalizedResource发送一个ResourceLocalizedEvent，告知Resource被Localizer。
并由FetchSuccessTransition进行处理：

          private static class FetchSuccessTransition extends ResourceTransition {
            @Override
            public void transition(LocalizedResource rsrc, ResourceEvent event) {
              ResourceLocalizedEvent locEvent = (ResourceLocalizedEvent) event;
              rsrc.localPath =
                  Path.getPathWithoutSchemeAndAuthority(locEvent.getLocation());
              rsrc.size = locEvent.getSize();
              for (ContainerId container : rsrc.ref) {
                rsrc.dispatcher.getEventHandler().handle(
                    new ContainerResourceLocalizedEvent(
                      container, rsrc.rsrc, rsrc.localPath));
              }
            }
          }
      
+   LocalizedResource会在资源已经Localizer以后，会以ContainerResourceLocalizedEvent事件的方式通知所有等待该资源的Container。
+   Container会对ContainerResourceLocalizedEvent进行处理，通过检查所有的Resource是否都已经Localizer，如果是就进行LOCALIZED并启动Container，否则继续等待。
逻辑如下：
        
        static class LocalizedTransition implements
              MultipleArcTransition<ContainerImpl,ContainerEvent,ContainerState> {
            @Override
            public ContainerState transition(ContainerImpl container,
                ContainerEvent event) {
              ContainerResourceLocalizedEvent rsrcEvent = (ContainerResourceLocalizedEvent) event;
              List<String> syms =
                  container.pendingResources.remove(rsrcEvent.getResource());
              if (null == syms) {
                LOG.warn("Localized unknown resource " + rsrcEvent.getResource() +
                         " for container " + container.containerId);
                assert false;
                // fail container?
                return ContainerState.LOCALIZING;
              }
              container.localizedResources.put(rsrcEvent.getLocation(), syms);
              if (!container.pendingResources.isEmpty()) {
                return ContainerState.LOCALIZING;
              }
        
              container.sendLaunchEvent();
              container.metrics.endInitingContainer();
              return ContainerState.LOCALIZED;
            }
          }

总结：到目前为止，我们已经了解了Localization请求进入LocalResourcesTracker，并被LocalizedResource并进行调度，请求“特定的组件”来完成Resource的Localizer，
完成以后通知Container，相应的资源已经Localized成功。

现在就遗留一个问题，所说的特定组件是什么东西？

##什么组件处理LocalizedResource的LocalizerResourceRequestEvent并完成Resource的Local操作
答案是LocalizerTracker，注意与LocalResourcesTracker的不同，该Tracker是Localizer操作的tracker。下面我们就来分析，LocalizerTracker是怎么来完成Localizer操作

LocalizerTracker和上面一组LocalResourcesTracker一样，都是属于ResourceLocalizationService内部的组件，而且LocalizerTracker只会处理一种类型的事件，即：  

    LocalizerEventType.REQUEST_RESOURCE_LOCALIZATION

而上面说到LocalizerResourceRequestEvent，就是封装了REQUEST_RESOURCE_LOCALIZATION的LocalizerEvent，另外每个LocalizerEvent有一个LocalizerID，一般情况下
该LocalizerID就是ContainerID。

现在问题来了，为什么需要LocalizerEvent需要将ContainerID封装为一个LocalizerID？难道LocalizerTracker会针对每个ContainerID进行不同的处理？  

答案是对的。LocalizerTracker是一个上层封装，在LocalizerTracker内部两个Localizer，如下所示：

    class LocalizerTracker extends AbstractService implements EventHandler<LocalizerEvent>  {
    
        private final PublicLocalizer publicLocalizer;
        private final Map<String,LocalizerRunner> privLocalizers;

它们分别是一个publicLocalizer和多个privLocalizers，其中privLocalizers会为每个LocalizerID创建一个LocalizerRunner，换句话说会为每个Container创建一个LocalizerRunner。

这点与上面谈到的LocalResourcesTracker很像，只是Localizer是Container为单位，而LocalResourcesTracker包含User粒度和Application粒度。

在LocalizerTracker的Handler接口会根据请求的LocalizerEvent的Resource资源类型将Localization操作转交给不同的Localizer进行处理，如下所示：

    public void handle(LocalizerEvent event) {
          String locId = event.getLocalizerId();
          switch (event.getType()) {
          case REQUEST_RESOURCE_LOCALIZATION:
            LocalizerResourceRequestEvent req = (LocalizerResourceRequestEvent)event;
            switch (req.getVisibility()) {
            case PUBLIC:
              publicLocalizer.addResource(req);
              break;
            case PRIVATE:
            case APPLICATION:
              synchronized (privLocalizers) {
                LocalizerRunner localizer = privLocalizers.get(locId);
                if (null == localizer) {
                  localizer = new LocalizerRunner(req.getContext(), locId);
                  privLocalizers.put(locId, localizer);
                  localizer.start();
                }
                // 1) propagate event
                localizer.addResource(req);
              }
              break;
            }
            break;
          }
        }

如果资源为APPLICATION和PRIVATE类型，而且在privLocalizers没有该ContainerID对应的LocalizerRunner，那么就会创建一个LocalizerRunner。LocalizerRunner和PublicLocalizer
都提供了addResource接口将需要Localization的资源传递给它进行处理。

问题来了？PublicLocalizer和LocalizerRunner有上面区别？还是启一小节来专门进行描述，太复杂了。

## PublicLocalizer和LocalizerRunner的实现

###PublicLocalizer的实现
每个LocalizerTrack有且仅有一个PublicLocalizer，在LocalizerTrack初始化时候就完成PublicLocalizer的创建。PublicLocalizer在实现上，是一个线程，并且在该线程内部维护
一个线程池。如下所示：

    class PublicLocalizer extends Thread {
    
        final FileContext lfs;
        final Configuration conf;
        final ExecutorService threadPool;
        final CompletionService<Path> queue;
        // Its shared between public localizer and dispatcher thread.
        final Map<Future<Path>,LocalizerResourceRequestEvent> pending;

PublicLocalizer会每个addResource的操作在线程池中创建一个类型为FSDownload的线程，该线程会真正完成文件的下载的操作。

主线程处于死循环中，从当前线程池中获取每个FSDownload线程的结束状态来判读Resource是否被正确下载，如下所示：
    
    public void run() {
          try {
            while (!Thread.currentThread().isInterrupted()) {
              try {
                Future<Path> completed = queue.take();
                LocalizerResourceRequestEvent assoc = pending.remove(completed);
                try {
                  Path local = completed.get();
                  LocalResourceRequest key = assoc.getResource().getRequest();
                  publicRsrc.handle(new ResourceLocalizedEvent(key, local, FileUtil
                    .getDU(new File(local.toUri()))));
                  assoc.getResource().unlock();
                } catch (ExecutionException e) {
                  LocalResourceRequest req = assoc.getResource().getRequest();
                  publicRsrc.handle(new ResourceFailedLocalizationEvent(req,
                      e.getMessage()));
                  assoc.getResource().unlock();
                } 
              } catch (InterruptedException e) {
                return;
              }
            }
          } catch(Throwable t) {
            LOG.fatal("Error: Shutting down", t);
          } finally {
            LOG.info("Public cache exiting");
            threadPool.shutdownNow();
          }
        }

如果资源被正确下载，那么就会向LocalResourcesTracker发送一个ResourceLocalizedEvent事件，否则会发送一个ResourceFailedLocalizationEvent事件，如上所述
这些事件会通过LocalResourcesTracker传递给LocalizedResource，最后通知相应的Container。

###LocalizerRunner的实现
从上面我们可以看到，对于PUBLIC类型的资源是采用线程来进行Localizer。那么对于PRIVATE和APPLICATION类型的资源的LocalizerRunner，是否是多线程呢？

答案是否定的。下面我们来具体的分析

LocalizerRunner也一个线程，其中维护一个待处理的Resource列表；和PublicLocalizer的主线程实现不同，LocalizerRunner是一个堵塞式调用ContainerExecutor的startLocalizer。
    
    public void run() {
          Path nmPrivateCTokensPath = null;
          try {
            nmPrivateCTokensPath =
              dirsHandler.getLocalPathForWrite(
                    NM_PRIVATE_DIR + Path.SEPARATOR
                        + String.format(ContainerLocalizer.TOKEN_FILE_NAME_FMT,
                            localizerId));
    
            writeCredentials(nmPrivateCTokensPath);
            List<String> localDirs = dirsHandler.getLocalDirs();
            List<String> logDirs = dirsHandler.getLogDirs();
            if (dirsHandler.areDisksHealthy()) {
              exec.startLocalizer(nmPrivateCTokensPath, localizationServerAddress,
                  context.getUser(),
                  ConverterUtils.toString(
                      context.getContainerId().
                      getApplicationAttemptId().getApplicationId()),
                  localizerId, localDirs, logDirs);
            } else {
              throw new IOException("All disks failed. "
                  + dirsHandler.getDisksHealthReport());
            }
          } catch (Exception e) {
            ContainerId cId = context.getContainerId();
            dispatcher.getEventHandler().handle(
                new ContainerResourceFailedEvent(cId, null, e.getMessage()));
          } finally {
            for (LocalizerResourceRequestEvent event : scheduled.values()) {
              event.getResource().unlock();
            }
            delService.delete(null, nmPrivateCTokensPath, new Path[] {});
          }
        }

和上面一章谈到的ContainerExecutor一样，不同的ContainerExecutor的实现可以提供不同的startLocalizer实现。就是说我们可以在startLocalizer起进程来进行文件的下载，
也可以在其中起线程来进行下载。为了保证实现的兼容性，LocalizerRunner与startLocalizer所起的下载服务（进程/线程）之间的通信是基于RPC通信的。

DefaultContainerExecutor就是在当前线程中起线程池来进行文件下载；LinuxContainerExecutor就是在当前线程中起一个外部进程来进行文件，当前线程挂起直到进程退出。
不过不管是在当前线程维持线程池还是起一个进程来维持线程池，她们都是由ContainerLocalizer模块来实现。

首先我们参考DefaultContainerExecutor当前线程的实现方案：
    
    public synchronized void startLocalizer(Path nmPrivateContainerTokensPath,
          InetSocketAddress nmAddr, String user, String appId, String locId,
          List<String> localDirs, List<String> logDirs)
          throws IOException, InterruptedException {
    
        ContainerLocalizer localizer =
            new ContainerLocalizer(lfs, user, appId, locId, getPaths(localDirs),
                RecordFactoryProvider.getRecordFactory(getConf()));
    
        createUserLocalDirs(localDirs, user);
        createUserCacheDirs(localDirs, user);
        createAppDirs(localDirs, user, appId);
        createAppLogDirs(appId, logDirs);
    
        Path appStorageDir = getFirstApplicationDir(localDirs, user, appId);
    
        String tokenFn = String.format(ContainerLocalizer.TOKEN_FILE_NAME_FMT, locId);
        Path tokenDst = new Path(appStorageDir, tokenFn);
        lfs.util().copy(nmPrivateContainerTokensPath, tokenDst);
        lfs.setWorkingDirectory(appStorageDir);
        
        localizer.runLocalization(nmAddr);
      }

从实现上来，很简单，在当前线程中创建一个ContainerLocalizer对象，设置一下环境变量，设置一些目录，然后堵塞调用runLocalization，指定结束。

而对于LinuxContainerExecutor，因为ContainerLocalizer类提供了main函数，可以直接以进程的方式起起来，参考ContainerLocalizer的实现。

    public static void main(String[] argv) throws Throwable {
        Thread.setDefaultUncaughtExceptionHandler(new YarnUncaughtExceptionHandler());
        try {
          String user = argv[0];
          String appId = argv[1];
          String locId = argv[2];
          InetSocketAddress nmAddr =
              new InetSocketAddress(argv[3], Integer.parseInt(argv[4]));
          String[] sLocaldirs = Arrays.copyOfRange(argv, 5, argv.length);
          ArrayList<Path> localDirs = new ArrayList<Path>(sLocaldirs.length);
          for (String sLocaldir : sLocaldirs) {
            localDirs.add(new Path(sLocaldir));
          }
    
          final String uid =UserGroupInformation.getCurrentUser().getShortUserName();
    
          ContainerLocalizer localizer =
              new ContainerLocalizer(FileContext.getLocalFSFileContext(), user,
                  appId, locId, localDirs,
                  RecordFactoryProvider.getRecordFactory(null));
          System.exit(localizer.runLocalization(nmAddr));////
        } catch (Throwable e) {
          throw e;
        }
      }
  
可以看出，不管是在当前线程还是新起一个进程，都是堵塞调用ContainerLocalizer.runLocalization来等待下载操作结束。

那现在问题来了，ContainerLocalizer.runLocalization到底是怎么进行文件下载的？它主要做了下面几个工作：

+   创建一个与ResourceLocalizationService之间的RPC通信，通信协议为LocalizationProtocol，该协议的实现很简单，仅仅提供一个heartbeat心跳接口。
ContainerLocalizer周期的通过该心跳协议与RLS进行通信，拉取新的下载请求，并汇报已下载的资源情况。  
heartbeat请求参数为LocalizerStatus。其中包括当前ContainerLocalizer归属哪个LocalizerRunner，即LocalizerId，以及当前处理所有资源信息和状态ContainerLocalizer。  
heartbeat请求的返回值为LocalizerHeartbeatResponse。其中包括Action和一组需要被下载的资源列表。Action有LIVE和DIE两种，ContainerLocalizer根据Action的返回值来确定
是否继续下载还是结束。
+   创建一个DownloadThreadPool线程池，当heartbeat返回值为LIVE时，将每个需要下载的资源创建一个FSDownload线程并添加到线程池中调度，这点和PublicLocalizer实现一直。

ContainerLocalizer的实现很简单，详细的代码我们就不抠出来进行解析。

现在还有一个遗留问题，ContainerLocalizer是RPC的client端，但是到目前为止，我们还咩有发现这个RPC的服务端是在哪里创建的？

答案是这个服务端即ResourceLocalizationService一部分。

    public class ResourceLocalizationService extends CompositeService
        implements EventHandler<LocalizationEvent>, LocalizationProtocol{
             public LocalizerHeartbeatResponse heartbeat(LocalizerStatus status) {
                return localizerTracker.processHeartbeat(status);
              }
    }
    
我们看到，ResourceLocalizationService创建了该RPC Server，并把每个心跳请求转发给我们上文提到的LocalizerTracker。
    
    public LocalizerHeartbeatResponse processHeartbeat(LocalizerStatus status) {
          String locId = status.getLocalizerId();
          synchronized (privLocalizers) {
            LocalizerRunner localizer = privLocalizers.get(locId);
            if (null == localizer) {
              // TODO process resources anyway
              LOG.info("Unknown localizer with localizerId " + locId
                  + " is sending heartbeat. Ordering it to DIE");
              LocalizerHeartbeatResponse response =
                recordFactory.newRecordInstance(LocalizerHeartbeatResponse.class);
              response.setLocalizerAction(LocalizerAction.DIE);
              return response;
            }
            return localizer.update(status.getResources());
          }
    }

在LocalizerTracker内部，根据心跳协议来确定当前心跳来自哪个privLocalizers，并将请求转发给privLocalizers的update接口。

总结：到目前为止，我们已经分析了PublicLocalizer和LocalizerRunner两种实现的不同。前者是直接线程间的通信来进行下载的调度。而后者是采用RPC的方式与
每个负责下载的Localizer模块进行通信。

具体为什么要这样的设计？我个人的认识还是配额的问题，用户私有的LocalizerRunner可以对起的下载进程进行配额，限制带宽等，
而public所有用户共享，无需配额管理。因此如果我们使用DefaultContainerExecutor，那么PublicLocalizer和LocalizerRunner就没有本质区别，仅仅一个是rpc，一个线程间通信。

===
end