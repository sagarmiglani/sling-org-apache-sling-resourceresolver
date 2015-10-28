/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.resourceresolver.impl.providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.resource.runtime.dto.FailureReason;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderDTO;
import org.apache.sling.api.resource.runtime.dto.ResourceProviderFailureDTO;
import org.apache.sling.api.resource.runtime.dto.RuntimeDTO;
import org.apache.sling.resourceresolver.impl.legacy.LegacyResourceProviderWhiteboard;
import org.apache.sling.spi.resource.provider.ResourceProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This service keeps track of all resource providers.
 */
@Component
@Service(value = ResourceProviderTracker.class)
public class ResourceProviderTracker {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final Map<ServiceReference, ResourceProviderInfo> infos = new ConcurrentHashMap<ServiceReference, ResourceProviderInfo>();

    private volatile BundleContext bundleContext;

    private volatile ServiceTracker tracker;

    private final Map<String, List<ResourceProviderHandler>> handlers = new HashMap<String, List<ResourceProviderHandler>>();

    private final Map<ResourceProviderInfo, FailureReason> invalidProviders = new ConcurrentHashMap<ResourceProviderInfo, FailureReason>();

    @Reference
    private EventAdmin eventAdmin;

    private volatile ResourceProviderStorage storage;

    @Activate
    protected void activate(final BundleContext bundleContext) {
        this.bundleContext = bundleContext;
        this.tracker = new ServiceTracker(bundleContext,
                ResourceProvider.class.getName(),
                new ServiceTrackerCustomizer() {

            @Override
            public void removedService(final ServiceReference reference, final Object service) {
                final ServiceReference ref = (ServiceReference)service;
                final ResourceProviderInfo info = infos.remove(ref);
                if ( info != null ) {
                    unregister(info);
                }
            }

            @Override
            public void modifiedService(final ServiceReference reference, final Object service) {
                removedService(reference, service);
                addingService(reference);
            }

            @Override
            public Object addingService(final ServiceReference reference) {
                final ResourceProviderInfo info = new ResourceProviderInfo(reference);
                infos.put(reference, info);
                register(info);
                return reference;
            }
        });
        this.tracker.open();
    }

    @Deactivate
    protected void deactivate() {
        if ( this.tracker != null ) {
            this.tracker.close();
            this.tracker = null;
        }
        this.infos.clear();
        this.handlers.clear();
        this.invalidProviders.clear();
    }

    /**
     * Try to register a new resource provider.
     * @param info The resource provider info.
     */
    private void register(final ResourceProviderInfo info) {
        if ( info.isValid() ) {
           logger.debug("Registering new resource provider {}", info);
           synchronized ( this.handlers ) {
               List<ResourceProviderHandler> matchingHandlers = this.handlers.get(info.getPath());
               if ( matchingHandlers == null ) {
                   matchingHandlers = new ArrayList<ResourceProviderHandler>();
                   this.handlers.put(info.getPath(), matchingHandlers);
               }
               final ResourceProviderHandler handler = new ResourceProviderHandler(bundleContext, info);
               matchingHandlers.add(handler);
               Collections.sort(matchingHandlers);
               if ( matchingHandlers.get(0) == handler ) {
                   if ( !this.activate(handler) ) {
                       matchingHandlers.remove(handler);
                       if ( matchingHandlers.isEmpty() ) {
                           this.handlers.remove(info.getPath());
                       }
                   } else {
                       if ( matchingHandlers.size() > 1 ) {
                           this.deactivate(matchingHandlers.get(1));
                       }
                   }
               }
           }
        } else {
            logger.warn("Ignoring invalid resource provider {}", info);
            this.invalidProviders.put(info, FailureReason.invalid);
        }
    }

    /**
     * Unregister a resource provider.
     * @param info The resource provider info.
     */
    private void unregister(final ResourceProviderInfo info) {
        final boolean isInvalid;
        synchronized ( this.invalidProviders ) {
            isInvalid = this.invalidProviders.remove(info) != null;
        }

        if ( !isInvalid ) {
            logger.debug("Unregistering resource provider {}", info);
            synchronized (this.handlers) {
                final List<ResourceProviderHandler> matchingHandlers = this.handlers.get(info.getPath());
                if ( matchingHandlers != null ) {
                    boolean doActivateNext = false;
                    if ( matchingHandlers.get(0).getInfo() == info ) {
                        doActivateNext = true;
                        this.deactivate(matchingHandlers.get(0));
                    }
                    if (removeHandlerByInfo(info, matchingHandlers)) {
                        while (doActivateNext && !matchingHandlers.isEmpty()) {
                            if (this.activate(matchingHandlers.get(0))) {
                                doActivateNext = false;
                            } else {
                                matchingHandlers.remove(0);
                            }
                        }
                    }
                    if (matchingHandlers.isEmpty()) {
                        this.handlers.remove(info.getPath());
                    }
                }
            }
            storage = null;
        } else {
            logger.debug("Unregistering invalid resource provider {}", info);
        }
    }

