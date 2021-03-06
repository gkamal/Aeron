/*
 * Copyright 2014 - 2016 Real Logic Ltd.
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
package io.aeron.driver.media;

import io.aeron.protocol.DataHeaderFlyweight;
import io.aeron.protocol.SetupFlyweight;
import org.agrona.BufferUtil;
import org.agrona.LangUtil;
import org.agrona.collections.ArrayUtil;
import org.agrona.concurrent.UnsafeBuffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;

import static io.aeron.logbuffer.FrameDescriptor.frameType;
import static io.aeron.protocol.HeaderFlyweight.HDR_TYPE_DATA;
import static io.aeron.protocol.HeaderFlyweight.HDR_TYPE_PAD;
import static io.aeron.protocol.HeaderFlyweight.HDR_TYPE_SETUP;
import static org.agrona.BitUtil.CACHE_LINE_LENGTH;

/**
 * Encapsulates the polling of a number of {@link UdpChannelTransport}s using whatever means provides the lowest latency.
 */
public class DataTransportPoller extends UdpTransportPoller
{
    private static final int MAX_UDP_PACKET = 1024 * 64;

    private final ByteBuffer byteBuffer;
    private final UnsafeBuffer unsafeBuffer;
    private final DataHeaderFlyweight dataMessage;
    private final SetupFlyweight setupMessage;
    private ReceiveChannelEndpoint[] transports = new ReceiveChannelEndpoint[0];

    public DataTransportPoller()
    {
        byteBuffer = BufferUtil.allocateDirectAligned(MAX_UDP_PACKET, CACHE_LINE_LENGTH * 2);
        unsafeBuffer = new UnsafeBuffer(byteBuffer);
        dataMessage = new DataHeaderFlyweight(unsafeBuffer);
        setupMessage = new SetupFlyweight(unsafeBuffer);
    }

    public int pollTransports()
    {
        int bytesReceived = 0;
        try
        {
            if (transports.length <= ITERATION_THRESHOLD)
            {
                for (final ReceiveChannelEndpoint transport : transports)
                {
                    bytesReceived += poll(transport);
                }
            }
            else
            {
                selector.selectNow();

                final SelectionKey[] keys = selectedKeySet.keys();
                for (int i = 0, length = selectedKeySet.size(); i < length; i++)
                {
                    bytesReceived += poll((ReceiveChannelEndpoint)keys[i].attachment());
                }

                selectedKeySet.reset();
            }
        }
        catch (final IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return bytesReceived;
    }

    public SelectionKey registerForRead(final UdpChannelTransport transport)
    {
        return registerForRead((ReceiveChannelEndpoint)transport);
    }

    public SelectionKey registerForRead(final ReceiveChannelEndpoint transport)
    {
        SelectionKey key = null;
        try
        {
            transports = ArrayUtil.add(transports, transport);
            key = transport.receiveDatagramChannel().register(selector, SelectionKey.OP_READ, transport);
        }
        catch (final ClosedChannelException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return key;
    }

    public void cancelRead(final UdpChannelTransport transport)
    {
        cancelRead((ReceiveChannelEndpoint)transport);
    }

    public void cancelRead(final ReceiveChannelEndpoint transport)
    {
        transports = ArrayUtil.remove(transports, transport);
    }

    private int poll(final ReceiveChannelEndpoint channelEndpoint)
    {
        int bytesReceived = 0;
        final InetSocketAddress srcAddress = channelEndpoint.receive(byteBuffer);

        if (null != srcAddress)
        {
            final int length = byteBuffer.position();

            if (channelEndpoint.isValidFrame(unsafeBuffer, length))
            {
                switch (frameType(unsafeBuffer, 0))
                {
                    case HDR_TYPE_PAD:
                    case HDR_TYPE_DATA:
                        bytesReceived = channelEndpoint.onDataPacket(dataMessage, unsafeBuffer, length, srcAddress);
                        break;

                    case HDR_TYPE_SETUP:
                        channelEndpoint.onSetupMessage(setupMessage, unsafeBuffer, srcAddress);
                        break;
                }
            }
        }

        return bytesReceived;
    }
}
