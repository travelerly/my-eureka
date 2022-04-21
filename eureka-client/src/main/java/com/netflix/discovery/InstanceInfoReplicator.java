package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo;

    // 配置的定时查看时间，默认每 30s 查看一次
    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Future> scheduledPeriodicRef;

    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        // 令牌桶限流器
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        // 配置的定时查看时间，默认每 30s 查看一次
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        this.burstSize = burstSize;
        // 每分钟允许的最大变化率：每分钟 4 次
        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        if (started.compareAndSet(false, true)) {
            // 发生变更，标记InstanceInfo发生了变更，记录当前 InstanceInfo 在客户端被修改的时间戳
            instanceInfo.setIsDirty();  // for initial register
            // 启动任务，即调用 run()
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            // 将当前任务存入原子应用的缓存中
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }

    /**
     * 按需更新（监听配置信息的变更）
     * 监听器监听客户端配置信息是否发生变更，若发生变更则触发监听器回调按需更新方法，将此变更更新至服务端
     * @return
     */
    public boolean onDemandUpdate() {
        // rateLimiter：令牌桶限流器，防止客户端变更太过频繁。「一般项目的变更不会过于频繁，此处使用限流器过于谨慎，没有必要」
        // allowedRatePerMinute：每分钟允许的最大变化率：每分钟 4 次。
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");
                        // 从原子引用缓存 AtomicReference<Future> scheduledPeriodicRef 中获取当前正在执行的任务
                        Future latestPeriodic = scheduledPeriodicRef.get();
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            // 如果当前存在正在执行的定时任务，并且这个定时任务尚未执行完成，则取消这个当前的定时任务
                            latestPeriodic.cancel(false);
                        }
                        // 直接执行将客户端配置信息的变更更新至服务端
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }

    /**
     * 将客户端配置信息的变更更新至服务端
     */
    public void run() {
        try {
            // 刷新实例数据（主要是刷新了续约信息，将配置信息变更的数据封装进续约信息中，再讲续约信息封装进实例数据 InstanceInfo 中）
            discoveryClient.refreshInstanceInfo();
            // 获取客户端发生变更的时间戳
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {
                // 客户端配置信息发生变更，向服务端发起注册请求
                discoveryClient.register();
                // 注册完信息后，取消实例数据变更【脏数据】标识
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            // 再次启动一个定时任务（定时查看客户端配置信息是否发生变更，默认每 30s 查看一次，即 replicationIntervalSeconds=30s）
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            // 原子引用类 AtomicReference<Future> scheduledPeriodicRef，用于保存当前新建的定时任务
            scheduledPeriodicRef.set(next);
        }
    }

}