    /**
     * Search the info in the list of handlers.
     * @param info The provider info
     * @param infos The list of handlers
     * @return {@code true} if the info got removed.
     */
    private boolean removeHandlerByInfo(final ResourceProviderInfo info, final List<ResourceProviderHandler> infos) {
        Iterator<ResourceProviderHandler> it = infos.iterator();
        boolean removed = false;
        while (it.hasNext()) {
            if (it.next().getInfo() == info) {
                it.remove();
                removed = true;
                break;
            }
        }
        return removed;
    }

    /**
     * Deactivate a resource provider
     * @param handler The provider handler
     */
    private void deactivate(final ResourceProviderHandler handler) {
        handler.deactivate();
        postEvent(SlingConstants.TOPIC_RESOURCE_PROVIDER_REMOVED, handler.getInfo());
        logger.debug("Deactivated resource provider {}", handler.getInfo());
    }

    private void postEvent(final String topic, final ResourceProviderInfo info) {
        final Dictionary<String, Object> eventProps = new Hashtable<String, Object>();
        eventProps.put(SlingConstants.PROPERTY_PATH, info.getPath());
        String pid = (String) info.getServiceReference().getProperty(Constants.SERVICE_PID);
        if (pid == null) {
            pid = (String) info.getServiceReference().getProperty(LegacyResourceProviderWhiteboard.ORIGINAL_SERVICE_PID);
        }
        if (pid != null) {
            eventProps.put(Constants.SERVICE_PID, pid);
        }
        eventAdmin.postEvent(new Event(topic, eventProps));
    }

    /**
     * Activate a resource provider
     * @param handler The provider handler
     */
    private boolean activate(final ResourceProviderHandler handler) {
        if ( !handler.activate() ) {
            logger.warn("Activating resource provider {} failed", handler.getInfo());
            this.invalidProviders.put(handler.getInfo(), FailureReason.service_not_gettable);

            return false;
        }
        postEvent(SlingConstants.TOPIC_RESOURCE_PROVIDER_ADDED, handler.getInfo());
        logger.debug("Activated resource provider {}", handler.getInfo());
        return true;
    }

    public void fill(final RuntimeDTO dto) {
        final List<ResourceProviderDTO> dtos = new ArrayList<ResourceProviderDTO>();
        final List<ResourceProviderFailureDTO> failures = new ArrayList<ResourceProviderFailureDTO>();

        synchronized ( this.handlers ) {
            for(final List<ResourceProviderHandler> handlers : this.handlers.values()) {
                boolean isFirst = true;
                for(final ResourceProviderHandler h : handlers) {
                    final ResourceProviderDTO d;
                    if ( isFirst ) {
                        d = new ResourceProviderDTO();
                        dtos.add(d);
                        isFirst = false;
                    } else {
                        d = new ResourceProviderFailureDTO();
                        ((ResourceProviderFailureDTO)d).reason = FailureReason.shadowed;
                        failures.add((ResourceProviderFailureDTO)d);
                    }
                    fill(d, h.getInfo());
                }
            }
        }
        synchronized ( this.invalidProviders ) {
            for(final Map.Entry<ResourceProviderInfo, FailureReason> entry : this.invalidProviders.entrySet()) {
                final ResourceProviderFailureDTO d = new ResourceProviderFailureDTO();
                fill(d, entry.getKey());
                d.reason = entry.getValue();
            }
        }
        dto.providers = dtos.toArray(new ResourceProviderDTO[dtos.size()]);
        dto.failedProviders = failures.toArray(new ResourceProviderFailureDTO[failures.size()]);
    }

    public ResourceProviderStorage getResourceProviderStorage() {
        ResourceProviderStorage result = storage;
        if (result == null) {
            synchronized (this.handlers) {
                if (storage == null) {
                    final List<ResourceProviderHandler> handlerList = new ArrayList<ResourceProviderHandler>();
                    for (List<ResourceProviderHandler> list : handlers.values()) {
                        ResourceProviderHandler h  = list.get(0);
                        if (h != null) {
                            handlerList.add(h);
                        }
                    }
                    storage = new ResourceProviderStorage(handlerList);
                }
                result = storage;
            }
        }
        return result;
    }

    private void fill(final ResourceProviderDTO d, final ResourceProviderInfo info) {
        d.authType = info.getAuthType();
        d.modifiable = info.getModifiable();
        d.name = info.getName();
        d.path = info.getPath();
        d.serviceId = (Long)info.getServiceReference().getProperty(Constants.SERVICE_ID);
        d.useResourceAccessSecurity = info.getUseResourceAccessSecurity();
    }
}
