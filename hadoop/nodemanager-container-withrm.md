NodeManager解析系列四：与ResourceManager的交互
====

在yarn模型中，NodeManager充当着slave的角色，与ResourceManager注册并提供了Containner服务。前面几部分核心分析NodeManager所提供的
Containner服务，本节我们就NodeManager与ResourceManager交互进行分析。从逻辑上说，这块比Containner服务要简单很多。

## Containner服务什么时候开始提供服务？
在NodeManager中，核心服务模块是ContainnerManager，它可以接受来自AM的请求，并启动Containner来提供服务。那么Containner服务什么时候才可以
提供呢？答案很简单：NodeManager与ResourceManager注册成功以后就可以提供服务。

在ContainerManager模块有一个blockNewContainerRequests变量来控制是否可以提供Container服务，如下：
    
    public class ContainerManagerImpl {
        private AtomicBoolean blockNewContainerRequests = new AtomicBoolean(false);
        //
        startContainers(StartContainersRequest requests)  {
            if (blockNewContainerRequests.get()) {
              throw new NMNotYetReadyException(
                "Rejecting new containers as NodeManager has not"
                    + " yet connected with ResourceManager");
            }
            ...

在ContainerManager启动时，blockNewContainerRequests变量会被设置true，所有的startContainers操作都会被ContainerManager所拒绝，
那么该变量什么时候被设置为true呢？回答之前先看NodeManager与ResourceManager之间的通信协议

##  NodeManager与ResourceManager之间的通信协议：ResourceTracker
NodeManager以Client的角色与ResourceManager之间进行RPC通信，通信协议如下：
    
    public interface ResourceTracker {
      public RegisterNodeManagerResponse registerNodeManager(
          RegisterNodeManagerRequest request) throws YarnException,
          IOException;
      public NodeHeartbeatResponse nodeHeartbeat(NodeHeartbeatRequest request)
          throws YarnException, IOException;
    }

NodeManager与ResourceManager之间操作由registerNodeManager和nodeHeartbeat组成，其中前者在NodeManager启动/同步时候进行注册，后者
在NodeManager运行过程中，周期性向ResourceManager进行汇报。

###先看registerNodeManager的参数和返回值：
    
    message RegisterNodeManagerRequestProto {
      optional NodeIdProto node_id = 1;
      optional int32 http_port = 3;
      optional ResourceProto resource = 4;
      optional string nm_version = 5;
      repeated NMContainerStatusProto container_statuses = 6;
      repeated ApplicationIdProto runningApplications = 7;
    }
    message RegisterNodeManagerResponseProto {
      optional MasterKeyProto container_token_master_key = 1;
      optional MasterKeyProto nm_token_master_key = 2;
      optional NodeActionProto nodeAction = 3;
      optional int64 rm_identifier = 4;
      optional string diagnostics_message = 5;
      optional string rm_version = 6;
    }
    message NodeIdProto {
      optional string host = 1;
      optional int32 port = 2;
    }
    message ResourceProto {
      optional int32 memory = 1;
      optional int32 virtual_cores = 2;
    }    

RegisterNodeManagerRequestProto为NodeManager注册提供的参数：

+   NodeIdProto表示当前的NodeManager的ID，它由当前NodeManager的RPC host和port组成。
+   ResourceProto为当前NodeManager总共可以用来分配的资源，分为内存资源和虚拟core个数：
    +   yarn.nodemanager.resource.memory-mb配置可以使用的物理内存大小。
    +   yarn.nodemanager.vmem-pmem-ratio可以使用的虚拟机内存比例，默认为2.1。
    +   yarn.nodemanager.resource.cpu-vcores当前使用的虚拟机core个数。
其中ResourceProto由resource.memory-mb和resource.cpu-vcores组成
+   http_port为当前NodeManager的web http页面端口
+   NMContainerStatusProto和ApplicationIdProto为当前NodeManager中运行的Container和App状态，
对于新启动的NodeManager都为空，但是NodeManager可以多次注册，即RESYNC过程。此时这两项都不为空

RegisterNodeManagerResponseProto为NodeManager注册返回信息：

+   MasterKeyProto/MasterKeyProto/都为相应的token信息
+   rm_identifier/rm_version为ResourceManager的标示和版本信息
+   NodeActionProto为ResourceManager通知NodeManager所进行的动作，包括RESYNC/SHUTDOWN和默认的NORMAL。
+   diagnostics_message为ResourceManager为该NodeManager提供的诊断信息

在NodeManager初始化过程中，即会发起注册的动作，如下：
    
    protected void registerWithRM(){
        List<NMContainerStatus> containerReports = getNMContainerStatuses();
        RegisterNodeManagerRequest request =
            RegisterNodeManagerRequest.newInstance(nodeId, httpPort, totalResource,
              nodeManagerVersionId, containerReports, getRunningApplications());
        if (containerReports != null) {
          LOG.info("Registering with RM using containers :" + containerReports);
        }
        RegisterNodeManagerResponse regNMResponse =
            resourceTracker.registerNodeManager(request);//发起注册
            
        this.rmIdentifier = regNMResponse.getRMIdentifier();
        // 被ResourceManager告知需要关闭当前NodeManager
        if (NodeAction.SHUTDOWN.equals(regNMResponse.getNodeAction())) {
          throw new YarnRuntimeException();
        }
    
        //NodeManager需要与ResourceManager之间必须版本兼容
        if (!minimumResourceManagerVersion.equals("NONE")){
          if (minimumResourceManagerVersion.equals("EqualToNM")){
            minimumResourceManagerVersion = nodeManagerVersionId;
          }
          String rmVersion = regNMResponse.getRMVersion();
          if (rmVersion == null) {
            throw new YarnRuntimeException("Shutting down the Node Manager. "
                + message);
          }
          if (VersionUtil.compareVersions(rmVersion,minimumResourceManagerVersion) < 0) {
            throw new YarnRuntimeException("Shutting down the Node Manager on RM "
                + "version error, " + message);
          }
        }
        MasterKey masterKey = regNMResponse.getContainerTokenMasterKey();
        if (masterKey != null) {
          this.context.getContainerTokenSecretManager().setMasterKey(masterKey);
        }        
        masterKey = regNMResponse.getNMTokenMasterKey();
        if (masterKey != null) {
          this.context.getNMTokenSecretManager().setMasterKey(masterKey);
        }
        //设置blockNewContainerRequests为false
        ((ContainerManagerImpl) this.context.getContainerManager())
          .setBlockNewContainerRequests(false);
      }
    
从上面注释我们可以看到registerWithRM主要进行的操作有：
+   向ResourceManager发送registerNodeManager RPC请求
+   如果RPC返回的动作为ShutDown，即立即关闭NodeManager
+   进行版本检查，保存ResourceManager返回的各种token。
+   最后一个动作很重要，设置ContainerManager的blockNewContainerRequests为false，此时ContainerManager可以接受AM的请求。

###NodeManager与ResourceManager之间的心跳协议：nodeHeartbeat
    
    message NodeHeartbeatRequestProto {
      optional NodeStatusProto node_status = 1;
      optional MasterKeyProto last_known_container_token_master_key = 2;
      optional MasterKeyProto last_known_nm_token_master_key = 3;
    }
    message NodeStatusProto {
      optional NodeIdProto node_id = 1;
      optional int32 response_id = 2;
      repeated ContainerStatusProto containersStatuses = 3;
      optional NodeHealthStatusProto nodeHealthStatus = 4;
      repeated ApplicationIdProto keep_alive_applications = 5;
    }
    message NodeHeartbeatResponseProto {
      optional int32 response_id = 1;
      optional MasterKeyProto container_token_master_key = 2;
      optional MasterKeyProto nm_token_master_key = 3;
      optional NodeActionProto nodeAction = 4;
      repeated ContainerIdProto containers_to_cleanup = 5;
      repeated ApplicationIdProto applications_to_cleanup = 6;
      optional int64 nextHeartBeatInterval = 7;
      optional string diagnostics_message = 8;
    }

nodeHeartbeat是一个周期性质的心跳协议，每次心跳最重要的是向ResourceManager汇报自己的NodeStatus，即NodeStatusProto，它包含当前
NodeManager所有调度的Container的状态信息，Node的健康信息（后面会详细解析），以及当前处于running的App列表。

NodeHeartbeatResponseProto为周期性心跳ResourceManager返回的信息，其中NodeActionProto可以实现ResourceManager关闭当前正在运行NodeManager的功能。
另外一个很重要的时候，containers_to_cleanup和applications_to_cleanup可以用来清理当前节点上相应的Container和App。

从实现上看，nodeHeartbeat应该是一个线程，周期性的调用nodeHeartbeat协议，逻辑代码如下：
    
    protected void startStatusUpdater() {    
        statusUpdaterRunnable = new Runnable() {
          public void run() {
            int lastHeartBeatID = 0;
            while (!isStopped) {
              try {
                NodeHeartbeatResponse response = null;
                NodeStatus nodeStatus = getNodeStatus(lastHeartBeatID);
                
                NodeHeartbeatRequest request =;
                response = resourceTracker.nodeHeartbeat(request);
                
                //get next heartbeat interval from response
                nextHeartBeatInterval = response.getNextHeartBeatInterval();
                updateMasterKeys(response);
    
                if (response.getNodeAction() == NodeAction.SHUTDOWN) {
                  context.setDecommissioned(true);
                  dispatcher.getEventHandler().handle(
                      new NodeManagerEvent(NodeManagerEventType.SHUTDOWN));
                  break;
                }
                if (response.getNodeAction() == NodeAction.RESYNC) {
                  dispatcher.getEventHandler().handle(
                      new NodeManagerEvent(NodeManagerEventType.RESYNC));
                  break;
                }    
                removeCompletedContainersFromContext();
    
                lastHeartBeatID = response.getResponseId();
                List<ContainerId> containersToCleanup = response.getContainersToCleanup();
                if (!containersToCleanup.isEmpty()) {
                  dispatcher.getEventHandler().handle(
                      new CMgrCompletedContainersEvent(containersToCleanup,
                        CMgrCompletedContainersEvent.Reason.BY_RESOURCEMANAGER));
                }
                List<ApplicationId> appsToCleanup =response.getApplicationsToCleanup();
                trackAppsForKeepAlive(appsToCleanup);
                if (!appsToCleanup.isEmpty()) {
                  dispatcher.getEventHandler().handle(
                      new CMgrCompletedAppsEvent(appsToCleanup,
                          CMgrCompletedAppsEvent.Reason.BY_RESOURCEMANAGER));
                }
              } catch (ConnectException e) {
                dispatcher.getEventHandler().handle(
                    new NodeManagerEvent(NodeManagerEventType.SHUTDOWN));
                throw new YarnRuntimeException(e);
              } catch (Throwable e) {
                LOG.error("Caught exception in status-updater", e);
              } finally {
                synchronized (heartbeatMonitor) {
                  nextHeartBeatInterval = nextHeartBeatInterval <= 0 ?
                      YarnConfiguration.DEFAULT_RM_NM_HEARTBEAT_INTERVAL_MS :
                        nextHeartBeatInterval;
                  try {
                    heartbeatMonitor.wait(nextHeartBeatInterval);
                  } 
                }
              }
            }
          }
        };
        statusUpdater =
            new Thread(statusUpdaterRunnable, "Node Status Updater");
        statusUpdater.start();
      }
  
主要的工作有：
+   构造nodeStatus，其中包括当前节点所有的信息，并调用nodeHeartbeat向ResourceManager发送心跳协议
+   处理ResourceManager对当前节点的SHUTDOWN和RESYNC
+   处理ResourceManager返回的containers_to_cleanup和applications_to_cleanup
+   每次心跳的间隔可以根据ResourceManager的当前情况，从心跳返回值中获取，从而可以控制NodeManager向ResourceManager的频率

-----------
end