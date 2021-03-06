/**
 * Copyright 2017 Pivotal Software, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.metrics.instrument.scheduling;

import org.springframework.metrics.instrument.*;
import org.springframework.metrics.instrument.binder.MeterBinder;
import org.springframework.metrics.instrument.internal.TimedExecutorService;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadPoolExecutor;

import static java.util.Collections.singletonList;

/**
 * Monitors the status of executor service pools. Does not record timings on operations executed in the {@link ExecutorService},
 * as this requires the instance to be wrapped. Timings are provided separately by wrapping the executor service
 * with {@link TimedExecutorService}.
 *
 * @author Jon Schneider
 * @author Clint Checketts
 */
public class ExecutorServiceMetrics implements MeterBinder {
    private final ExecutorService executorService;
    private final String name;
    private final Iterable<Tag> tags;

    public ExecutorServiceMetrics(ExecutorService executorService, String name, Iterable<Tag> tags) {
        this.name = name;
        this.tags = tags;
        this.executorService = executorService;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        if (executorService == null) {
            return;
        }

        String className = executorService.getClass().getName();

        if (executorService instanceof ThreadPoolExecutor) {
            monitor(registry, (ThreadPoolExecutor) executorService);
        } else if (className.equals("java.util.concurrent.Executors$DelegatedScheduledExecutorService")) {
            monitor(registry, unwrapThreadPoolExecutor(executorService, executorService.getClass()));
        } else if (className.equals("java.util.concurrent.Executors$FinalizableDelegatedExecutorService")) {
            monitor(registry, unwrapThreadPoolExecutor(executorService, executorService.getClass().getSuperclass()));
        } else if (executorService instanceof ForkJoinPool) {
            monitor(registry, (ForkJoinPool) executorService);
        }
    }

    /**
     * Every ScheduledThreadPoolExecutor created by {@link Executors} is wrapped. Also,
     * {@link Executors#newSingleThreadExecutor()} wrap a regular {@link ThreadPoolExecutor}.
     */
    private ThreadPoolExecutor unwrapThreadPoolExecutor(ExecutorService executor, Class<?> wrapper) {
        try {
            Field e = wrapper.getDeclaredField("e");
            e.setAccessible(true);
            return (ThreadPoolExecutor) e.get(executorService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Do nothing. We simply can't get to the underlying ThreadPoolExecutor.
        }
        return null;
    }

    private void monitor(MeterRegistry registry, ThreadPoolExecutor tp) {
        if (tp == null) {
            return;
        }

        registry.register(Meters.build(name)
                .type(Meter.Type.Counter)
                .tags(tags)
                .create(tp, (n, tpRef) -> Arrays.asList(
                        // The sum of the three lifecycle phases is monotonically increasing, though scheduled and active
                        // can go up and down as tasks are added and completed. The sum of these three measurements
                        // gives you the total of all tasks submitted irrespective of whether they have been completed or not.
                        new Measurement(n, singletonList(Tag.of("lifecycle", "scheduled")), tpRef.getTaskCount()),
                        new Measurement(n, singletonList(Tag.of("lifecycle", "completed")), tpRef.getCompletedTaskCount()),
                        new Measurement(n, singletonList(Tag.of("lifecycle", "active")), tpRef.getActiveCount())
                )));

        registry.gauge(name + "_queue_size", tags, tp, tpRef -> tpRef.getQueue().size());
        registry.gauge(name + "_pool_size", tags, tp, ThreadPoolExecutor::getPoolSize);
    }

    private void monitor(MeterRegistry registry, ForkJoinPool fj) {
        registry.gauge(name + "_active", fj, ForkJoinPool::getActiveThreadCount);
        registry.gauge(name + "_queued_tasks", fj, ForkJoinPool::getQueuedTaskCount);
        registry.gauge(name + "_running_threads", fj, ForkJoinPool::getRunningThreadCount);
        registry.gauge(name + "_steal_count", fj, ForkJoinPool::getStealCount);
    }
}
