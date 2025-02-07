/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.server.worker.runner;

import org.apache.dolphinscheduler.common.Constants;
import org.apache.dolphinscheduler.common.storage.StorageOperate;
import org.apache.dolphinscheduler.common.thread.Stopper;
import org.apache.dolphinscheduler.common.thread.ThreadUtils;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContext;
import org.apache.dolphinscheduler.plugin.task.api.TaskExecutionContextCacheManager;
import org.apache.dolphinscheduler.plugin.task.api.enums.ExecutionStatus;
import org.apache.dolphinscheduler.server.worker.config.WorkerConfig;
import org.apache.dolphinscheduler.server.worker.metrics.WorkerServerMetrics;
import org.apache.dolphinscheduler.server.worker.processor.TaskCallbackService;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Manage tasks
 */
@Component
public class WorkerManagerThread implements Runnable {

    private final Logger logger = LoggerFactory.getLogger(WorkerManagerThread.class);

    /**
     * task queue
     */
    private final BlockingQueue<TaskExecuteThread> waitSubmitQueue;

    @Autowired(required = false)
    private StorageOperate storageOperate;

    /**
     * thread executor service
     */
    private final WorkerExecService workerExecService;

    /**
     * task callback service
     */
    @Autowired
    private TaskCallbackService taskCallbackService;

    private volatile int workerExecThreads;

    /**
     * running task
     */
    private final ConcurrentHashMap<Integer, TaskExecuteThread> taskExecuteThreadMap = new ConcurrentHashMap<>();

    public WorkerManagerThread(WorkerConfig workerConfig) {
        workerExecThreads = workerConfig.getExecThreads();
        this.waitSubmitQueue = new DelayQueue<>();
        workerExecService = new WorkerExecService(
            ThreadUtils.newDaemonFixedThreadExecutor("Worker-Execute-Thread", workerConfig.getExecThreads()),
            taskExecuteThreadMap
        );
    }

    public TaskExecuteThread getTaskExecuteThread(Integer taskInstanceId) {
        return this.taskExecuteThreadMap.get(taskInstanceId);
    }

    /**
     * get wait submit queue size
     *
     * @return queue size
     */
    public int getWaitSubmitQueueSize() {
        return waitSubmitQueue.size();
    }

    /**
     * get thread pool queue size
     *
     * @return queue size
     */
    public int getThreadPoolQueueSize() {
        return this.workerExecService.getThreadPoolQueueSize();
    }

    /**
     * Kill tasks that have not been executed, like delay task
     * then send Response to Master, update the execution status of task instance
     */
    public void killTaskBeforeExecuteByInstanceId(Integer taskInstanceId) {
        waitSubmitQueue.stream()
                          .filter(taskExecuteThread -> taskExecuteThread.getTaskExecutionContext().getTaskInstanceId() == taskInstanceId)
                          .forEach(waitSubmitQueue::remove);
        sendTaskKillResponse(taskInstanceId);
    }

    /**
     * kill task before execute , like delay task
     */
    private void sendTaskKillResponse(Integer taskInstanceId) {
        TaskExecutionContext taskExecutionContext = TaskExecutionContextCacheManager.getByTaskInstanceId(taskInstanceId);
        if (taskExecutionContext == null) {
            return;
        }
        taskExecutionContext.setCurrentExecutionStatus(ExecutionStatus.KILL);
        taskCallbackService.sendTaskExecuteResponseCommand(taskExecutionContext);
    }

    /**
     * submit task
     *
     * @param taskExecuteThread taskExecuteThread
     * @return submit result
     */
    public boolean offer(TaskExecuteThread taskExecuteThread) {
        if (waitSubmitQueue.size() > workerExecThreads) {
            WorkerServerMetrics.incWorkerSubmitQueueIsFullCount();
            // if waitSubmitQueue is full, it will wait 1s, then try add
            ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
            if (waitSubmitQueue.size() > workerExecThreads) {
                return false;
            }
        }
        return waitSubmitQueue.offer(taskExecuteThread);
    }

    public void start() {
        Thread thread = new Thread(this, this.getClass().getName());
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        Thread.currentThread().setName("Worker-Execute-Manager-Thread");
        TaskExecuteThread taskExecuteThread;
        while (Stopper.isRunning()) {
            try {
                if (this.getThreadPoolQueueSize() <= workerExecThreads) {
                    taskExecuteThread = waitSubmitQueue.take();
                    taskExecuteThread.setStorageOperate(storageOperate);
                    workerExecService.submit(taskExecuteThread);
                } else {
                    WorkerServerMetrics.incWorkerOverloadCount();
                    logger.info("Exec queue is full, waiting submit queue {}, waiting exec queue size {}",
                            this.getWaitSubmitQueueSize(), this.getThreadPoolQueueSize());
                    ThreadUtils.sleep(Constants.SLEEP_TIME_MILLIS);
                }
            } catch (Exception e) {
                logger.error("An unexpected interrupt is happened, "
                    + "the exception will be ignored and this thread will continue to run", e);
            }
        }
    }
}
