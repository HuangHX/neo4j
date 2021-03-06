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
package org.neo4j.kernel.impl.api.state;

public final class RelationshipState extends PropertyContainerState
{
    private long startNode = -1;
    private long endNode = -1;
    private int type = -1;
    //HuangTask
    private long timeField=-1;

    public RelationshipState( long id )
    {
        super( id );
    }

    public void setMetaData( long startNode, long endNode, int type )
    {
        this.startNode = startNode;
        this.endNode = endNode;
        this.type = type;
    }
    
    //HuangTask
    public void setMetaData( long startNode, long endNode, int type, long timeid )
    {
        this.startNode = startNode;
        this.endNode = endNode;
        this.type = type;
        this.timeField = timeid;
    }

    public long startNode()
    {
        return startNode;
    }

    public long endNode()
    {
        return endNode;
    }

    public int type()
    {
        return type;
    }
    
    //HuangTask
    public long timeField()
    {
        return timeField;
    }
}
