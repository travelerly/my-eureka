# **Eureka**

## **Eureka 体系架构**

![](images/eureka_architecture.jpg)

---

## **Eureka Client**

### **Eureka Client 重要 API**

#### **InstanceInfo**

该类用于保存一个微服务主机的信息。一个该类实例就代表了一个微服务主机。该主机注册到 Eureka Server 就是将其 InstanceInfo 写入到了 Eureka 注册表，且被其它 Server 读取到的该 Server 的信息也是这个 InstanceInfo。

```java
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

```java
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

```java
// key 为微服务名称，value 为 Application
private final Map<String, Application> appNameApplicationMap;
```

#### **Jersey 框架**

SpringCloud 中 Eureka Client 与 Eureka Server 的通信，以及 Eureka Server 之间的通信，均采用的是 Jersey 框架。

Jersey 框架是一个开源的RESTful 框架，实现了 JAX-RS 规范。该框架的作用与 SpringMVC 是相同的，其也是用户提交 URI 后，在处理器中进行路由匹配，路由到指定的后台业务。这个路由功能同样是由处理器完成的，只不过这个处理器不是 Controller，而是 Resource。

---

### **Eureka Client 分析**

1. #### **客户端解析入口**

   **@SpringBootApplication→spring.factories→EurekaClientAutoConfiguration→(内部类)RefreshableEurekaClientConfiguration.eurekaClient()→new CloudEurekaClient()→super→@Inject DiscoveryClient**

2. #### **获取注册表**

   **DiscoveryClient.fetchRegistry(false)**

   - getAndStoreFullRegistry()：获取全量注册表，并缓存到本地 region 注册表中 AtomicReference<Applications> localRegionApps。
   - getAndUpdateDelta(applications)：获取增量注册表，并更新本地缓存数据。
   - 本地缓存分为两类:
      - 缓存本地 Region 的注册表。AtomicReference<Applications> localRegionApps。
      - 缓存远程 Region 的注册信息。Map<String, Applications> remoteRegionVsApps：key：远程 Region，value：该远程 Region 的注册表 Applications

3. #### **向服务端注册**

   **DiscoveryClient.register()**

   ##### **Client 提交 register() 的时机**

   1. 在应用启动时可以直接进行 register() 注册，但前提是在配置文件中配置启动时注册
   2. 在续约 renew() 时，如果服务端返回的是 NOT_FOUND(404)，则提交 register() 注册请求
   3. 当客户端数据发生变更时，监听器触发调用 register() 注册请求

4. #### **初始化定时任务（定时更新本地缓存客户端注册表、定时续约、定时更新客户端数据至服务端）**

   **DiscoveryClient.initScheduledTasks()**

   - DiscoveryClient.CacheRefreshThread.run()→refreshRegistry()：定时更新本地缓存注册表
   - DiscoveryClient.HeartbeatThread.run()→renew()：定时续约。续约 renew() 时，如果服务端返回的是 NOT_FOUND(404)，则提交 register() 注册请求
   - InstanceInfoReplicator.onDemandUpdate()：按需更新（监听器监听本地客户端数据发生变更，从而触发监听器回调按需更新方法）
   - InstanceInfoReplicator.run()：定时更新客户端数据至服务端

   定时任务执行时，启动的是一次性定时任务，但在每个定时任务执行完毕后，会执行 finally，在finally 中又重新开启了一个定时任务，使得定时任务会一直执行下去。
   并且还会使用原子引用类 AtomicReference<Future> scheduledPeriodicRef，来保存当前任务。这样当监听器回调按需更新或者存在任务延迟时，新任务可以取消掉「cancel()」尚未执行完毕的任务，再开启新的任务，避免造成定时任务无限创建执行的问题。

