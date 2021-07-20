Eureka
===
**Eureka Client**

1. 客户端解析入口

   spring.factories→EurekaClientAutoConfiguration→RefreshableEurekaClientConfiguration.eurekaClient()→new CloudEurekaClient()→super→@Inject DiscoveryClient

2. 获取注册表

   fetchRegistry(false)

3. 向服务端注册

   register()

4. 初始化定时任务（定时更新客户端注册表、定时续约、定时更新客户端信息至服务端）

   initScheduledTasks()
---
**Eureka Server**

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

---
