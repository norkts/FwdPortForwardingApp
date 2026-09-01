/*
 * Fwd: the port forwarding app
 * Copyright (C) 2016  Elixsr Ltd
 *
 * This program is free software: you can redistribute it and/or modify
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

package com.elixsr.portforwarder.forwarding;

import android.util.Log;
import com.elixsr.portforwarder.util.LogBuffer;

import com.elixsr.portforwarder.exceptions.BindException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.concurrent.Callable;

/**
 * Skeleton taken from: http://cs.ecs.baylor.edu/~donahoo/practical/JavaSockets2/code/UDPEchoServerSelector.java
 * <p>
 * Created by Niall McShane on 21/02/2016.
 */
public class UdpForwarder extends Forwarder implements Callable<Void> {

    private static final String TAG = "UdpForwarder";
    private static final int BUFFER_SIZE = 100000;

    private static final int TIMEOUT = 3000; // Wait timeout (milliseconds)

    private DatagramChannel inChannel;
    private Selector selector;
    public UdpForwarder(InetSocketAddress form, InetSocketAddress to, String ruleName) {
        super("UDP", form, to, ruleName);
    }

    public Void call() throws IOException, BindException {

        LogBuffer.getInstance().d(TAG, String.format(super.START_MESSAGE, protocol, from.getPort(), to.getPort()));

        try {
            ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);

            inChannel = openDatagramChannelV4();
            registerResource(inChannel);            inChannel.configureBlocking(false);

            try {
                // 强制绑定 IPv4 0.0.0.0，避免默认绑定 IPv6 [::] 导致防火墙/iptables 阻止外部连接
                InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", from.getPort());
                Log.i(TAG, "UDP binding to port " + from.getPort() + " on IPv4 all interfaces (0.0.0.0)");
                inChannel.socket().bind(bindAddress);
            } catch (SocketException e) {
                LogBuffer.getInstance().e(TAG, String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
                throw new BindException(String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
            }

            selector = Selector.open();
            registerResource(selector);            inChannel.register(selector, SelectionKey.OP_READ, new ClientRecord(to));
            LogBuffer.getInstance().i(TAG, "UDP listening on port " + from.getPort() + ", forwarding to " + to.getPort());

            while (true) { // Run forever, receiving and echoing datagrams

                if (Thread.currentThread().isInterrupted()) {
                    LogBuffer.getInstance().i(TAG, String.format(super.THREAD_INTERRUPT_CLEANUP_MESSAGE, protocol));
                    inChannel.socket().close();
                    break;
                }

                int count = selector.select();
                if (count > 0) {


                    // Get iterator on set of keys with I/O to process
                    Iterator<SelectionKey> keyIter = selector.selectedKeys().iterator();
                    while (keyIter.hasNext()) {
                        SelectionKey key = keyIter.next(); // Key is bit mask

                        // Client socket channel has pending data?
                        if (key.isReadable()) {
                            // LogBuffer.getInstance().i(TAG, "Have Something to READ");
                            handleRead(key, readBuffer);
                        }

                        // Client socket channel is available for writing and
                        // key is valid (i.e., channel not closed).
                        if (key.isValid() && key.isWritable()) {
                            // LogBuffer.getInstance().i(TAG, "Have Something to WRITE");
                            handleWrite(key);
                        }

                        keyIter.remove();
                    }
                }
            }
        } catch (IOException e) {
            LogBuffer.getInstance().e(TAG, "Problem opening Selector", e);
            throw e;
        }

        return null;
    }

    public static void handleRead(SelectionKey key, ByteBuffer readBuffer) throws IOException {

        // Log.i("UdpForwarder", "Handling Read");
        DatagramChannel channel = (DatagramChannel) key.channel();
        ClientRecord clientRecord = (ClientRecord) key.attachment();

        // Ensure the buffer is empty
        readBuffer.clear();

        // Receive the data
        channel.receive(readBuffer);

        // Get read to wrte, then send
        readBuffer.flip();
        channel.send(readBuffer, clientRecord.toAddress);

        // If there is anything remaining in the buffer
        if (readBuffer.remaining() > 0) {
            clientRecord.writeBuffer.put(readBuffer);
            key.interestOps(SelectionKey.OP_WRITE);
        }

//        ClientRecord clientRecord = (ClientRecord) key.attachment();
//        clientRecord.buffer.clear();    // Prepare buffer for receiving
//        clientRecord.clientAddress = channel.receive(clientRecord.buffer);
//
//        if (clientRecord.clientAddress != null) {  // Did we receive something?
//            // Register write with the selector
//            key.interestOps(SelectionKey.OP_WRITE);
//        }
    }

    public static void handleWrite(SelectionKey key) throws IOException {
        DatagramChannel channel = (DatagramChannel) key.channel();
        ClientRecord clientRecord = (ClientRecord) key.attachment();
        clientRecord.writeBuffer.flip(); // Prepare buffer for sending
        int bytesSent = channel.send(clientRecord.writeBuffer, clientRecord.toAddress);
        LogBuffer.getInstance().d(TAG, "UDP sent " + bytesSent + " bytes to " + clientRecord.toAddress);


        if (clientRecord.writeBuffer.remaining() > 0) {
            clientRecord.writeBuffer.compact();
        } else {
            key.interestOps(SelectionKey.OP_READ);
            clientRecord.writeBuffer.clear();
        }

//        if (bytesSent != 0) { // Buffer completely written?
//            // No longer interested in writes
//            key.interestOps(SelectionKey.OP_READ);
//        }
    }


    @Override
    public void close() {
        isRunning = false;
        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
            if (inChannel != null && inChannel.isOpen()) {
                LogBuffer.getInstance().i(TAG, "UDP closing channel on port " + from.getPort());
                inChannel.close();
            }
        } catch (Exception e) {
            LogBuffer.getInstance().e(TAG, "Error closing UdpForwarder resources", e);
        }
    }

    /**
     * 创建强制绑定 IPv4 (0.0.0.0) 的 DatagramChannel。
     * Android 默认 new DatagramChannel().bind() 会绑定到 IPv6 [::]，
     * 导致某些设备/MIUI 防火墙阻止 IPv4 客户端连接。API 33+ 通过
     * StandardProtocolFamily.INET 强制创建 IPv4 socket，旧设备回退默认。
     */
    private static DatagramChannel openDatagramChannelV4() throws IOException {
        try {
            Class<?> pfClass = Class.forName("java.net.ProtocolFamily");
            Class<?> spfClass = Class.forName("java.net.StandardProtocolFamily");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum inet = Enum.valueOf((Class<? extends Enum>) spfClass, "INET");
            java.lang.reflect.Method open = DatagramChannel.class.getMethod("open", pfClass);
            return (DatagramChannel) open.invoke(null, inet);
        } catch (Exception e) {
            // 旧设备或反射失败：回退默认行为
            return DatagramChannel.open();
        }
    }    static class ClientRecord {
        public SocketAddress toAddress;
        public ByteBuffer writeBuffer = ByteBuffer.allocate(BUFFER_SIZE);

        public ClientRecord(SocketAddress toAddress) {
            this.toAddress = toAddress;
        }
    }

}
