/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.spring.messaging.endpoint;

import com.microsoft.azure.spring.messaging.container.ListenerContainerFactory;
import com.microsoft.azure.spring.messaging.container.MessageListenerContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates the necessary {@link MessageListenerContainer} instances for the
 * registered {@linkplain AzureListenerEndpoint endpoints}. Also manages the
 * lifecycle of the listener containers, in particular within the lifecycle
 * of the application context.
 *
 * <p>Contrary to {@link MessageListenerContainer MessageListenerContainers}
 * created manually, listener containers managed by registry are not beans
 * in the application context and are not candidates for autowiring.
 * Use {@link #getListenerContainers()} if you need to access this registry's
 * listener containers for management purposes.
 *
 * @author Warren Zhu
 * @see AzureListenerEndpoint
 * @see MessageListenerContainer
 * @see ListenerContainerFactory
 */
@Slf4j
public class AzureListenerEndpointRegistry
        implements DisposableBean, SmartLifecycle, ApplicationContextAware, ApplicationListener<ContextRefreshedEvent> {

    private final Map<String, MessageListenerContainer> listenerContainers = new ConcurrentHashMap<>();

    @Nullable
    private ApplicationContext applicationContext;

    private boolean contextRefreshed;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() == this.applicationContext) {
            this.contextRefreshed = true;
        }
    }

    /**
     * Return the managed {@link MessageListenerContainer} instance(s).
     */
    public Collection<MessageListenerContainer> getListenerContainers() {
        return Collections.unmodifiableCollection(this.listenerContainers.values());
    }

    /**
     * Create a message listener container for the given {@link AzureListenerEndpoint}.
     * <p>This create the necessary infrastructure to honor that endpoint
     * with regards to its configuration.
     * <p>The {@code startImmediately} flag determines if the container should be
     * started immediately.
     *
     * @param endpoint         the endpoint to add
     * @param factory          the listener factory to use
     * @param startImmediately start the container immediately if necessary
     * @see #getListenerContainers()
     */
    public void registerListenerContainer(AzureListenerEndpoint endpoint, ListenerContainerFactory<?> factory,
            boolean startImmediately) {

        Assert.notNull(endpoint, "Endpoint must not be null");
        Assert.notNull(factory, "Factory must not be null");
        String id = endpoint.getId();
        Assert.hasText(id, "Endpoint id must be set");

        synchronized (this.listenerContainers) {
            if (this.listenerContainers.containsKey(id)) {
                throw new IllegalStateException("Another endpoint is already registered with id '" + id + "'");
            }
            MessageListenerContainer container = createListenerContainer(endpoint, factory);
            this.listenerContainers.put(id, container);
            if (startImmediately) {
                startIfNecessary(container);
            }
        }
    }

    /**
     * Create a message listener container for the given {@link AzureListenerEndpoint}.
     * <p>This create the necessary infrastructure to honor that endpoint
     * with regards to its configuration.
     *
     * @param endpoint the endpoint to add
     * @param factory  the listener factory to use
     * @see #registerListenerContainer(AzureListenerEndpoint, ListenerContainerFactory, boolean)
     */
    public void registerListenerContainer(AzureListenerEndpoint endpoint, ListenerContainerFactory<?> factory) {
        registerListenerContainer(endpoint, factory, true);
    }

    /**
     * Create and start a new container using the specified factory.
     */
    protected MessageListenerContainer createListenerContainer(AzureListenerEndpoint endpoint,
            ListenerContainerFactory<?> factory) {

        MessageListenerContainer listenerContainer = factory.createListenerContainer(endpoint);

        if (listenerContainer instanceof InitializingBean) {
            try {
                ((InitializingBean) listenerContainer).afterPropertiesSet();
            } catch (Exception ex) {
                throw new BeanInitializationException("Failed to initialize message listener container", ex);
            }
        }

        int containerPhase = listenerContainer.getPhase();
        if (containerPhase < Integer.MAX_VALUE) {  // a custom phase value
            if (this.getPhase() < Integer.MAX_VALUE && this.getPhase() != containerPhase) {
                throw new IllegalStateException(
                        "Encountered phase mismatch between container factory definitions: " + this.getPhase() +
                                " vs " + containerPhase);
            }
        }

        return listenerContainer;
    }

    @Override
    public void start() {
        for (MessageListenerContainer listenerContainer : getListenerContainers()) {
            startIfNecessary(listenerContainer);
        }
    }

    @Override
    public void stop() {
        for (MessageListenerContainer listenerContainer : getListenerContainers()) {
            listenerContainer.stop();
        }
    }

    @Override
    public boolean isAutoStartup() {
        return false;
    }

    @Override
    public void stop(Runnable callback) {
        Collection<MessageListenerContainer> listenerContainers = getListenerContainers();
        AggregatingCallback aggregatingCallback = new AggregatingCallback(listenerContainers.size(), callback);
        for (MessageListenerContainer listenerContainer : listenerContainers) {
            listenerContainer.stop(aggregatingCallback);
        }
    }

    @Override
    public boolean isRunning() {
        for (MessageListenerContainer listenerContainer : getListenerContainers()) {
            if (listenerContainer.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start the specified {@link MessageListenerContainer} if it should be started
     * on startup or when start is called explicitly after startup.
     *
     * @see MessageListenerContainer#isAutoStartup()
     */
    private void startIfNecessary(MessageListenerContainer listenerContainer) {
        if (this.contextRefreshed || listenerContainer.isAutoStartup()) {
            listenerContainer.start();
        }
    }

    @Override
    public void destroy() {
        for (MessageListenerContainer listenerContainer : getListenerContainers()) {
            if (listenerContainer instanceof DisposableBean) {
                try {
                    ((DisposableBean) listenerContainer).destroy();
                } catch (Throwable ex) {
                    log.warn("Failed to destroy message listener container", ex);
                }
            }
        }
    }

    @Override
    public int getPhase() {
        return 0;
    }

    private static class AggregatingCallback implements Runnable {

        private final AtomicInteger count;

        private final Runnable finishCallback;

        public AggregatingCallback(int count, Runnable finishCallback) {
            this.count = new AtomicInteger(count);
            this.finishCallback = finishCallback;
        }

        @Override
        public void run() {
            if (this.count.decrementAndGet() == 0) {
                this.finishCallback.run();
            }
        }
    }

}
