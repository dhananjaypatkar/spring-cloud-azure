/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See LICENSE in the project root for
 * license information.
 */

package com.microsoft.azure.spring.messaging.endpoint;

import com.microsoft.azure.spring.messaging.container.ListenerContainerFactory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.lang.Nullable;
import org.springframework.messaging.handler.annotation.support.MessageHandlerMethodFactory;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper bean for registering {@link AzureListenerEndpoint} with a {@link AzureListenerEndpointRegistry}.
 *
 * @author Warren Zhu
 */
@Getter
@Setter
public class AzureListenerEndpointRegistrar implements BeanFactoryAware, InitializingBean {

    private final List<AzureListenerEndpointDescriptor> endpointDescriptors = new ArrayList<>();
    @Nullable
    private AzureListenerEndpointRegistry endpointRegistry;
    @Nullable
    private MessageHandlerMethodFactory messageHandlerMethodFactory;
    @Nullable
    private ListenerContainerFactory<?> containerFactory;
    @Nullable
    private String containerFactoryBeanName;
    @Nullable
    private BeanFactory beanFactory;
    private boolean startImmediately;

    private Object mutex = this.endpointDescriptors;

    /**
     * A {@link BeanFactory} only needs to be available in conjunction with
     * {@link #setContainerFactoryBeanName}.
     */
    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        if (beanFactory instanceof ConfigurableBeanFactory) {
            this.mutex = ((ConfigurableBeanFactory) beanFactory).getSingletonMutex();
        }
    }

    @Override
    public void afterPropertiesSet() {
        registerAllEndpoints();
    }

    protected void registerAllEndpoints() {
        Assert.state(this.endpointRegistry != null, "No AzureListenerEndpointRegistry set");
        synchronized (this.mutex) {
            for (AzureListenerEndpointDescriptor descriptor : this.endpointDescriptors) {
                this.endpointRegistry
                        .registerListenerContainer(descriptor.endpoint, resolveContainerFactory(descriptor));
            }
            this.startImmediately = true;  // trigger immediate startup
        }
    }

    private ListenerContainerFactory<?> resolveContainerFactory(AzureListenerEndpointDescriptor descriptor) {
        if (descriptor.containerFactory != null) {
            return descriptor.containerFactory;
        } else if (this.containerFactory != null) {
            return this.containerFactory;
        } else if (this.containerFactoryBeanName != null) {
            Assert.state(this.beanFactory != null, "BeanFactory must be set to obtain container factory by bean name");
            // Consider changing this if live change of the factory is required...
            this.containerFactory =
                    this.beanFactory.getBean(this.containerFactoryBeanName, ListenerContainerFactory.class);
            return this.containerFactory;
        } else {
            throw new IllegalStateException(
                    "Could not resolve the " + ListenerContainerFactory.class.getSimpleName() + " to use for [" +
                            descriptor.endpoint + "] no factory was given and no default is set.");
        }
    }

    /**
     * Register a new {@link AzureListenerEndpoint} alongside the
     * {@link ListenerContainerFactory} to use to create the underlying container.
     * <p>The {@code factory} may be {@code null} if the default factory has to be
     * used for that endpoint.
     */
    public void registerEndpoint(AzureListenerEndpoint endpoint, @Nullable ListenerContainerFactory<?> factory) {
        Assert.notNull(endpoint, "Endpoint must not be null");
        Assert.hasText(endpoint.getId(), "Endpoint id must be set");

        // Factory may be null, we defer the resolution right before actually creating the container
        AzureListenerEndpointDescriptor descriptor = new AzureListenerEndpointDescriptor(endpoint, factory);

        synchronized (this.mutex) {
            if (this.startImmediately) {  // register and start immediately
                Assert.state(this.endpointRegistry != null, "No AzureListenerEndpointRegistry set");
                this.endpointRegistry
                        .registerListenerContainer(descriptor.endpoint, resolveContainerFactory(descriptor), true);
            } else {
                this.endpointDescriptors.add(descriptor);
            }
        }
    }

    /**
     * Register a new {@link AzureListenerEndpoint} using the default
     * {@link ListenerContainerFactory} to create the underlying container.
     *
     * @see #setContainerFactory(ListenerContainerFactory)
     * @see #registerEndpoint(AzureListenerEndpoint, ListenerContainerFactory)
     */
    public void registerEndpoint(AzureListenerEndpoint endpoint) {
        registerEndpoint(endpoint, null);
    }

    private static class AzureListenerEndpointDescriptor {

        public final AzureListenerEndpoint endpoint;

        @Nullable
        public final ListenerContainerFactory<?> containerFactory;

        public AzureListenerEndpointDescriptor(AzureListenerEndpoint endpoint,
                @Nullable ListenerContainerFactory<?> containerFactory) {

            this.endpoint = endpoint;
            this.containerFactory = containerFactory;
        }
    }

}
