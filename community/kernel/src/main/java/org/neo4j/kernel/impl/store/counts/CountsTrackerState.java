/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.store.counts;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

import org.neo4j.kernel.impl.store.counts.CountsKey.IndexCountsKey;
import org.neo4j.kernel.impl.store.counts.CountsKey.IndexSampleKey;
import org.neo4j.kernel.impl.store.counts.CountsKey.NodeKey;
import org.neo4j.kernel.impl.store.counts.CountsKey.RelationshipKey;
import org.neo4j.kernel.impl.store.kvstore.KeyValueRecordVisitor;
import org.neo4j.kernel.impl.store.kvstore.SortedKeyValueStore;
import org.neo4j.register.Register.CopyableDoubleLongRegister;
import org.neo4j.register.Register.DoubleLongRegister;

interface CountsTrackerState extends Closeable
{
    long lastTxId();

    boolean hasChanges();

    long nodeCount( NodeKey nodeKey );

    long relationshipCount( RelationshipKey relationshipKey );

    void indexUpdatesAndSize( IndexCountsKey indexCountsKey, DoubleLongRegister target );

    void indexSample( IndexSampleKey indexSampleKey, DoubleLongRegister target );

    void incrementNodeCount( NodeKey nodeKey, long delta );

    void incrementRelationshipCount( RelationshipKey relationshipKey, long delta );

    void replaceIndexUpdatesAndSize( IndexCountsKey indexCountsKey, long updates, long size );

    void incrementIndexUpdates( IndexCountsKey indexCountsKey, long delta );

    void replaceIndexSample( IndexSampleKey indexSampleKey, long unique, long size );

    File storeFile();

    SortedKeyValueStore.Writer<CountsKey, CopyableDoubleLongRegister> newWriter( File file, long lastCommittedTxId )
            throws IOException;

    void accept( KeyValueRecordVisitor<CountsKey, CopyableDoubleLongRegister> visitor );
}