5. #### **服务离线**

   1. 基于Actuator监控器实现，直接向客户端发送 POST 请求请求

      - 服务下架 **DiscoveryClient.shutdown()**：http://localhost:8083/actuator/shutdown，无需请求体。

      - 服务下线  **Spring-Cloud-netflix-eureka-client#EurekaServiceRegistry.setStatus()**：http://localhost:8083/actuator/serviceregistry，含请求体。（该方法称为服务平滑上下线，从 Spring Cloud 2020.0.0 版本开始，服务平滑上下线的监控终端由 service-registry 变更为了 serviceregistry）

        ```java
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

        ```java
        http://${server}:${port}/eureka/apps/${serviceName}/${instanceId}
        ```

      - 服务下线：通过向 eureka server 发送 PUT 请求来修改指定 client 的 status，其中 ${value} 的取值 为：OUT_OF_SERVICE 或 UP。

        ```java
        http://${server}:${port}/eureka/apps/${serviceName}/${instanceId}/status?value=${value}
        ```

---



## **Eureka Server**

### **Eureka Server 重要 API**

#### **InstanceResource**

Jersey 框架处理器，用于处理客户端发送的续约请求、状态修改请求、客户端下线/下架请求等。

#### **ApplicationResource**

Jersey 框架处理器，用于处理客户端发送的注册请求。

#### **ApplicationsResource**

Jersey 框架处理器，处理所有与 Applications 相关的请求（客户端的全量、增量下载等）。

#### **AbstractInstanceRegistry**

处理来自eureka客户端的所有注册请求

```java
// 服务端本地注册表。key：客户端名字；value：客户端的实例数据→InstanceInfo
private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry

// 服务端缓存客户端当前状态数据。key：InstanceId；value：客户端实例的当前状态 status
// 当前状态取值：InstanceStatus{UP、DOWN、STARTING、OUT_OF_SERVICE、UNKNOWN}
protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap

// 最近变更队列
private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue
```

#### **ResponseCacheImpl**

缓存注册表信息，用于处理客户端的下载请求（全量下载与增量下载）

```java
private final ConcurrentMap<Key, Value> readOnlyCacheMap

