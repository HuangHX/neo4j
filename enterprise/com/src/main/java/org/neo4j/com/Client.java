/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.com;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.queue.BlockingReadHandler;

import org.neo4j.com.monitor.RequestMonitor;
import org.neo4j.com.storecopy.ResponseUnpacker;
import org.neo4j.com.storecopy.ResponseUnpacker.TxHandler;
import org.neo4j.helpers.Exceptions;
import org.neo4j.helpers.Triplet;
import org.neo4j.kernel.impl.store.MismatchingStoreIdException;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.lifecycle.LifecycleAdapter;
import org.neo4j.kernel.logging.Logging;
import org.neo4j.kernel.monitoring.ByteCounterMonitor;

import static java.util.concurrent.Executors.newCachedThreadPool;

import static org.neo4j.com.Protocol.addLengthFieldPipes;
import static org.neo4j.com.Protocol.assertChunkSizeIsWithinFrameSize;
import static org.neo4j.com.ResourcePool.DEFAULT_CHECK_INTERVAL;
import static org.neo4j.com.storecopy.ResponseUnpacker.NO_OP_TX_HANDLER;
import static org.neo4j.helpers.Clock.SYSTEM_CLOCK;
import static org.neo4j.helpers.NamedThreadFactory.daemon;

/**
 * A means for a client to communicate with a {@link Server}. It
 * serializes requests and sends them to the server and waits for
 * a response back.
 *
 * @see Server
 */
public abstract class Client<T> extends LifecycleAdapter implements ChannelPipelineFactory
{
    // Max number of concurrent channels that may exist. Needs to be high because we
    // don't want to run into that limit, it will make some #acquire calls block and
    // gets disastrous if that thread is holding monitors that is needed to communicate
    // with the server in some way.
    public static final int DEFAULT_MAX_NUMBER_OF_CONCURRENT_CHANNELS_PER_CLIENT = 20;
    public static final int DEFAULT_READ_RESPONSE_TIMEOUT_SECONDS = 20;

    private ClientBootstrap bootstrap;
    private final SocketAddress address;
    private final StringLogger msgLog;
    private ResourcePool<Triplet<Channel, ChannelBuffer, ByteBuffer>> channelPool;
    private final Protocol protocol;
    private final int frameLength;
    private final long readTimeout;
    private final int maxUnusedChannels;
    private final StoreId storeId;
    private ResourceReleaser resourcePoolReleaser;
    private final List<MismatchingVersionHandler> mismatchingVersionHandlers;
    private final ResponseUnpacker responseUnpacker;
    private final ByteCounterMonitor byteCounterMonitor;
    private final RequestMonitor requestMonitor;

    public Client( String hostNameOrIp, int port, Logging logging, StoreId storeId, int frameLength,
                   ProtocolVersion protocolVersion, long readTimeout, int maxConcurrentChannels, int chunkSize,
                   ResponseUnpacker responseUnpacker,
                   ByteCounterMonitor byteCounterMonitor, RequestMonitor requestMonitor )
    {
        assert byteCounterMonitor != null;
        assert requestMonitor != null;

        this.byteCounterMonitor = byteCounterMonitor;
        this.requestMonitor = requestMonitor;
        assertChunkSizeIsWithinFrameSize( chunkSize, frameLength );

        this.msgLog = logging.getMessagesLog( getClass() );
        this.storeId = storeId;
        this.frameLength = frameLength;
        this.readTimeout = readTimeout;
        // ResourcePool no longer controls max concurrent channels. Use this value for the pool size
        this.maxUnusedChannels = maxConcurrentChannels;
        this.mismatchingVersionHandlers = new ArrayList<>( 2 );
        this.address = new InetSocketAddress( hostNameOrIp, port );
        this.protocol = createProtocol( chunkSize, protocolVersion.getApplicationProtocol() );
        this.responseUnpacker = responseUnpacker;

        msgLog.info( getClass().getSimpleName() + " communication channel created towards " + address );
    }

    protected Protocol createProtocol( int chunkSize, byte applicationProtocolVersion )
    {
        return new Protocol214( chunkSize, applicationProtocolVersion, getInternalProtocolVersion() );
    }

