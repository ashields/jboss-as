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

package org.jboss.as.protocol;

import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.Executor;
import org.jboss.logging.Logger;

import static org.jboss.as.protocol.ProtocolConstants.*;

/**
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class ConnectionImpl implements Connection {

    private static final Logger log = Logger.getLogger("org.jboss.as.protocol.connection");

    private final Socket socket;

    private final Object lock = new Object();

    // protected by {@link #lock}
    private OutputStream sender;
    // protected by {@link #lock}
    private boolean readDone;
    // protected by {@link #lock}
    private boolean writeDone;

    private volatile MessageHandler messageHandler;

    private final Executor readExecutor;

    private volatile Object attachment;

    ConnectionImpl(final Socket socket, final MessageHandler handler, final Executor readExecutor) {
        this.socket = socket;
        messageHandler = handler;
        this.readExecutor = readExecutor;
    }

    public OutputStream writeMessage() throws IOException {
        final OutputStream os;
        synchronized (lock) {
            if (writeDone) {
                throw new IOException("Writes are already shut down");
            }
            while (sender != null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            boolean ok = false;
            try {
                sender = new MessageOutputStream();
                os = new BufferedOutputStream(sender);
                ok = true;
            } finally {
                if (! ok) {
                    // let someone else try
                    lock.notify();
                }
            }
        }
        return os;
    }

    public void shutdownWrites() throws IOException {
        synchronized (lock) {
            if (writeDone) return;
            while (sender != null) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException();
                }
            }
            writeDone = true;
            if (readDone) {
                socket.close();
            } else {
                socket.shutdownOutput();
            }
            lock.notifyAll();
        }
    }

    public void close() throws IOException {
        synchronized (lock) {
            lock.notifyAll();
            sender = null;
            readDone = true;
            writeDone = true;
            socket.close();
            lock.notifyAll();
        }
    }

    public void setMessageHandler(final MessageHandler messageHandler) {
        if (messageHandler == null) {
            throw new IllegalArgumentException("messageHandler is null");
        }
        this.messageHandler = messageHandler;
    }

    public InetAddress getPeerAddress() {
        synchronized (lock) {
            final Socket socket = this.socket;
            if (socket != null) {
                return socket.getInetAddress();
            } else {
                return null;
            }
        }
    }

    public void attach(final Object attachment) {
        this.attachment = attachment;
    }

    public Object getAttachment() {
        return attachment;
    }

    Runnable getReadTask() {
        return new Runnable() {
            public void run() {
                try {
                    Pipe pipe = null;
                    final InputStream is = socket.getInputStream();
                    OutputStream mos = null;
                    final int bufferSize = 8192;
                    final byte[] buffer = new byte[bufferSize];
                    for (;;) {

                        int cmd = is.read();
                        switch (cmd) {
                            case -1: {
                                log.trace("Received end of stream");
                                // end of stream
                                safeHandleShutdown();
                                boolean done;
                                if (mos != null) {
                                    mos.close();
                                    pipe.await();
                                }
                                synchronized (lock) {
                                    readDone = true;
                                    done = writeDone;
                                }
                                if (done) {
                                    StreamUtils.safeClose(socket);
                                    safeHandleFinished();
                                }
                                return;
                            }
                            case CHUNK_START: {
                                if (mos == null) {
                                    pipe = new Pipe(8192);
                                    // new message!
                                    final InputStream pis = pipe.getIn();
                                    mos = pipe.getOut();

                                    readExecutor.execute(new Runnable() {
                                        public void run() {
                                            safeHandleMessage(new MessageInputStream(pis));
                                        }
                                    });
                                }
                                int cnt = StreamUtils.readInt(is);
                                log.tracef("Received data chunk of size %d", Integer.valueOf(cnt));
                                while (cnt > 0) {
                                    int sc = is.read(buffer, 0, Math.min(cnt, bufferSize));
                                    if (sc == -1) {
                                        throw new EOFException("Unexpected end of stream");
                                    }
                                    mos.write(buffer, 0, sc);
                                    cnt -= sc;
                                }
                                break;
                            }
                            case CHUNK_END: {
                                log.trace("Received end data marker");
                                if (mos != null) {
                                    // end message
                                    mos.close();
                                    pipe.await();
                                    mos = null;
                                    pipe = null;
                                }
                                break;
                            }
                            default: {
                                throw new IOException("Invalid command byte read: " + cmd);
                            }
                        }
                    }
                } catch (IOException e) {
                    safeHandlerFailure(e);
                }
            }
        };
    }

    void safeHandleMessage(final InputStream pis) {
        try {
            messageHandler.handleMessage(this, pis);
        } catch (RuntimeException e) {
            log.errorf(e, "Failed to read a message");
        } catch (IOException e) {
            log.errorf(e, "Failed to read a message");
        } catch (NoClassDefFoundError e) {
            log.errorf(e, "Failed to read a message");
        } catch (Error e) {
            log.errorf(e, "Failed to read a message");
            throw e;
        } finally {
            StreamUtils.safeClose(pis);
        }
    }

    void safeHandleShutdown() {
        try {
            messageHandler.handleShutdown(this);
        } catch (IOException e) {
            log.errorf(e, "Failed to handle socket shut down condition");
        }
    }

    void safeHandleFinished() {
        try {
            messageHandler.handleFinished(this);
        } catch (IOException e) {
            log.errorf(e, "Failed to handle socket finished condition");
        }
    }

    void safeHandlerFailure(IOException e) {
        try {
            messageHandler.handleFailure(this, e);
        } catch (IOException e1) {
            log.errorf(e1, "Failed to handle socket failure condition");
        }
    }

    final class MessageInputStream extends FilterInputStream {

        protected MessageInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            try {
                while (in.read() != -1) {}
            } finally {
                super.close();
            }
        }
    }

    final class MessageOutputStream extends FilterOutputStream {

        private final byte[] hdr = new byte[5];

        MessageOutputStream() throws IOException {
            super(socket.getOutputStream());
        }

        @Override
        public void write(final int b) throws IOException {
            throw new IllegalStateException();
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            if (len == 0) {
                return;
            }
            final byte[] hdr = this.hdr;
            hdr[0] = (byte) CHUNK_START;
            hdr[1] = (byte) (len >> 24);
            hdr[2] = (byte) (len >> 16);
            hdr[3] = (byte) (len >> 8);
            hdr[4] = (byte) (len >> 0);
            synchronized (lock) {
                if (sender != this || writeDone) {
                    if (sender == this) sender = null;
                    lock.notifyAll();
                    throw new IOException("Write channel closed");
                }
                log.tracef("Sending data chunk of size %d", Integer.valueOf(len));
                out.write(hdr);
                out.write(b, off, len);
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (lock) {
                if (sender != this) {
                    return;
                }
                sender = null;
                // wake up waiters
                lock.notify();
                if (writeDone) throw new IOException("Write channel closed");
                if (readDone) {
                    readExecutor.execute(new Runnable() {
                        public void run() {
                            safeHandleFinished();
                        }
                    });
                }
                log.tracef("Sending end of message");
                out.write(CHUNK_END);
            }
        }

        @Override
        protected void finalize() throws Throwable {
            super.finalize();
            synchronized (lock) {
                if (sender == this) {
                    log.warnf("Leaked a message output stream; cleaning");
                    close();
                }
            }
        }
    }

}
