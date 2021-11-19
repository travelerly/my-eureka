# **Eureka**

## **Eureka 体系架构**

![](images/eureka_architecture.jpg)

---

## **Eureka Client**

### **Eureka Client 重要 API**

#### **InstanceInfo**

该类用于保存一个微服务主机的信息。一个该类实例就代表了一个微服务主机。该主机注册到 Eureka Server 就是将其 InstanceInfo 写入到了 Eureka 注册表，且被其它 Server 读取到的该 Server 的信息也是这个 InstanceInfo。

```text
// 记录当前 client 在 server 端的状态
private volatile InstanceStatus status = InstanceStatus.UP;
// 该状态用于在 client 提交注册请求与 Renew 续约请求时,计算 client 在 server 端的状态
private volatile InstanceStatus overriddenStatus = InstanceStatus.UNKNOWN;

// 续约信息
private volatile LeaseInfo leaseInfo;

// 记录当前 InstanceInfo 在 server 端被修改的时间戳
private volatile Long lastUpdatedTimestamp;
// 记录当前 InstanceInfo 在 Client 端被修改的时间戳
private volatile Long lastDirtyTimestamp;

// 重写了 equals() 方法：只要两个 InstanceInfo 的 instanceId 相同，那么这两个 InstanceInfo 就相同
@Override
public boolean equals(Object obj)
```

#### **Application**

一个 Application 实例中保存着一个特定微服务的所有提供者实例

```text
// 微服务名称
private String name;

// 保存着当前 name 所指定的微服务名称的所有 InstanceInfo
@XStreamImplicit
private final Set<InstanceInfo> instances;

// key 为 instanceId；value 为 instanceInfo
private final Map<String, InstanceInfo> instancesMap
```

#### **Applications**

该类封装了来自于 Eureka Server 的所有注册信息，可以称为"客户端注册表"，之所以要强调是客户端是因为，服务端的注册表是另外的一个 Map

```text
// key 为微服务名称，value 为 Application
private final Map<String, Application> appNameApplicationMap;
```

#### **Jersey 框架**

SpringCloud 中 Eureka Client 与 Eureka Server 的通信，以及 Eureka Server 之间的通信，均采用的是 Jersey 框架。

Jersey 框架是一个开源的RESTful 框架，实现了 JAX-RS 规范。该框架的作用与 SpringMVC 是相同的，其也是用户提交 URI 后，在处理器中进行路由匹配，路由到指定的后台业务。这个路由功能同样是由处理器完成的，只不过这个处理器不是 Controller，而是 Resource。

---

#### **Eureka Client 分析**

1. **客户端解析入口**

   **@SpringBootApplication→spring.factories→EurekaClientAutoConfiguration→(内部类)RefreshableEurekaClientConfiguration.eurekaClient()→new CloudEurekaClient()→super→@Inject DiscoveryClient**

2. **获取注册表**

   **DiscoveryClient.fetchRegistry(false)**

   - getAndStoreFullRegistry()：获取全量注册表，并缓存到本地 region 注册表中 AtomicReference<Applications> localRegionApps。

   - getAndUpdateDelta(applications)：获取增量注册表，并更新本地缓存数据。

   - ```text
     本地缓存分为两类
     1.缓存本地 Region 的注册表。AtomicReference<Applications> localRegionApps。
     2.缓存远程 Region 的注册信息。Map<String, Applications> remoteRegionVsApps：key：远程 Region，value：该远程 Region 的注册表 Applications
     ```

3. **向服务端注册**

   **DiscoveryClient.register()**

   ##### **Client 提交 register() 的时机**

   1. 在应用启动时可以直接进行 register() 注册，但前提是在配置文件中配置启动时注册
   2. 在续约 renew() 时，如果服务端返回的是 NOT_FOUND(404)，则提交 register() 注册请求
   3. 当客户端数据发生变更时，监听器触发调用 register() 注册请求

