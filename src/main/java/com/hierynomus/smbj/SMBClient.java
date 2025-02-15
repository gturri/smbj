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
package com.hierynomus.smbj;

import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.event.ConnectionClosed;
import com.hierynomus.smbj.event.SMBEventBus;
import com.hierynomus.smbj.server.ServerList;
import net.engio.mbassy.listener.Handler;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.hierynomus.protocol.commons.IOUtils.closeSilently;

/**
 * Server Message Block Client API.
 */
public class SMBClient implements Closeable {
    /**
     * The default TCP port for SMB
     */
    public static final int DEFAULT_PORT = 445;

    private Map<String, Connection> connectionTable = new ConcurrentHashMap<>();
    private ServerList serverList = new ServerList();

    private SmbConfig config;

    private SMBEventBus bus;

    public SMBClient() {
        this(SmbConfig.createDefaultConfig());
    }

    public SMBClient(SmbConfig config) {
        this(config, new SMBEventBus());
    }

    public SMBClient(SmbConfig config, SMBEventBus bus) {
        this.config = config;
        this.bus = bus;
        bus.subscribe(this);
    }

    /**
     * Connect to the host at <pre>hostname</pre> on the default port (445)
     *
     * @param hostname The hostname to connect to.
     * @return An established connection.
     * @throws IOException If the connection could not be established.
     */
    public Connection connect(String hostname) throws IOException {
        return getEstablishedOrConnect(hostname, DEFAULT_PORT);
    }

    /**
     * Connect to the host at <pre>hostname</pre> on the given port
     *
     * @param hostname The hostname to connect to.
     * @param port     The port to connect to
     * @return An established connection.
     * @throws IOException If the connection could not be established.
     */
    public Connection connect(String hostname, int port) throws IOException {
        return getEstablishedOrConnect(hostname, port);
    }

    private Connection getEstablishedOrConnect(String hostname, int port) throws IOException {
        synchronized (this) {
            String hostPort = hostname + ":" + port;
            Connection cachedConnection = connectionTable.get(hostPort);
            if (cachedConnection != null) {
                cachedConnection = cachedConnection.lease();
            }
            if (cachedConnection == null || !cachedConnection.isConnected()) {
                Connection connection = new Connection(config, this, bus, serverList);
                try {
                    connection.connect(hostname, port);
                } catch (IOException e) {
                    closeSilently(connection); // Quietly close broken connection.
                    throw e;
                }
                connectionTable.put(hostPort, connection);
                return connection;
            }

            return cachedConnection;
        }
    }

    @Handler
    @SuppressWarnings("unused")
    private void connectionClosed(ConnectionClosed event) {
        synchronized (this) {
            String hostPort = event.getHostname() + ":" + event.getPort();
            connectionTable.remove(hostPort);
            System.out.println("tempGT2: Connection to << " + hostPort + " >> closed");
        }
    }


    @Override
    public void close() {
        System.out.println("tempGT2: Going to close all remaining connections");
        for (Connection connection : connectionTable.values()) {
            try {
                connection.close();
            } catch (Exception e) {
                System.out.println("tempGT2: Error closing connection to host " + connection.getRemoteHostname());
                System.out.println("tempGT2: Exception was: " + e);
            }
        }
    }
}
