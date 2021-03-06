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

package com.hazelcast.map.impl.operation;

import com.hazelcast.map.impl.MapDataSerializerHook;
import com.hazelcast.map.impl.MapService;
import com.hazelcast.map.impl.MapServiceContext;
import com.hazelcast.map.impl.PartitionContainer;
import com.hazelcast.map.impl.record.Record;
import com.hazelcast.map.impl.record.RecordInfo;
import com.hazelcast.map.impl.record.RecordReplicationInfo;
import com.hazelcast.map.impl.recordstore.RecordStore;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.nio.serialization.IdentifiedDataSerializable;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.impl.MutatingOperation;

import java.io.IOException;

import static com.hazelcast.map.impl.record.Records.buildRecordInfo;

/**
 * Replicates all IMap-states of this partition to a replica partition.
 */
public class MapReplicationOperation extends Operation implements MutatingOperation, IdentifiedDataSerializable {

    // keep these fields `protected`, extended in another context.
    protected final MapReplicationStateHolder mapReplicationStateHolder = new MapReplicationStateHolder(this);
    protected final WriteBehindStateHolder writeBehindStateHolder = new WriteBehindStateHolder(this);
    protected final MapNearCacheStateHolder mapNearCacheStateHolder = new MapNearCacheStateHolder(this);

    public MapReplicationOperation() {
    }

    public MapReplicationOperation(PartitionContainer container, int partitionId, int replicaIndex) {
        setPartitionId(partitionId).setReplicaIndex(replicaIndex);

        mapReplicationStateHolder.prepare(container, replicaIndex);
        writeBehindStateHolder.prepare(container, replicaIndex);
        mapNearCacheStateHolder.prepare(container, replicaIndex);
    }

    @Override
    public void run() {
        mapReplicationStateHolder.applyState();
        writeBehindStateHolder.applyState();
        if (getReplicaIndex() == 0) {
            mapNearCacheStateHolder.applyState();
        }
    }

    @Override
    public String getServiceName() {
        return MapService.SERVICE_NAME;
    }

    @Override
    protected void writeInternal(final ObjectDataOutput out) throws IOException {
        mapReplicationStateHolder.writeData(out);
        writeBehindStateHolder.writeData(out);
        mapNearCacheStateHolder.writeData(out);
    }

    @Override
    protected void readInternal(final ObjectDataInput in) throws IOException {
        mapReplicationStateHolder.readData(in);
        writeBehindStateHolder.readData(in);
        mapNearCacheStateHolder.readData(in);
    }

    RecordReplicationInfo createRecordReplicationInfo(Data key, Record record, MapServiceContext mapServiceContext) {
        RecordInfo info = buildRecordInfo(record);
        return new RecordReplicationInfo(key, mapServiceContext.toData(record.getValue()), info);
    }

    RecordStore getRecordStore(String mapName) {
        final boolean skipLoadingOnRecordStoreCreate = true;
        MapService mapService = getService();
        MapServiceContext mapServiceContext = mapService.getMapServiceContext();
        return mapServiceContext.getRecordStore(getPartitionId(), mapName, skipLoadingOnRecordStoreCreate);
    }

    @Override
    public int getFactoryId() {
        return MapDataSerializerHook.F_ID;
    }

    @Override
    public int getId() {
        return MapDataSerializerHook.MAP_REPLICATION;
    }
}
