/*
 * Copyright (c) 2008, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.collection.impl.collection;

import com.hazelcast.config.CollectionConfig;
import com.hazelcast.logging.ILogger;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.transaction.TransactionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@SuppressWarnings("checkstyle:methodcount")
public abstract class CollectionContainer implements IdentifiedDataSerializable {

    protected final Map<Long, TxCollectionItem> txMap = new HashMap<Long, TxCollectionItem>();
    protected String name;
    protected NodeEngine nodeEngine;
    protected ILogger logger;
    protected Map<Long, CollectionItem> itemMap;
    private long idGenerator;

    protected CollectionContainer() {
    }

    protected CollectionContainer(String name, NodeEngine nodeEngine) {
        this.name = name;
        this.nodeEngine = nodeEngine;
        this.logger = nodeEngine.getLogger(getClass());
    }

    public void init(NodeEngine nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.logger = nodeEngine.getLogger(getClass());
    }

    public abstract CollectionConfig getConfig();

    protected abstract Collection<CollectionItem> getCollection();

    protected abstract Map<Long, CollectionItem> getMap();

    public long add(Data value) {
        final CollectionItem item = new CollectionItem(nextId(), value);
        if (getCollection().add(item)) {
            return item.getItemId();
        }
        return -1;
    }

    public void addBackup(long itemId, Data value) {
        final CollectionItem item = new CollectionItem(itemId, value);
        getMap().put(itemId, item);
    }

    public CollectionItem remove(Data value) {
        final Iterator<CollectionItem> iterator = getCollection().iterator();
        while (iterator.hasNext()) {
            final CollectionItem item = iterator.next();
            if (value.equals(item.getValue())) {
                iterator.remove();
                return item;
            }
        }
        return null;
    }

    public void removeBackup(long itemId) {
        getMap().remove(itemId);
    }

    public int size() {
        return getCollection().size();
    }

    public Map<Long, Data> clear() {
        final Collection<CollectionItem> coll = getCollection();
        Map<Long, Data> itemIdMap = new HashMap<Long, Data>(coll.size());
        for (CollectionItem item : coll) {
            itemIdMap.put(item.getItemId(), (Data) item.getValue());
        }
        coll.clear();
        return itemIdMap;
    }

    public void clearBackup(Set<Long> itemIdSet) {
        for (Long itemId : itemIdSet) {
            removeBackup(itemId);
        }
    }

    public boolean contains(Set<Data> valueSet) {
        Collection<CollectionItem> collection = getCollection();
        CollectionItem collectionItem = new CollectionItem(-1, null);
        for (Data value : valueSet) {
            collectionItem.setValue(value);
            if (!collection.contains(collectionItem)) {
                return false;
            }
        }
        return true;
    }

    public Map<Long, Data> addAll(List<Data> valueList) {
        final int size = valueList.size();
        final Map<Long, Data> map = new HashMap<Long, Data>(size);
        List<CollectionItem> list = new ArrayList<CollectionItem>(size);
        for (Data value : valueList) {
            final long itemId = nextId();
            list.add(new CollectionItem(itemId, value));
            map.put(itemId, value);
        }
        getCollection().addAll(list);

        return map;
    }

    public void addAllBackup(Map<Long, Data> valueMap) {
        Map<Long, CollectionItem> map = new HashMap<Long, CollectionItem>(valueMap.size());
        for (Map.Entry<Long, Data> entry : valueMap.entrySet()) {
            final long itemId = entry.getKey();
            map.put(itemId, new CollectionItem(itemId, entry.getValue()));
        }
        getMap().putAll(map);
    }

    public Map<Long, Data> compareAndRemove(boolean retain, Set<Data> valueSet) {
        Map<Long, Data> itemIdMap = new HashMap<Long, Data>();
        final Iterator<CollectionItem> iterator = getCollection().iterator();
        while (iterator.hasNext()) {
            final CollectionItem item = iterator.next();
            final boolean contains = valueSet.contains(item.getValue());
            if ((contains && !retain) || (!contains && retain)) {
                itemIdMap.put(item.getItemId(), (Data) item.getValue());
                iterator.remove();
            }
        }
        return itemIdMap;
    }

    public List<Data> getAll() {
        ArrayList<Data> sub = new ArrayList<Data>(getCollection().size());
        for (CollectionItem item : getCollection()) {
            sub.add((Data) item.getValue());
        }
        return sub;
    }

    public boolean hasEnoughCapacity(int delta) {
        return getCollection().size() + delta <= getConfig().getMaxSize();
    }


    /*
     * TX methods
     *
     */

    public Long reserveAdd(String transactionId, Data value) {
        if (value != null && getCollection().contains(new CollectionItem(-1, value))) {
            return null;
        }
        final long itemId = nextId();
        txMap.put(itemId, new TxCollectionItem(itemId, null, transactionId, false));
        return itemId;
    }

    public void reserveAddBackup(long itemId, String transactionId) {
        TxCollectionItem item = new TxCollectionItem(itemId, null, transactionId, false);
        Object o = txMap.put(itemId, item);
        if (o != null) {
            logger.severe("Transaction reservation item already exists on the backup member."
                    + " Reservation item id : " + itemId);
        }
    }

    public CollectionItem reserveRemove(long reservedItemId, Data value, String transactionId) {
        final Iterator<CollectionItem> iterator = getCollection().iterator();
        while (iterator.hasNext()) {
            final CollectionItem item = iterator.next();
            if (value.equals(item.getValue())) {
                iterator.remove();
                txMap.put(item.getItemId(), new TxCollectionItem(item)
                        .setTransactionId(transactionId)
                        .setRemoveOperation(true));
                return item;
            }
        }
        if (reservedItemId != -1) {
            return txMap.remove(reservedItemId);
        }
        return null;
    }

    public void reserveRemoveBackup(long itemId, String transactionId) {
        final CollectionItem item = getMap().remove(itemId);
        if (item == null) {
            throw new TransactionException("Transaction reservation failed on backup member. "
                    + "Reservation item id: " + itemId);
        }
        txMap.put(itemId, new TxCollectionItem(item).setTransactionId(transactionId).setRemoveOperation(true));
    }

    public void ensureReserve(long itemId) {
        if (txMap.get(itemId) == null) {
            throw new TransactionException("Transaction reservation cannot be found for reservation item id: "
                    + itemId);
        }
    }

    public void rollbackAdd(long itemId) {
        if (txMap.remove(itemId) == null) {
            logger.warning("Transaction log cannot be found for rolling back 'add()' operation. Missing log item id: "
                    + itemId);
        }
    }

    public void rollbackAddBackup(long itemId) {
        if (txMap.remove(itemId) == null) {
            logger.warning("Transaction log cannot be found for rolling back 'add()' operation on backup member."
                    + " Missing log item id: " + itemId);
        }
    }

    public void rollbackRemove(long itemId) {
        final TxCollectionItem txItem = txMap.remove(itemId);
        if (txItem == null) {
            logger.warning("Transaction log cannot be found for rolling back 'remove()' operation."
                    + " Missing log item id: " + itemId);
        } else {
            CollectionItem item = new CollectionItem(itemId, txItem.value);
            getCollection().add(item);
        }
    }

    public void rollbackRemoveBackup(long itemId) {
        final TxCollectionItem item = txMap.remove(itemId);
        if (item == null) {
            logger.warning("Transaction log cannot be found for rolling back 'remove()' operation on backup member."
                    + " Missing log item id: " + itemId);
        }
    }

    public void commitAdd(long itemId, Data value) {
        final TxCollectionItem txItem = txMap.remove(itemId);
        if (txItem == null) {
            throw new TransactionException("Transaction log cannot be found for committing 'add()' operation."
                    + " Missing log item id :" + itemId);
        }
        CollectionItem item = new CollectionItem(itemId, value);
        getCollection().add(item);
    }

    public void commitAddBackup(long itemId, Data value) {
        txMap.remove(itemId);
        CollectionItem item = new CollectionItem(itemId, value);
        getMap().put(itemId, item);
    }

    public CollectionItem commitRemove(long itemId) {
        final CollectionItem item = txMap.remove(itemId);
        if (item == null) {
            logger.warning("Transaction log cannot be found for committing 'remove()' operation."
                    + " Missing log item id " + itemId);
        }
        return item;
    }

    public void commitRemoveBackup(long itemId) {
        if (txMap.remove(itemId) == null) {
            logger.warning("Transaction log cannot be found for committing 'remove()' operation on backup member."
                    + " Missing log item id:" + itemId);
        }
    }

    public void rollbackTransaction(String transactionId) {
        final Iterator<TxCollectionItem> iterator = txMap.values().iterator();

        while (iterator.hasNext()) {
            final TxCollectionItem txItem = iterator.next();
            if (transactionId.equals(txItem.getTransactionId())) {
                iterator.remove();
                if (txItem.isRemoveOperation()) {
                    CollectionItem item = new CollectionItem(txItem.itemId, txItem.value);
                    getCollection().add(item);
                }
            }
        }
    }

    public long nextId() {
        return ++idGenerator;
    }

    void setId(long itemId) {
        idGenerator = Math.max(itemId + 1, idGenerator);
    }

    public void destroy() {
        onDestroy();
        if (itemMap != null) {
            itemMap.clear();
        }
        txMap.clear();
    }

    protected abstract void onDestroy();

    @Override
    public void writeData(ObjectDataOutput out) throws IOException {
        out.writeUTF(name);
        final Collection<CollectionItem> collection = getCollection();
        out.writeInt(collection.size());
        for (CollectionItem item : collection) {
            item.writeData(out);
        }
        out.writeInt(txMap.size());
        for (TxCollectionItem txCollectionItem : txMap.values()) {
            txCollectionItem.writeData(out);
        }
    }

    @Override
    public void readData(ObjectDataInput in) throws IOException {
        name = in.readUTF();
        final int collectionSize = in.readInt();
        final Collection<CollectionItem> collection = getCollection();
        for (int i = 0; i < collectionSize; i++) {
            final CollectionItem item = new CollectionItem();
            item.readData(in);
            collection.add(item);
            setId(item.getItemId());
        }

        final int txMapSize = in.readInt();
        for (int i = 0; i < txMapSize; i++) {
            final TxCollectionItem txCollectionItem = new TxCollectionItem();
            txCollectionItem.readData(in);
            txMap.put(txCollectionItem.getItemId(), txCollectionItem);
            setId(txCollectionItem.itemId);
        }
    }

    @Override
    public int getFactoryId() {
        return CollectionDataSerializerHook.F_ID;
    }
}