    @Override
    public void start()
    {
        String threadNameFormat = "%s-" + getClass().getSimpleName() + "@" + address;
        bootstrap = new ClientBootstrap( new NioClientSocketChannelFactory(
                newCachedThreadPool( daemon( getClass().getSimpleName() + "-boss@" + address ) ),
                newCachedThreadPool( daemon( getClass().getSimpleName() + "-worker@" + address ) ) ) );
        bootstrap.setPipelineFactory( this );

        channelPool = new ResourcePool<Triplet<Channel, ChannelBuffer, ByteBuffer>>( maxUnusedChannels,
                new ResourcePool.CheckStrategy.TimeoutCheckStrategy( DEFAULT_CHECK_INTERVAL, SYSTEM_CLOCK ),
                new LoggingResourcePoolMonitor( msgLog ) )
        {
            @Override
            protected Triplet<Channel, ChannelBuffer, ByteBuffer> create()
            {
                ChannelFuture channelFuture = bootstrap.connect( address );
                channelFuture.awaitUninterruptibly( 5, TimeUnit.SECONDS );
                Triplet<Channel, ChannelBuffer, ByteBuffer> channel;
                if ( channelFuture.isSuccess() )
                {
                    channel = Triplet.of( channelFuture.getChannel(),
                            ChannelBuffers.dynamicBuffer(),
                            ByteBuffer.allocate( 1024 * 1024 ) );
                    msgLog.logMessage( "Opened a new channel to " + address, true );
                    return channel;
                }

                String msg = Client.this.getClass().getSimpleName() + " could not connect to " + address;
                msgLog.logMessage( msg, true );
                throw new ComException( msg );
            }

            @Override
            protected boolean isAlive( Triplet<Channel, ChannelBuffer, ByteBuffer> resource )
            {
                return resource.first().isConnected();
            }

            @Override
            protected void dispose( Triplet<Channel, ChannelBuffer, ByteBuffer> resource )
            {
                Channel channel = resource.first();
                if ( channel.isConnected() )
                {
                    msgLog.debug( "Closing channel: " + channel + ". Channel pool size is now " + currentSize() );
                    channel.close();
                }
            }
        };
        /*
         * This is here to couple the channel releasing to Response.close() itself and not
         * to TransactionStream.close() as it is implemented here. The reason is that a Response
         * that is returned without a TransactionStream will still hold the channel and should
         * release it eventually. Also, logically, closing the channel is not dependent on the
         * TransactionStream.
         */
        resourcePoolReleaser = new ResourceReleaser()
        {
            @Override
            public void release()
            {
                channelPool.release();
            }
        };
    }


    @Override
    public void stop()
    {
        channelPool.close( true );
        bootstrap.releaseExternalResources();
        mismatchingVersionHandlers.clear();
        msgLog.logMessage( toString() + " shutdown", true );
    }

    protected <R> Response<R> sendRequest( RequestType<T> type, RequestContext context,
                                           Serializer serializer, Deserializer<R> deserializer )
    {
        return sendRequest( type, context, serializer, deserializer, null, NO_OP_TX_HANDLER );
    }

