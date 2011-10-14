/**
 * Copyright (c) 2002-2011 "Neo Technology,"
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
package org.neo4j.kernel.impl.storemigration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.neo4j.graphdb.DynamicRelationshipType.withName;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.junit.Test;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.PropertyContainer;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.impl.nioneo.store.NeoStore;
import org.neo4j.kernel.impl.nioneo.store.PropertyType;
import org.neo4j.kernel.impl.storemigration.legacystore.LegacyReaderFactory;
import org.neo4j.kernel.impl.storemigration.legacystore.LegacyStore;
import org.neo4j.kernel.impl.storemigration.legacystore.StubbedReaderFactory;
import org.neo4j.kernel.impl.storemigration.monitoring.MigrationProgressMonitor;
import org.neo4j.kernel.impl.util.FileUtils;

public class StoreMigratorSpeedBenchmark
{
    @SuppressWarnings({"unchecked"})
    @Test
    public void shouldMigrate() throws IOException
    {
        String storageFileName = "/Users/apcj/projects/neo4j/legacy-store-creator/target/output-database/neostore";

        final LegacyStore referenceLegacyStore = new LegacyStore( storageFileName, new LegacyReaderFactory() );

        long reference = time( new Runnable()
        {
            public void run()
            {
                migrate( referenceLegacyStore );
            }
        } );

        final LegacyStore trialLegacyStore = new LegacyStore( storageFileName, new StubbedReaderFactory() );

        long trial = time( new Runnable()
        {
            public void run()
            {
                migrate( trialLegacyStore );
            }
        } );

        System.out.printf( "reference = %ds%n", reference / 1000 );
        System.out.printf( "trial = %ds%n", trial / 1000 );
        System.out.println( String.format( "Trial is %f%% better than reference", (reference - trial) / (double) reference * 100 ) );
    }

    private long time( Runnable runnable )
    {
        long startTime = System.currentTimeMillis();
        runnable.run();
        return System.currentTimeMillis() - startTime;
    }

    private void migrate( LegacyStore legacyStore )
    {
        try
        {
            HashMap config = MigrationTestUtils.defaultConfig();
            File outputDir = new File( "target/outputDatabase" );
            FileUtils.deleteRecursively( outputDir );
            assertTrue( outputDir.mkdirs() );

            String storeFileName = "target/outputDatabase/neostore";
            config.put( "neo_store", storeFileName );
            NeoStore.createStore( storeFileName, config );
            NeoStore neoStore = new NeoStore( config );

            ListAccumulatorMigrationProgressMonitor monitor = new ListAccumulatorMigrationProgressMonitor();

            new StoreMigrator( monitor ).migrate( legacyStore, neoStore );
            neoStore.close();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    private static class DatabaseContentVerifier
    {
        private String longString = MigrationTestUtils.makeLongString();
        private int[] longArray = MigrationTestUtils.makeLongArray();
        private GraphDatabaseService database;

        public DatabaseContentVerifier( GraphDatabaseService database )
        {
            this.database = database;
        }

        private void verifyRelationships()
        {
            Node currentNode = database.getReferenceNode();
            int traversalCount = 0;
            while ( currentNode.hasRelationship( Direction.OUTGOING ) )
            {
                traversalCount++;
                Relationship relationship = currentNode.getRelationships( Direction.OUTGOING ).iterator().next();
                verifyProperties( relationship );
                currentNode = relationship.getEndNode();
            }
            assertEquals( 500, traversalCount );
        }

        private void verifyNodes()
        {
            int nodeCount = 0;
            for ( Node node : database.getAllNodes() )
            {
                nodeCount++;
                if ( node.getId() > 0 )
                {
                    verifyProperties( node );
                }
            }
            assertEquals( 501, nodeCount );
        }

        private void verifyProperties( PropertyContainer node )
        {
            assertEquals( Integer.MAX_VALUE, node.getProperty( PropertyType.INT.name() ) );
            assertEquals( longString, node.getProperty( PropertyType.STRING.name() ) );
            assertEquals( true, node.getProperty( PropertyType.BOOL.name() ) );
            assertEquals( Double.MAX_VALUE, node.getProperty( PropertyType.DOUBLE.name() ) );
            assertEquals( Float.MAX_VALUE, node.getProperty( PropertyType.FLOAT.name() ) );
            assertEquals( Long.MAX_VALUE, node.getProperty( PropertyType.LONG.name() ) );
            assertEquals( Byte.MAX_VALUE, node.getProperty( PropertyType.BYTE.name() ) );
            assertEquals( Character.MAX_VALUE, node.getProperty( PropertyType.CHAR.name() ) );
            assertArrayEquals( longArray, (int[]) node.getProperty( PropertyType.ARRAY.name() ) );
            assertEquals( Short.MAX_VALUE, node.getProperty( PropertyType.SHORT.name() ) );
            assertEquals( "short", node.getProperty( PropertyType.SHORT_STRING.name() ) );
        }

        private void verifyNodeIdsReused()
        {
            try
            {
                database.getNodeById( 1 );
                fail( "Node 2 should not exist" );
            }
            catch ( NotFoundException e )
            {
                //expected
            }
            Transaction transaction = database.beginTx();
            try
            {
                Node newNode = database.createNode();
                assertEquals( 1, newNode.getId() );
                transaction.success();
            }
            finally
            {
                transaction.finish();
            }
        }

        private void verifyRelationshipIdsReused()
        {
            Transaction transaction = database.beginTx();
            try
            {
                Node node1 = database.createNode();
                Node node2 = database.createNode();
                Relationship relationship1 = node1.createRelationshipTo( node2, withName( "REUSE" ) );
                assertEquals( 0, relationship1.getId() );
                transaction.success();
            }
            finally
            {
                transaction.finish();
            }
        }
    }

    private class ListAccumulatorMigrationProgressMonitor implements MigrationProgressMonitor
    {
        private List<Integer> events = new ArrayList<Integer>();
        private boolean started = false;
        private boolean finished = false;

        public void started()
        {
            started = true;
        }

        public void percentComplete( int percent )
        {
            events.add( percent );
        }

        public void finished()
        {
            finished = true;
        }
    }
}