private final LoadingCache<Key, Value> readWriteCacheMap
```

> **readOnlyCacheMap 与 readWriteCacheMap 的数据来源以及他们之间的关系**
>
> 1. 在 ResponseCacheImpl 构造器中创建并初始化了这个读写缓存 readWriteCacheMap。
> 2. .readOnlyCacheMap 的数据来自于 readWriteCacheMap，在 ResponseCacheImpl 构造器中定义并开启了一个定时任务，用于定时从 readWriteCacheMap 中更新 readOnlyCacheMap 中的数据，这样，只要 readWriteCacheMap 中的数据若发生了变更，那么 readOnlyCacheMap 中的数据也随之更新了。
> 3. 使用定时任务更新 readOnlyCacheMap 中数据的好处是，为了保证对 readWriteCacheMap 的迭代稳定性。即将读写进行了分离，分离到了两个共享集合。但这种解决方案存在一个很严重的弊端：读、写两个集合的数据无法保证强一致性 ，即只能做到最终一致性 。所以这种方案的应用场景是，对数据的实时性要求不是很高，对数据是否是最新数据要求不高的场景。



---

### **Eureka Server 分析**

1. #### 处理客户端注册请求

   **ApplicationResource.addInstance()**

   server 完成的操作：

   - 将最新的客户端写入到注册表中
   - 将本次操作记录到最近变更队列”recentlyChangedQueue“缓存中
   - 本地注册完成后，进行 eureka-server 之间的数据同步

   ```java
   每分钟续约次数阈值的计算：
   this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
                   * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                   * serverConfig.getRenewalPercentThreshold());
   
   = 客户端数量 * (60 /「期望的客户端发送续约心跳的间隔时间，默认是30s」) * （自我保护机制开启的阈值因子，默认是0.85）
   = 客户端数量 * 每个客户端每分钟发送心跳的数量 * 阈值因子
   = 所有客户端每分钟发送心跳数量 * 阈值因子
   
   当前服务端开启自我保护机制的每分钟最小心跳数量(续约阈值)
     
   一旦自我保护机制开启了，那么就将当前server保护了起来，即当前server注册表中的所有client均不会过期，即使当前client没有在指定时间内「默认90s」发送续约，也不会将其从注册表中删除。这是为了保证server的可用性，即保证了"AP"。
   
   expectedNumberOfClientsSendingRenews 设置的越大，当前server开启自我保护机制的每分钟最小心跳数量觉越大，就越容易开启自我保护机制。
   ```

2. #### 处理客户端续约请求

   **InstanceResource.renewLease()**

   server 完成的操作：

   - 计算出当前客户端新的 status，并将其写入到注册表中
   - 本地更新完成后，进行 eureka-server 之间的数据同步

3. #### 处理客户端状态修改请求【"OUT_OF_SERVICES"、"UP"】

   **InstanceResource.statusUpdate()**

   server 完成的操作：

   - 修改了注册表中的 InstanceInfo 的 status
   - 将新的状态记录到了 overriddenInstanceStatusMap 缓存中
   - 将本次修改记录到了最近更新队列“ recentlyChangedQueue ”缓存中
   - 本地更新完成后，进行 eureka-server 之间的数据同步

4. #### 处理客户端下线「删除 overridden 状态」请求

   **InstanceResource.deleteStatusUpdate()**

   server 完成的操作：

   - 将指定的客户端的 overriddenStatus 从 overriddenInstanceStatusMap 中删除
   - 修改注册表中该客户端的 overriddenStatus 为 UNKNOWN
   - 修改注册表中的客户端的 status 为 UNKNOWN
   - 将本次修改记录到了最近更新队列“ recentlyChangedQueue ”缓存中
   - 修改注册表中该客户端的 lastUpdatedTimestamp
   - 本地更新完成后，进行 eureka-server 之间的数据同步

   > 注意：被没有将该客户端从注册表中"物理删除"，仅为"逻辑删除"，即本地注册表中 status 设置为 UNKNOWN

5. #### 处理客户端下架请求

   **InstanceResource.cancelLease()**

   server 完成的操作：

   - 将该客户端从注册表中删除，返回被删除的 lease 数据。（Lease<InstanceInfo> 相当于 InstanceInfo）
   - 将该客户端的 overriddenStatus 从缓存 overriddenInstanceStatusMap 中删除
   - 将本次修改记录到了最近更新队列“ recentlyChangedQueue ”缓存中
   - 修改注册表中该客户端的 lastUpdatedTimestamp
   - 本地更新完成后，进行 eureka-server 之间的数据同步

6. #### 处理客户端全量、增量下载下载请求

   **ApplicationsResource.getContainers()→ResponseCacheImpl.get()→getValue()→readOnlyCacheMap/readWriteCacheMap→AbstractInstanceRegistry.getApplicationsFromMultipleRegions()**

   **ApplicationsResource.getContainerDifferential()→ResponseCacheImpl.get()→getValue()→readOnlyCacheMap/readWriteCacheMap→AbstractInstanceRegistry.getApplicationDeltasFromMultipleRegions**

   **readOnlyCacheMap 与 readWriteCacheMap 的数据来源以及他们之间的关系**

   - 在 ResponseCacheImpl 构造器中创建并初始化了这个读写缓存 readWriteCacheMap，封装了全量的注册表数据。
   - readOnlyCacheMap 的数据来自于 readWriteCacheMap，在 ResponseCacheImpl 构造器中定义并开启了一个定时任务，定时将 readWriteCacheMap 中的数据更新到 readOnlyCacheMap 中，这样，只要 readWriteCacheMap 中的数据若发生了变更，那么 readOnlyCacheMap 中的数据也随之更新了。
   - 使用定时任务更新 readOnlyCacheMap 中数据的好处是，为了保证对 readWriteCacheMap 的迭代稳定性。即将读写进行了分离，分离到了两个共享集合。但这种解决方案存在一个很严重的弊端：读、写两个集合的数据无法保证强一致性 ，即只能做到最终一致性 。所以这种方案的应用场景是，对数据的实时性要求不是很高，对数据是否是最新数据要求不高的场景。

7. #### 定时清理过期客户端

   **spring.factories→EurekaServerAutoConfiguration→@Import(EurekaServerInitializerConfiguration.class)→EurekaServerInitializerConfiguration.start()→PeerAwareInstanceRegistryImpl.openForTraffic()**

   先取出过期的客户端数据，然后再计算出在配置的存活阈值下的可删除客户端数量，选择过期客户端数量与可删除客户端数量之间最小值，使用洗牌算法进行删除，调用是服务下架接口，即从注册表中删除过期客户端数据。

8. #### Eureka Server 处理过程中的读写锁问题

   **读写锁使用场景**：

   | 方法名                                  | 操作的共享集合                 | 读/写操作 | 添加的锁 |
      | --------------------------------------- | ------------------------------ | --------- | -------- |
   | register()：注册                        | registry、recentlyChangedQueue | 写        | 读锁     |
   | statusUpdate()：状态修改                | registry、recentlyChangedQueue | 写        | 读锁     |
   | internalCancel()：服务下架              | registry、recentlyChangedQueue | 写        | 读锁     |
   | deleteStatusOverride()：删除 overridden | registry、recentlyChangedQueue | 写        | 读锁     |
   | renew()：续约                           | registry                       | 写        | 无锁     |
   | 全量下载                                | registry                       | 读        | 无锁     |
   | 增量下载                                | registry、recentlyChangedQueue | 读        | 写锁     |

   > **registry：注册表**
   >
   > **recentlyChangedQueue：最近变更队列**

   **加锁方法的特征**

   - 所有对最近变更队列 recentlyChangedQueue 共享集合操作的方法都添加了锁。而没有对其进行操作的方法，没有加锁。

   **为什么写操作要添加读锁？**

   - 写锁是排他锁，如果为这些对 recentlyChangedQueue 进行的写操作添加写锁的话，则意味着当有一个写操作发生时，其他所有对 recentlyChangedQueue 进行的读/写操作都会被阻塞，导致效率低下。
   - 而若加了读锁，则会使得所有对 recentlyChangedQueue 进行的写操作实现并行，从而提高了并发，进而提高了执行效率。而 recentlyChangedQueue 是 JUC 的队列，是线程安全的。
   - 读锁是共享锁，所以，在同时处理注册表 registry 时，不会因为加了读锁而影响对 registry 操作的并行效率。

   **为什么读操作添加写锁？**

   - 为了保证对共享集合 recentlyChangedQueue 的读/写操作的互斥，但因为加了写锁，导致读操作的效率降低，无法实现读操作的并行，只能串行习执行。

   **读写锁反加应用场景**

   - 写操作相对于读操作更加频繁的场景。

   **续约操作能否添加写锁？**

   - 不能，因为续约操作是一个发生非常频繁的写操作，若加了写锁，则意味着其他客户端无法实现并行操作，造成阻塞。

   **续约操作能否添加读锁？**

   - 不能，因为添加的读锁的目的是为了与写操作实现互斥。所以在上述方法中，所有对注册表 registry 的操作中，均没有添加写锁，所以这里的写操作也无需添加读锁。

   **能否不加锁？**

   - 若对 recentlyChangedQueue 的操作不加锁，可能会存在同时对 recentlyChangedQueue 进行读操作和写操作的情况，可能会引发对 recentlyChangedQueue 操作的迭代稳定性问题。

   **为什么全量下载没有添加写锁？**

   - 若为其添加了写锁，则会导致某个客户端在读取期间，其他客户端的续约请求被阻塞。

---