    protected <R> Response<R> sendRequest( RequestType<T> type, RequestContext context,
                                           Serializer serializer, Deserializer<R> deserializer,
                                           StoreId specificStoreId, TxHandler txHandler )
    {
        boolean success = true;
        Triplet<Channel, ChannelBuffer, ByteBuffer> channelContext;
        Throwable failure = null;

        // Send 'em over the wire
        Channel channel;
        ChannelBuffer output;
        ByteBuffer input;
        try
        {
            channelContext = getChannel( type );
            channel = channelContext.first();
            output = channelContext.second();
            input = channelContext.third();
        }
        catch ( Throwable e )
        {
            throw Exceptions.launderedException( ComException.class, e );
        }

        try
        {
            notifyRequestMonitor( type, context, channel );

            // Request
            protocol.serializeRequest( channel, output, type, context, serializer );

            // Response
            @SuppressWarnings("unchecked")
            Response<R> response = protocol.deserializeResponse(
                    (BlockingReadHandler<ChannelBuffer>) channel.getPipeline().get( "blockingHandler" ), input,
                    getReadTimeout( type, readTimeout ), deserializer, resourcePoolReleaser );

            if ( type.responseShouldBeUnpacked() )
            {
                responseUnpacker.unpackResponse( response, txHandler );
            }

            if ( shouldCheckStoreId( type ) )
            {
                // specificStoreId is there as a workaround for then the graphDb isn't initialized yet
                if ( specificStoreId != null )
                {
                    assertCorrectStoreId( response.getStoreId(), specificStoreId );
                }
                else
                {
                    assertCorrectStoreId( response.getStoreId(), storeId );
                }
            }

            return response;
        }
        catch ( IllegalProtocolVersionException e )
        {
            failure = e;
            success = false;
            for ( MismatchingVersionHandler handler : mismatchingVersionHandlers )
            {
                handler.versionMismatched( e.getExpected(), e.getReceived() );
            }
            throw e;
        }
        catch ( Throwable e )
        {
            failure = e;
            success = false;
            if ( channelContext != null )
            {
                closeChannel( channelContext );
            }
            throw Exceptions.launderedException( ComException.class, e );
        }
        finally
        {
            /*
             * Otherwise the user must call response.close() to prevent resource leaks.
             */
            if ( !success )
            {
                releaseChannel();
            }
            requestMonitor.endRequest( failure );
        }
    }

    private void notifyRequestMonitor( RequestType<T> type, RequestContext context, Channel channel )
    {
        Map<String, String> requestContext = new HashMap<>( 3, 1 );
        requestContext.put( "type", type.toString() );
        requestContext.put( "slaveContext", context.toString() );
        requestContext.put( "serverAddress", channel.getRemoteAddress().toString() );

        requestMonitor.beginRequest( requestContext );
    }

    protected long getReadTimeout( RequestType<T> type, long readTimeout )
    {
        return readTimeout;
    }

    protected boolean shouldCheckStoreId( RequestType<T> type )
    {
        return true;
    }

    protected StoreId getStoreId()
    {
        return storeId;
    }

    private void assertCorrectStoreId( StoreId storeId, StoreId myStoreId )
    {
        if ( !myStoreId.equals( storeId ) )
        {
            throw new MismatchingStoreIdException( myStoreId, storeId );
        }
    }

    private Triplet<Channel, ChannelBuffer, ByteBuffer> getChannel( RequestType<T> type ) throws Exception
    {
        // Calling acquire is dangerous since it may be a blocking call... and if this
        // thread holds a lock which others may want to be able to communicate with
        // the server things go stiff.
        Triplet<Channel, ChannelBuffer, ByteBuffer> result = channelPool.acquire();
        if ( result == null )
        {
            msgLog.error( "Unable to acquire new channel for " + type );
            throw new ComException( "Unable to acquire new channel for " + type );
        }
        return result;
    }

    private void releaseChannel()
    {
        channelPool.release();
    }

    private void closeChannel( Triplet<Channel, ChannelBuffer, ByteBuffer> channel )
    {
        channel.first().close().awaitUninterruptibly();
    }

    @Override
    public ChannelPipeline getPipeline() throws Exception
    {
        ChannelPipeline pipeline = Channels.pipeline();
        pipeline.addLast( "monitor", new MonitorChannelHandler( byteCounterMonitor ) );
        addLengthFieldPipes( pipeline, frameLength );
        BlockingReadHandler<ChannelBuffer> reader =
                new BlockingReadHandler<>( new ArrayBlockingQueue<ChannelEvent>( 100, false ) );
        pipeline.addLast( "blockingHandler", reader );
        return pipeline;
    }

    public void addMismatchingVersionHandler( MismatchingVersionHandler toAdd )
    {
        mismatchingVersionHandlers.add( toAdd );
    }

    protected byte getInternalProtocolVersion()
    {
        return Server.INTERNAL_PROTOCOL_VERSION;
    }

    @Override
    public String toString()
    {
        return getClass().getSimpleName() + "[" + address + "]";
    }
}
