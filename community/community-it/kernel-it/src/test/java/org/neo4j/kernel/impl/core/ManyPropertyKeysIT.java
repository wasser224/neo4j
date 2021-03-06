/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.kernel.impl.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collection;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.Iterables;
import org.neo4j.internal.id.DefaultIdGeneratorFactory;
import org.neo4j.internal.kernel.api.exceptions.TransactionFailureException;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.io.pagecache.IOLimiter;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.PageCacheTracer;
import org.neo4j.kernel.api.Kernel;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.security.AnonymousContext;
import org.neo4j.kernel.impl.store.NeoStores;
import org.neo4j.kernel.impl.store.PropertyKeyTokenStore;
import org.neo4j.kernel.impl.store.PropertyStore;
import org.neo4j.kernel.impl.store.StoreFactory;
import org.neo4j.kernel.impl.store.record.DynamicRecord;
import org.neo4j.kernel.impl.store.record.PropertyKeyTokenRecord;
import org.neo4j.kernel.internal.GraphDatabaseAPI;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.test.OtherThreadExecutor;
import org.neo4j.test.OtherThreadExecutor.WorkerCommand;
import org.neo4j.test.TestDatabaseManagementServiceBuilder;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.Neo4jLayoutExtension;
import org.neo4j.test.extension.pagecache.PageCacheExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.index.internal.gbptree.RecoveryCleanupWorkCollector.immediate;
import static org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer.NULL;

/**
 * Tests for handling many property keys (even after restart of database)
 * as well as concurrent creation of property keys.
 */
@PageCacheExtension
@Neo4jLayoutExtension
class ManyPropertyKeysIT
{
    @Inject
    private FileSystemAbstraction fileSystem;
    @Inject
    private DatabaseLayout databaseLayout;
    @Inject
    private PageCache pageCache;
    private DatabaseManagementService managementService;

    @Test
    void creating_many_property_keys_should_have_all_loaded_the_next_restart() throws Exception
    {
        // GIVEN
        // The previous limit to load was 2500, so go some above that
        GraphDatabaseAPI db = databaseWithManyPropertyKeys( 3000 );
        int countBefore = propertyKeyCount( db );

        // WHEN
        managementService.shutdown();
        db = database();
        createNodeWithProperty( db, key( 2800 ), true );

        // THEN
        assertEquals( countBefore, propertyKeyCount( db ) );
        managementService.shutdown();
    }

    @Test
    void concurrently_creating_same_property_key_in_different_transactions_should_end_up_with_same_key_id() throws Exception
    {
        // GIVEN
        DatabaseManagementService managementService = new TestDatabaseManagementServiceBuilder().impermanent().build();
        GraphDatabaseAPI db = (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
        OtherThreadExecutor<WorkerState> worker1 = new OtherThreadExecutor<>( "w1", new WorkerState( db ) );
        OtherThreadExecutor<WorkerState> worker2 = new OtherThreadExecutor<>( "w2", new WorkerState( db ) );
        worker1.execute( new BeginTx() );
        worker2.execute( new BeginTx() );

        // WHEN
        String key = "mykey";
        worker1.execute( new CreateNodeAndSetProperty( key ) );
        worker2.execute( new CreateNodeAndSetProperty( key ) );
        worker1.execute( new FinishTx() );
        worker2.execute( new FinishTx() );
        worker1.close();
        worker2.close();

        // THEN
        assertEquals( 1, propertyKeyCount( db ) );
        managementService.shutdown();
    }

    private GraphDatabaseAPI database()
    {
        managementService = new TestDatabaseManagementServiceBuilder( databaseLayout )
                .setConfig( GraphDatabaseSettings.fail_on_missing_files, false )
                .build();
        return (GraphDatabaseAPI) managementService.database( DEFAULT_DATABASE_NAME );
    }

    private GraphDatabaseAPI databaseWithManyPropertyKeys( int propertyKeyCount ) throws IOException
    {
        var cacheTracer = PageCacheTracer.NULL;
        var cursorTracer = cacheTracer.createPageCursorTracer( "databaseWithManyPropertyKeys" );
        StoreFactory storeFactory = new StoreFactory( databaseLayout, Config.defaults(),
                new DefaultIdGeneratorFactory( fileSystem, immediate() ), pageCache, fileSystem, NullLogProvider.getInstance(), cacheTracer );
        NeoStores neoStores = storeFactory.openAllNeoStores( true );
        PropertyKeyTokenStore store = neoStores.getPropertyKeyTokenStore();
        for ( int i = 0; i < propertyKeyCount; i++ )
        {
            PropertyKeyTokenRecord record = new PropertyKeyTokenRecord( (int) store.nextId( cursorTracer ) );
            record.setInUse( true );
            Collection<DynamicRecord> nameRecords = store.allocateNameRecords( PropertyStore.encodeString( key( i ) ), cursorTracer );
            record.addNameRecords( nameRecords );
            record.setNameId( (int) Iterables.first( nameRecords ).getId() );
            store.updateRecord( record, NULL );
        }
        neoStores.flush( IOLimiter.UNLIMITED, cursorTracer );
        neoStores.close();

        return database();
    }

    private static String key( int i )
    {
        return "key" + i;
    }

    private static void createNodeWithProperty( GraphDatabaseService db, String key, Object value )
    {
        try ( Transaction tx = db.beginTx() )
        {
            Node node = tx.createNode();
            node.setProperty( key, value );
            tx.commit();
        }
    }

    private static int propertyKeyCount( GraphDatabaseAPI db ) throws TransactionFailureException
    {
        Kernel kernelAPI = db.getDependencyResolver().resolveDependency( Kernel.class );
        try ( KernelTransaction tx = kernelAPI.beginTransaction( KernelTransaction.Type.IMPLICIT, AnonymousContext.read() ) )
        {
            return tx.tokenRead().propertyKeyCount();
        }
    }

    private static class WorkerState
    {
        protected final GraphDatabaseService db;
        protected Transaction tx;

        WorkerState( GraphDatabaseService db )
        {
            this.db = db;
        }
    }

    private static class BeginTx implements WorkerCommand<WorkerState, Void>
    {
        @Override
        public Void doWork( WorkerState state )
        {
            state.tx = state.db.beginTx();
            return null;
        }
    }

    private static class CreateNodeAndSetProperty implements WorkerCommand<WorkerState, Void>
    {
        private final String key;

        CreateNodeAndSetProperty( String key )
        {
            this.key = key;
        }

        @Override
        public Void doWork( WorkerState state )
        {
            Node node = state.tx.createNode();
            node.setProperty( key, true );
            return null;
        }
    }

    private static class FinishTx implements WorkerCommand<WorkerState, Void>
    {
        @Override
        public Void doWork( WorkerState state )
        {
            state.tx.commit();
            return null;
        }
    }
}
