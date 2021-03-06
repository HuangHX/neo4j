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
package org.neo4j.unsafe.impl.batchimport.cache;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class DynamicLongArrayTest
{
    @Test
    public void shouldWorkOnSingleChunk() throws Exception
    {
        // GIVEN
        LongArray array = LongArrayFactory.AUTO.newDynamicLongArray( 10, 0 );
        array.set( 4, 5 );

        // WHEN
        assertEquals( 5L, array.get( 4 ) );
        assertEquals( 0L, array.get( 12 ) );
        array.set( 12, 13 );
        assertEquals( 13L, array.get( 12 ) );
    }

    @Test
    public void shouldAddManyChunks() throws Exception
    {
        // GIVEN
        LongArray array = LongArrayFactory.AUTO.newDynamicLongArray( 10, 0 );

        // WHEN
        long index = 243;
        long value = 5485748;
        array.set( index, value );

        // THEN
        assertEquals( value, array.get( index ) );
    }
}
