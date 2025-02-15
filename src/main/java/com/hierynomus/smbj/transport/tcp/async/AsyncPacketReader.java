/*
 * Copyright (C)2016 - SMBJ Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hierynomus.smbj.transport.tcp.async;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hierynomus.protocol.PacketData;
import com.hierynomus.protocol.commons.buffer.Buffer.BufferException;
import com.hierynomus.protocol.transport.PacketFactory;
import com.hierynomus.protocol.transport.PacketReceiver;

public class AsyncPacketReader<D extends PacketData<?>> {
    private final PacketFactory<D> packetFactory;
    private PacketReceiver<D> handler;
    private final AsynchronousSocketChannel channel;
    private String remoteHost;
    private int soTimeout = 0;

    private AtomicBoolean stopped = new AtomicBoolean(false);

    public AsyncPacketReader(AsynchronousSocketChannel channel, PacketFactory<D> packetFactory,
                             PacketReceiver<D> handler) {
        this.channel = channel;
        this.packetFactory = packetFactory;
        this.handler = handler;
    }

    public void start(String remoteHost, int soTimeout) {
        this.remoteHost = remoteHost;
        this.soTimeout = soTimeout;
        initiateNextRead(new PacketBufferReader());
    }

    public void stop() {
        stopped.set(true);
    }

    private void initiateNextRead(PacketBufferReader bufferReader) {
        if (stopped.get()) {
            System.out.println("tempGT2: Stopped, not initiating another read operation.");
            return;
        }
        System.out.println("tempGT2: Initiating next read");
        channel.read(bufferReader.getBuffer(), this.soTimeout, TimeUnit.MILLISECONDS, bufferReader,
            new CompletionHandler<Integer, PacketBufferReader>() {

                @Override
                public void completed(Integer bytesRead, PacketBufferReader reader) {
                    System.out.println("tempGT2: Received " + bytesRead + " bytes");
                    if (bytesRead < 0) {
                        handleClosedReader();
                        return; // stop the read cycle
                    }
                    try {
                        processPackets(reader);
                        initiateNextRead(reader);
                    } catch (RuntimeException e) {
                        handleAsyncFailure(e);
                    }
                }

                @Override
                public void failed(Throwable exc, PacketBufferReader attachment) {
                    handleAsyncFailure(exc);
                }

                private void processPackets(PacketBufferReader reader) {
                    for (byte[] packetBytes = reader.readNext(); packetBytes != null; packetBytes = reader
                        .readNext()) {
                        readAndHandlePacket(packetBytes);
                    }
                }

                private void handleClosedReader() {
                    if (!stopped.get()) {
                        handleAsyncFailure(new EOFException("Connection closed by server"));
                    }
                }

            });
    }

    private void readAndHandlePacket(byte[] packetBytes) {
        try {
            D packet = packetFactory.read(packetBytes);
            System.out.println("tempGT2: Received packet << " + packet + " >>");
            handler.handle(packet);
        } catch (BufferException | IOException e) {
            handleAsyncFailure(e);
        }
    }

    private void handleAsyncFailure(Throwable exc) {
        if (isChannelClosedByOtherParty(exc)) {
            System.out.println("tempGT2: Channel to " + remoteHost + " closed by other party, closing it locally.");
        } else {
            String excClass = exc.getClass().getSimpleName();
            System.out.println("tempGT2: " + excClass + " on channel to " + remoteHost + ", closing channel: " + exc.getMessage());
            System.out.println("tempGT2: Exception was: " +  exc);
        }
        closeChannelQuietly();
    }

    private boolean isChannelClosedByOtherParty(Throwable exc) {
        return exc instanceof AsynchronousCloseException;
    }

    private void closeChannelQuietly() {
        try {
            channel.close();
        } catch (IOException e) {
            String eClass = e.getClass().getSimpleName();
            System.out.println("tempGT2: " + eClass + " while closing channel to " + remoteHost + " on failure: " + e.getMessage());
        }
    }

}