4. **初始化定时任务（定时更新本地缓存客户端注册表、定时续约、定时更新客户端数据至服务端）**

   **DiscoveryClient.initScheduledTasks()**

   - DiscoveryClient.CacheRefreshThread.run()→refreshRegistry()：定时更新本地缓存注册表
   - DiscoveryClient.HeartbeatThread.run()→renew()：定时续约。续约 renew() 时，如果服务端返回的是 NOT_FOUND(404)，则提交 register() 注册请求
   - InstanceInfoReplicator.onDemandUpdate()：按需更新（监听器监听本地客户端数据发生变更，从而触发监听器回调按需更新方法）
   - InstanceInfoReplicator.run()：定时更新客户端数据至服务端

   定时任务执行时，启动的是一次性定时任务，但在每个定时任务执行完毕后，会执行 finally，在finally 中又重新开启了一个定时任务，使得定时任务会一直执行下去。
   并且还会使用原子引用类 AtomicReference<Future> scheduledPeriodicRef，来保存当前任务。这样当监听器回调按需更新或者存在任务延迟时，新任务可以取消掉「cancel()」尚未执行完毕的任务，再开启新的任务，避免造成定时任务无限创建执行的问题。

5. **服务离线**

   1. 基于Actuator监控器实现，直接向客户端发送 POST 请求请求

      - 服务下架 **DiscoveryClient.shutdown()**：http://localhost:8083/actuator/shutdown，无需请求体。

      - 服务下线  **Spring-Cloud-netflix-eureka-client#EurekaServiceRegistry.setStatus()**：http://localhost:8083/actuator/serviceregistry，含请求体。（该方法称为服务平滑上下线，从 Spring Cloud 2020.0.0 版本开始，服务平滑上下线的监控终端由 service-registry 变更为了 serviceregistry）

        ```text
        {
        	"status":"OUT_OF_SERVICE" 
        }
        --------------------------
        {
        	"status":"UP" 
        }
        --------------------------
        {
        	"status":"CANCEL_OVERRIDE" 
        }
        
        特殊状态 CANCEL_OVERRIDE：用户提交的状态修改请求中指定的状态，除了 InstanceInfo 的内置枚举类 InstanceStatus 中定义的状态外，还可以是CANCEL_OVERRIDE 状态。若用户提交的状态为 CANCEL_OVERRIDE，则 Client 会通过 Jersey 向 Server 提交一个 DELETE 请求，用于在 Server 端将对应InstanceInfo 的 overridenStatus 修改为 UNKNWON，即删除了原来的 overridenStatus 的状态值。此时，该 Client 发送的心跳 Server 是不接收的。Server 会向该Client 返回 404。
        ```

   2. 直接向服务端发送请求

      - 服务下架：通过向 eureka server 发送 DELETE 请求来删除指定 client 的服务。？？？？？

        ```text
        http://${server}:${port}/eureka/apps/${serviceName}/${instanceId}
        ```

      - 服务下线：通过向 eureka server 发送 PUT 请求来修改指定 client 的 status，其中 ${value} 的取值 为：OUT_OF_SERVICE 或 UP。

        ```text
        http://${server}:${port}/eureka/apps/${serviceName}/${instanceId}/status?value=${value}
        ```

---


## **Eureka Server**

1. 处理客户端注册请求

   ApplicationResource.addInstance()

2. 处理客户端续约请求

   InstanceResource.renewLease()

3. 处理客户端状态修改请求

   InstanceResource.statusUpdate()

4. 处理客户端下线「删除overridden状态」请求

   InstanceResource.deleteStatusUpdate()

5. 处理客户端下架请求

   InstanceResource.cancelLease()

6. 处理客户端全量、增量下载下载请求

   ApplicationsResource.getContainers()、ApplicationsResource.getContainerDifferential()

7. 定时清理过期客户端

   spring.factories→EurekaServerAutoConfiguration→@Import(EurekaServerInitializerConfiguration.class)→EurekaServerInitializerConfiguration.start()→PeerAwareInstanceRegistryImpl.openForTraffic()