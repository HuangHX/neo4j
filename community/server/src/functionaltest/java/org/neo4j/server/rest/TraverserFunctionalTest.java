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
package org.neo4j.server.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.neo4j.server.WebTestUtils.CLIENT;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.neo4j.graphdb.Transaction;
import org.neo4j.helpers.collection.MapUtil;
import org.neo4j.server.NeoServerWithEmbeddedWebServer;
import org.neo4j.server.ServerBuilder;
import org.neo4j.server.database.DatabaseBlockedException;
import org.neo4j.server.rest.DocsGenerator.ResponseEntity;
import org.neo4j.server.rest.domain.GraphDbHelper;
import org.neo4j.server.rest.domain.JsonHelper;
import org.neo4j.server.rest.web.PropertyValueException;

import com.sun.jersey.api.client.ClientResponse;

public class TraverserFunctionalTest {
    private long startNode;
    private long child1_l1;
    private long child2_l1;
    private long child1_l2;
    private long child1_l3;
    private long child2_l3;

    private NeoServerWithEmbeddedWebServer server;
    private FunctionalTestHelper functionalTestHelper;
    private GraphDbHelper helper;

    @Before
    public void setupServer() throws Exception {
        server = ServerBuilder.server().withRandomDatabaseDir().withPassingStartupHealthcheck().build();
        server.start();
        functionalTestHelper = new FunctionalTestHelper(server);
        helper = functionalTestHelper.getGraphDbHelper();

        createSmallGraph();
    }

    @After
    public void stopServer() {
        server.stop();
        server = null;
    }

    private void createSmallGraph() throws Exception {
        Transaction tx = server.getDatabase().graph.beginTx();
        startNode = helper.createNode(MapUtil.map("name", "Root"));
        child1_l1 = helper.createNode(MapUtil.map("name", "Mattias"));
        helper.createRelationship("knows", startNode, child1_l1);
        child2_l1 = helper.createNode(MapUtil.map("name", "Johan"));
        helper.createRelationship("knows", startNode, child2_l1);
        child1_l2 = helper.createNode(MapUtil.map("name", "Emil"));
        helper.createRelationship("knows", child2_l1, child1_l2);
        child1_l3 = helper.createNode(MapUtil.map("name", "Peter"));
        helper.createRelationship("knows", child1_l2, child1_l3);
        child2_l3 = helper.createNode(MapUtil.map("name", "Tobias"));
        helper.createRelationship("loves", child1_l2, child2_l3);
        tx.success();
        tx.finish();
    }

    private ClientResponse traverse(long node, String description) {
        return CLIENT.resource(functionalTestHelper.nodeUri(node) + "/traverse/node").accept(MediaType.APPLICATION_JSON_TYPE).entity(description,
                MediaType.APPLICATION_JSON_TYPE).post(ClientResponse.class);
    }

    @Test
    public void shouldGet404WhenTraversingFromNonExistentNode() {
        ClientResponse response = traverse(99999, "{}");
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void shouldGet200WhenNoHitsFromTraversing() throws DatabaseBlockedException {
        long node = helper.createNode();
        ClientResponse response = traverse(node, "{}");
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        response.close();
    }

    @Test
    public void shouldGetSomeHitsWhenTraversingWithDefaultDescription() throws PropertyValueException
    {
        ClientResponse response = traverse(startNode, "");
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        String entity = response.getEntity(String.class);
        expectNodes(entity, child1_l1, child2_l1);
        response.close();
    }

    private void expectNodes(String entity, long... nodes) throws PropertyValueException
    {
        Set<String> expected = new HashSet<String>();
        for (long node : nodes) {
            expected.add(functionalTestHelper.nodeUri(node));
        }
        Collection<?> items = (Collection<?>) JsonHelper.jsonToSingleValue(entity);
        for (Object item : items) {
            Map<?, ?> map = (Map<?, ?>) item;
            String uri = (String) map.get("self");
            assertTrue(uri + " not found", expected.remove(uri));
        }
        assertTrue("Expected not empty:" + expected, expected.isEmpty());
    }

    @Test
    public void shouldGetExpectedHitsWhenTraversingWithDescription() throws PropertyValueException
    {
        ArrayList rels = new ArrayList<Map>();
        rels.add( MapUtil.map( "type","knows","direction","all") );
        rels.add( MapUtil.map( "type","loves","direction","all") );
        String description = JsonHelper.createJsonFrom(MapUtil.map("order", "breadth first","uniqueness","node global","prune evaluator", MapUtil.map("language", "builtin", "name", "none"), "return filter",
                MapUtil.map("language", "javascript", "body", "position.endNode().getProperty('name').toLowerCase().contains('t')"), "relationships",rels,"max depth", 3));
        
        ResponseEntity entity = DocsGenerator.create( "Traverse from a start node", 
                "In this example, no prune evaluator and a return filter are supplied. " +
        		"The result is to be returned as nodes, as indicated by `traverse/{returnTyoe}` in the URL with returnType" +
        		"being one of `node`, `relationship`, `path` or `fullpath`.\n" +
        		"The _position_ object in the body of the return and prune evaluators is an `org.neo4j.graphdb.Path` object " +
        		"representing the path from the start node to the current traversal position. `max depth` is a short-hand way" +
        		" of specifying a prune evaluator which prunes after a certain depth. " +
        		"If not specified a max depth of 1 is used and if a \"prune evaluator\" is specified instead of a max depth, " +
        		"no max depth limit is set.\n\n" +
        		"Built-in prune evaluators: `none`\n\n" +
        		"Built-in return filters: `all`, `all but start node`\n\n" +
                "Uniqueness: `node global`, `none`, `relationship global`, `node path`, `relationship path`\n\n" +
        		"Order values: `breadth frist`, `depth first`\n\n")
        .expectedStatus( Response.Status.OK ).payload( description )
        .post( functionalTestHelper.nodeUri( startNode ) + "/traverse/node" );
        expectNodes(entity.entity(), startNode, child1_l1, child1_l3, child2_l3);
    }

    @Test
    public void shouldGet400WhenSupplyingInvalidTraverserDescriptionFormat() throws DatabaseBlockedException {
        long node = helper.createNode();
        ClientResponse response = traverse(node, "::not JSON{[ at all");
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        response.close();
    }
}
