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
package com.hierynomus.smbj.transport;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import com.hierynomus.protocol.PacketData;
import com.hierynomus.protocol.transport.PacketReceiver;
import com.hierynomus.protocol.transport.TransportException;


public abstract class PacketReader<D extends PacketData<?>> implements Runnable {

    protected InputStream in;
    private PacketReceiver<D> handler;

    private AtomicBoolean stopped = new AtomicBoolean(false);
    private Thread thread;

    public PacketReader(String host, InputStream in, PacketReceiver<D> handler) {
        this.in = in;
        this.handler = handler;
        this.thread = new Thread(this, "Packet Reader for " + host);
        this.thread.setDaemon(true);
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted() && !stopped.get()) {
            try {
                readPacket();
            } catch (TransportException e) {
                if (stopped.get()) {
                    break;
                }
                System.out.println("tempGT2: PacketReader error, got exception." + e);
                handler.handleError(e);
                return;
            }
        }
        if (stopped.get()) {
            System.out.println("tempGT2: " + thread + " stopped.");
        }
    }

    public void stop() {
        System.out.println("tempGT2: Stopping PacketReader...");
        stopped.set(true);
        thread.interrupt();
    }

    private void readPacket() throws TransportException {
        D packet = doRead();
        System.out.println("tempGT2: Received packet " + packet);
        handler.handle(packet);
    }

    /**
     * Read the actual SMB2 Packet from the {@link InputStream}
     *
     * @return the read SMB2Packet
     * @throws TransportException
     */
    protected abstract D doRead() throws TransportException;

    public void start() {
        System.out.println("tempGT2: Starting PacketReader on thread: " + thread.getName());
        this.thread.start();
    }
}
