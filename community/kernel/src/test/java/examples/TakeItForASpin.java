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
package examples;

import java.io.File;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.DynamicLabel;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.ResourceIterable;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.neo4j.graphdb.index.Index;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.test.TargetDirectory;

public class TakeItForASpin
{
    public static void main( String[] args )
    {
        File dir = TargetDirectory.forTest( TakeItForASpin.class ).makeGraphDbDir();
        GraphDatabaseService db = new GraphDatabaseFactory().newEmbeddedDatabase( "E:\\GitHub\\neo4j\\community\\kernel\\target\\test-data\\examples.TakeItForASpin\\graph-db"); 
        
        try ( Transaction tx = db.beginTx() )
        {
            Node node1=db.createNode(-1);
            Node node2=db.createNode(-1);
            Relationship rel1=node1.createRelationshipTo(node2, DynamicRelationshipType.withName("knows"),-1);
            node1.setProperty("name", "huang");
            node2.setProperty("name", "haixing");
            node1.addLabel(DynamicLabel.label("label1"));
        /*   
            Index<Node> nodeIndex = db.index().forNodes( "nodeIndex" );
            nodeIndex.add( node1 , "name" , "huang" );
            nodeIndex.add( node2, "name", "haixing" );
          */  
            tx.success();
        }
      
        try(Transaction tx=db.beginTx())
        {
        	@SuppressWarnings("deprecation")
			Iterable<Node> nodes=db.getAllNodes();
        	for(Node n:nodes)
        	{
        	    Relationship relationships = n.getSingleRelationship( DynamicRelationshipType.withName( "knows" ), Direction.BOTH );
        	    System.out.print( relationships.getTimeField() );
        		System.out.print(n.getTimeField());
        		System.out.print('\n');
        	}
        }
        
        /*
        try( Transaction tx = db.beginTx() )
        {
            Index< Node > nodeIndex = db.index().forNodes( "nodeIndex" );
            IndexHits< Node > nodehit = nodeIndex.get( "name", "huang" );
            IndexHits< Node > nodehit2 = nodeIndex.get( "name", "haixing" );
            Node node1 = nodehit.next();
            Node node2 = nodehit2.next();
            System.out.println( node1.getTimeField() );
            System.out.println( node2.getTimeField() );
        }
  //      */
        finally
        {
            db.shutdown();
        }
    }
}
