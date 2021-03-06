/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.demos.domain.servers.runner;

import java.util.Collections;
import org.jboss.as.domain.client.api.HostUpdateResult;
import org.jboss.as.model.AbstractHostModelUpdate;
import org.jboss.as.model.HostServerAdd;
import org.jboss.as.model.HostServerRemove;
import org.jboss.as.model.HostServerUpdate;
import org.jboss.as.model.ServerElementSocketBindingGroupUpdate;
import org.jboss.as.model.ServerElementSocketBindingPortOffsetUpdate;
import org.jboss.as.model.ServerPortOffsetUpdate;
import static org.jboss.as.protocol.StreamUtils.safeClose;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.xml.stream.FactoryConfigurationError;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.jboss.as.domain.client.api.DomainClient;
import org.jboss.as.domain.client.api.ServerIdentity;
import org.jboss.as.domain.client.api.ServerStatus;
import org.jboss.as.model.ServerModel;
import org.jboss.staxmapper.XMLContentWriter;
import org.jboss.staxmapper.XMLExtendedStreamWriter;
import org.jboss.staxmapper.XMLMapper;

/**
 * Demonstration of basic aspects of administering servers via the domain management API.
 *
 * TODO improve this by putting it in a loop that lists the set of (numbered) commands on sysout
 * and reads the desired command and parameter from stdin
 *
 * @author Brian Stansberry
 */
public class ExampleRunner {

    public static void main(String[] args) throws Exception {
        DomainClient client = null;
        try {
            client = DomainClient.Factory.create(InetAddress.getByName("localhost"), 9999);

            System.out.println("\nReading the list of configured servers:");
            Map<ServerIdentity, ServerStatus> statuses = new HashMap<ServerIdentity, ServerStatus>(client.getServerStatuses());
            for(Map.Entry<ServerIdentity, ServerStatus> server : statuses.entrySet()) {
                ServerIdentity id = server.getKey();
                System.out.println("\nServer:\n");
                System.out.println("server name:         " + id.getServerName());
                System.out.println("server manager name: " + id.getHostName());
                System.out.println("server group name:   " + id.getServerGroupName());
                System.out.println("status:              " + server.getValue());
            }
            // Find some servers to manipulate
            List<ServerIdentity> servers = findUsefulServers(statuses, ServerStatus.STARTED);

            for (ServerIdentity server : servers) {
                System.out.println("\nReading runtime configuration for " + server.getServerName() + "\n");
                ServerModel sm = client.getServerModel(server.getHostName(), server.getServerName());
                if (sm == null) {
                    System.out.println("ERROR: server model is null");
                }
                else {
                    System.out.println(writeModel("server", sm));
                }
            }

            for (ServerIdentity server : servers) {
                System.out.println("\nStopping server " + server.getServerName() + "\n");
                ServerStatus status = client.stopServer(server.getHostName(), server.getServerName(), -1, TimeUnit.SECONDS);
                System.out.println("Stop executed. Server status is " + status);
                statuses.put(server, status);
            }

            servers = findUsefulServers(statuses, ServerStatus.STOPPED);

            for (ServerIdentity server : servers) {
                System.out.println("\nStarting server " + server.getServerName() + "\n");
                ServerStatus status = client.startServer(server.getHostName(), server.getServerName());
                System.out.println("Start executed. Server status is " + status);
                statuses.put(server, status);
            }

            Thread.sleep(2000);

            servers = findUsefulServers(statuses, ServerStatus.STARTED);

            for (ServerIdentity server : servers) {
                System.out.println("\nRestarting server " + server.getServerName() + "\n");
                ServerStatus status = client.restartServer(server.getHostName(), server.getServerName(), -1, TimeUnit.SECONDS);
                System.out.println("Restart executed. Server status is " + status);
                statuses.put(server, status);
            }

            Thread.sleep(2000);

            System.out.println("\nCurrent server statuses\n");
            for(Map.Entry<ServerIdentity, ServerStatus> server : client.getServerStatuses().entrySet()) {
                ServerIdentity id = server.getKey();
                System.out.println("\nServer:\n");
                System.out.println("server name:         " + id.getServerName());
                System.out.println("server manager name: " + id.getHostName());
                System.out.println("server group name:   " + id.getServerGroupName());
                System.out.println("status:              " + server.getValue());
            }


            Thread.sleep(2000);

            final String serverName = "example-server";
            System.out.println("\nCreating new server: " + serverName + "\n");
            final List<String> serverManagers = client.getServerManagerNames();
            final String serverManagerName = serverManagers.get(0);
            System.out.println("Adding to server manager: " + serverManagerName);
            final String serverGroup = client.getDomainModel().getServerGroupNames().iterator().next();
            System.out.println("Adding to server group: " + serverGroup);
            List<AbstractHostModelUpdate<?>> updates = new ArrayList<AbstractHostModelUpdate<?>>(2);
            updates.add(new HostServerAdd(serverName, serverGroup));
            updates.add(HostServerUpdate.create(serverName, new ServerElementSocketBindingGroupUpdate("standard-sockets")));
            updates.add(HostServerUpdate.create(serverName, new ServerElementSocketBindingPortOffsetUpdate(350)));
            List<HostUpdateResult<?>> results = client.applyHostUpdates(serverManagerName, updates);
            HostUpdateResult<?> result = results.get(0);
            System.out.println("Add success: " + result.isSuccess());

            if(result.isSuccess()) {
                System.out.println("Starting server " + serverName);
                ServerStatus status = client.startServer(serverManagerName, serverName);
                System.out.println("Start executed. Server status is " + status);

                Thread.sleep(1000);

                System.out.println("\nStopping server " + serverName);
                status = client.stopServer(serverManagerName, serverName, -1, TimeUnit.SECONDS);
                System.out.println("Stop executed. Server status is " + status);

                System.out.println("Removing server " + serverName);
                updates = new ArrayList<AbstractHostModelUpdate<?>>(1);
                updates.add(new HostServerRemove(serverName));

                results = client.applyHostUpdates(serverManagerName, updates);
                result = results.get(0);
                System.out.println("Remove success: " + result.isSuccess());
            }

        } finally {
            safeClose(client);
        }
    }

