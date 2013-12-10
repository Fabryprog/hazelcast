/*
 * Copyright (c) 2008-2013, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.client.proxy;

import com.hazelcast.client.spi.ClientProxy;
import com.hazelcast.client.spi.EventHandler;
import com.hazelcast.client.spi.ListenerSupport;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.EntryListener;
import com.hazelcast.core.Member;
import com.hazelcast.core.ReplicatedMap;
import com.hazelcast.map.MapEntrySet;
import com.hazelcast.query.Predicate;
import com.hazelcast.replicatedmap.client.*;
import com.hazelcast.spi.impl.PortableEntryEvent;
import com.hazelcast.util.ExceptionUtil;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class ClientReplicatedMapProxy<K, V> extends ClientProxy implements ReplicatedMap<K, V> {

    public ClientReplicatedMapProxy(String serviceName, String objectName) {
        super(serviceName, objectName);
    }

    @Override
    protected void onDestroy() {
        // TODO destroy nearcache
    }

    @Override
    public V put(K key, V value, long ttl, TimeUnit timeUnit) {
        return invoke(new ClientReplicatedMapPutTtlRequest(getName(), key, value, timeUnit.toMillis(ttl)));
    }

    @Override
    public int size() {
        return invoke(new ClientReplicatedMapSizeRequest(getName()));
    }

    @Override
    public boolean isEmpty() {
        return invoke(new ClientReplicatedMapIsEmptyRequest(getName()));
    }

    @Override
    public boolean containsKey(Object key) {
        return invoke(new ClientReplicatedMapContainsKeyRequest(getName(), key));
    }

    @Override
    public boolean containsValue(Object value) {
        return invoke(new ClientReplicatedMapContainsValueRequest(getName(), value));
    }

    @Override
    public V get(Object key) {
        GetResponse response = invoke(new ClientReplicatedMapGetRequest(getName(), key));
        // TODO add near caching
        return (V) response.getValue();
    }

    @Override
    public V put(K key, V value) {
        return put(key, value, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public V remove(Object key) {
        return invoke(new ClientReplicatedMapRemoveRequest(getName(), key));
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        invoke(new ClientReplicatedMapPutAllRequest(getName(), new ReplicatedMapEntrySet(m.entrySet())));
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("clear is not supported on ReplicatedMap");
    }

    @Override
    public boolean removeEntryListener(String id) {
        return stopListening(id);
    }

    @Override
    public String addEntryListener(EntryListener<K, V> listener) {
        ClientReplicatedMapAddEntryListenerRequest request =
                new ClientReplicatedMapAddEntryListenerRequest(getName(), null, null);
        EventHandler<ReplicatedMapPortableEntryEvent> handler = createHandler(listener);
        return listen(request, null, handler);
    }

    @Override
    public String addEntryListener(EntryListener<K, V> listener, K key) {
        ClientReplicatedMapAddEntryListenerRequest request =
                new ClientReplicatedMapAddEntryListenerRequest(getName(), null, key);
        EventHandler<ReplicatedMapPortableEntryEvent> handler = createHandler(listener);
        return listen(request, null, handler);
    }

    @Override
    public String addEntryListener(EntryListener<K, V> listener, Predicate<K, V> predicate) {
        ClientReplicatedMapAddEntryListenerRequest request =
                new ClientReplicatedMapAddEntryListenerRequest(getName(), predicate, null);
        EventHandler<ReplicatedMapPortableEntryEvent> handler = createHandler(listener);
        return listen(request, null, handler);
    }

    @Override
    public String addEntryListener(EntryListener<K, V> listener, Predicate<K, V> predicate, K key) {
        ClientReplicatedMapAddEntryListenerRequest request =
                new ClientReplicatedMapAddEntryListenerRequest(getName(), predicate, key);
        EventHandler<ReplicatedMapPortableEntryEvent> handler = createHandler(listener);
        return listen(request, null, handler);
    }

    @Override
    public Set<K> keySet() {
        return invoke(new ClientReplicatedMapKeySetRequest(getName()));
    }

    @Override
    public Collection<V> values() {
        return invoke(new ClientReplicatedMapValuesRequest(getName()));
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return invoke(new ClientReplicatedMapEntrySetRequest(getName()));
    }

    private <T> T invoke(Object request) {
        try {
            return getContext().getInvocationService().invokeOnRandomTarget(request);
        } catch (Exception e) {
            throw ExceptionUtil.rethrow(e);
        }
    }

    private EventHandler<ReplicatedMapPortableEntryEvent> createHandler(final EntryListener<K, V> listener) {
        return new EventHandler<ReplicatedMapPortableEntryEvent>() {
            public void handle(ReplicatedMapPortableEntryEvent event) {
                V value = (V) event.getValue();
                V oldValue = (V) event.getOldValue();
                K key = (K) event.getKey();
                Member member = getContext().getClusterService().getMember(event.getUuid());
                EntryEvent<K, V> entryEvent = new EntryEvent<K, V>(getName(), member,
                        event.getEventType().getType(), key, oldValue, value);
                switch (event.getEventType()) {
                    case ADDED:
                        listener.entryAdded(entryEvent);
                        break;
                    case REMOVED:
                        listener.entryRemoved(entryEvent);
                        break;
                    case UPDATED:
                        listener.entryUpdated(entryEvent);
                        break;
                    case EVICTED:
                        listener.entryEvicted(entryEvent);
                        break;
                }
            }
        };
    }

}