    private static String writeModel(final String element, final XMLContentWriter content) throws Exception, FactoryConfigurationError {
        final XMLMapper mapper = XMLMapper.Factory.create();
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final BufferedOutputStream bos = new BufferedOutputStream(baos);
        final XMLStreamWriter writer = XMLOutputFactory.newInstance().createXMLStreamWriter(bos);
        try {
            mapper.deparseDocument(new RootElementWriter(element, content), writer);
        }
        catch (XMLStreamException e) {
            // Dump some diagnostics
            System.out.println("XML Content that was written prior to exception:");
            System.out.println(writer.toString());
            throw e;
        }
        finally {
            writer.close();
            bos.close();
        }
        return new String(baos.toByteArray());
    }

    private static class RootElementWriter implements XMLContentWriter {

        private final String element;
        private final XMLContentWriter content;

        RootElementWriter(final String element, final XMLContentWriter content) {
            this.element = element;
            this.content = content;
        }

        @Override
        public void writeContent(XMLExtendedStreamWriter streamWriter) throws XMLStreamException {
            streamWriter.writeStartDocument();
            streamWriter.writeStartElement(element);
            content.writeContent(streamWriter);
            streamWriter.writeEndDocument();
        }

    }

    private static List<ServerIdentity> findUsefulServers(Map<ServerIdentity, ServerStatus> statuses, ServerStatus requiredStatus) {
        ServerIdentity a = null;
        ServerIdentity b = null;

        for(Map.Entry<ServerIdentity, ServerStatus> server : statuses.entrySet()) {
            ServerStatus status = server.getValue();
            if (status == requiredStatus) {
                ServerIdentity id   = server.getKey();

                if (a == null) {
                    a = id;
                }
                else if (b == null) {
                    if ("other-server-group".equals(a.getServerGroupName())) {
                        b = id;
                    }
                    else {
                        b = a;
                        a = id;
                    }
                }
                else if (a.getHostName().equals(id.getHostName())) {
                    if (b.getHostName().equals(id.getHostName())) {
                        if ("other-server-group".equals(id.getServerGroupName())) {
                            b = id;
                        }
                    }
                    else if ("other-server-group".equals(id.getServerGroupName())) {
                        a = id;
                    }
                }
                else if ("other-server-group".equals(id.getServerGroupName())) {
                    b = a;
                    a = id;
                }
                else {
                    b = id;
                }
            }
        }

        if (a == null) {
             throw new IllegalStateException("No started servers are available");
        }
        else {
            List<ServerIdentity> result = new ArrayList<ServerIdentity>();
            result.add(a);
            if (b != null) {
                result.add(b);
            }
            return result;
        }
    }

}
