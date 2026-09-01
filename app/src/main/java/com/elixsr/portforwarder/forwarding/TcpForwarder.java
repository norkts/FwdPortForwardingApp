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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.Callable;

import android.util.Log;
import com.elixsr.portforwarder.util.LogBuffer;

import com.elixsr.portforwarder.exceptions.BindException;

/**
 * Created by Niall McShane on 21/02/2016.
 * <p>
 * Credit: https://alexapps.net/single-threaded-port-forwarding-utility-/
 */
public class TcpForwarder extends Forwarder implements Callable<Void> {

    private static final String TAG = "TcpForwarder";
    private static final int BUFFER_SIZE = 100000;

    private Selector selector;
    private ServerSocketChannel listening;
    public TcpForwarder(InetSocketAddress form, InetSocketAddress to, String ruleName) {
        super("TCP", form, to, ruleName);
    }

    public Void call() throws IOException, BindException {

        LogBuffer.getInstance().d(TAG, String.format(super.START_MESSAGE, protocol, from.getPort(), to.getPort()));

        try {
            selector = Selector.open();

            registerResource(selector);
            ByteBuffer readBuffer = ByteBuffer.allocate(BUFFER_SIZE);

            listening = openServerSocketChannelV4();
            registerResource(listening);            listening.configureBlocking(false);

            try {
                // 强制绑定 IPv4 0.0.0.0，避免默认绑定 IPv6 [::] 导致防火墙/iptables 阻止外部连接
                InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", from.getPort());
                Log.i(TAG, "TCP binding to port " + from.getPort() + " on all interfaces (0.0.0.0)");
                listening.socket().bind(bindAddress, 0);
            } catch (java.net.BindException e) {
                LogBuffer.getInstance().e(TAG, String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
                throw new BindException(String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
            } catch (java.net.SocketException e) {
                LogBuffer.getInstance().e(TAG, String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
                throw new BindException(String.format(super.BIND_FAILED_MESSAGE, from.getPort(), protocol, ruleName), e);
            }

            listening.register(selector, SelectionKey.OP_ACCEPT, listening);
            LogBuffer.getInstance().i(TAG, "TCP listening on port " + from.getPort() + ", forwarding to " + to.getPort());

            try {
                while (true) {

                    if (Thread.currentThread().isInterrupted()) {
                        LogBuffer.getInstance().i(TAG, String.format(super.THREAD_INTERRUPT_CLEANUP_MESSAGE, protocol));
                        break;
                    }

                    int count = selector.select();
                    if (count > 0) {
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {

                            SelectionKey key = it.next();
                            it.remove();

                            if (!key.isValid()) {
                                continue;
                            }

                            // 每个 key 单独处理，异常只关闭该连接，绝不终止 selector 循环
                            try {
                                if (key.isAcceptable()) {
                                    processAcceptable(key, to);
                                } else if (key.isConnectable()) {
                                    processConnectable(key);
                                } else if (key.isReadable()) {
                                    processReadable(key, readBuffer);
                                } else if (key.isWritable()) {
                                    processWritable(key);
                                }
                            } catch (IOException e) {
                                LogBuffer.getInstance().e(TAG, "Error processing connection on port " + from.getPort() + ": " + e.getMessage());
                                closeConnection(key);
                            }
                        }
                    }
                }
            } finally {
                // 确保资源被正确清理
                Log.i(TAG, "TCP cleaning up resources on port " + from.getPort() + " (loop exited, interrupted=" + Thread.currentThread().isInterrupted() + ")");
                close();
            }
        } catch (IOException e) {
            LogBuffer.getInstance().e(TAG, "Problem opening Selector", e);
            throw e;
        }

        return null;
    }

    /**
     * 关闭出错连接的通道，避免单个连接异常终止整个 selector 循环。
     */
    private static void closeConnection(SelectionKey key) {
        try {
            key.cancel();
            if (key.channel() != null) {
                key.channel().close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void registerReads(
            Selector selector,
            SocketChannel socket,
            SocketChannel forwardToSocket) throws ClosedChannelException {        RoutingPair pairFromToPair = new RoutingPair();
        pairFromToPair.from = socket;
        pairFromToPair.to = forwardToSocket;
        pairFromToPair.from.register(selector, SelectionKey.OP_READ, pairFromToPair);

        RoutingPair pairToFromPair = new RoutingPair();
        pairToFromPair.from = forwardToSocket;
        pairToFromPair.to = socket;
        pairToFromPair.from.register(selector, SelectionKey.OP_READ, pairToFromPair);
    }

    private static void processWritable(
            SelectionKey key) throws IOException {

        RoutingPair pair = (RoutingPair) key.attachment();

        pair.writeBuffer.flip();
        pair.to.write(pair.writeBuffer);

        if (pair.writeBuffer.remaining() > 0) {
            pair.writeBuffer.compact();
        } else {
            key.interestOps(SelectionKey.OP_READ);
            pair.writeBuffer.clear();
        }
    }

    private static void processReadable(
            SelectionKey key,
            ByteBuffer readBuffer) throws IOException {

        readBuffer.clear();
        RoutingPair pair = (RoutingPair) key.attachment();

        int r = 0;
        try {
            r = pair.from.read(readBuffer);
        } catch (IOException e) {
            key.cancel();
            System.out.println("Connection closed: " + key.channel());
        }
        if (r <= 0) {
            LogBuffer.getInstance().i(TAG, "TCP connection closed (no data), bytes read: " + r);
            pair.from.close();
            pair.to.close();
            key.cancel();
        } else {
            readBuffer.flip();
            int bytesForwarded = pair.to.write(readBuffer);
            LogBuffer.getInstance().d(TAG, "TCP forwarded " + bytesForwarded + " bytes from " + pair.from.socket().getRemoteSocketAddress() + " to " + pair.to.socket().getRemoteSocketAddress());

            if (readBuffer.remaining() > 0) {
                pair.writeBuffer.put(readBuffer);
                key.interestOps(SelectionKey.OP_WRITE);
            }
        }
    }

    private static void processConnectable(
            SelectionKey key) throws IOException {
        SocketChannel from = (SocketChannel) key.attachment();
        SocketChannel forwardToSocket = (SocketChannel) key.channel();

        forwardToSocket.finishConnect();
        LogBuffer.getInstance().i(TAG, "TCP connection established to " + forwardToSocket.socket().getRemoteSocketAddress());
        forwardToSocket.socket().setTcpNoDelay(true);
        registerReads(key.selector(), from, forwardToSocket);
    }

    private static void processAcceptable(
            SelectionKey key,
            InetSocketAddress forwardToAddress) throws IOException {
        SocketChannel from = ((ServerSocketChannel) key.attachment()).accept();
        Log.i(TAG, "TCP accepted connection from " + from.socket().getRemoteSocketAddress() + " on port " + ((ServerSocketChannel) key.attachment()).socket().getLocalPort());
        LogBuffer.getInstance().i(TAG, "TCP accepted connection from " + from.socket().getRemoteSocketAddress());
        from.socket().setTcpNoDelay(true);
        from.configureBlocking(false);

        SocketChannel forwardToSocket = SocketChannel.open();
        forwardToSocket.configureBlocking(false);

        boolean connected = forwardToSocket.connect(forwardToAddress);
        if (connected) {
            forwardToSocket.socket().setTcpNoDelay(true);
            registerReads(key.selector(), from, forwardToSocket);
        } else {
            forwardToSocket.register(key.selector(), SelectionKey.OP_CONNECT, from);
        }
    }


    @Override
    public void close() {
        isRunning = false;
        try {
            if (selector != null && selector.isOpen()) {
                selector.close();
            }
            if (listening != null && listening.isOpen()) {
                LogBuffer.getInstance().i(TAG, "TCP closing listening socket on port " + from.getPort());
                listening.close();
            }
        } catch (Exception e) {
            LogBuffer.getInstance().e(TAG, "Error closing TcpForwarder resources", e);
        }
    }

    /**
     * 创建强制绑定 IPv4 (0.0.0.0) 的 ServerSocketChannel。
     * Android 默认 new ServerSocketChannel().bind() 会绑定到 IPv6 [::]，
     * 导致某些设备/MIUI 防火墙阻止 IPv4 客户端连接。API 33+ 通过
     * StandardProtocolFamily.INET 强制创建 IPv4 socket，旧设备回退默认。
     */
    private static ServerSocketChannel openServerSocketChannelV4() throws IOException {
        try {
            Class<?> pfClass = Class.forName("java.net.ProtocolFamily");
            Class<?> spfClass = Class.forName("java.net.StandardProtocolFamily");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Enum inet = Enum.valueOf((Class<? extends Enum>) spfClass, "INET");
            java.lang.reflect.Method open = ServerSocketChannel.class.getMethod("open", pfClass);
            return (ServerSocketChannel) open.invoke(null, inet);
        } catch (Exception e) {
            // 旧设备或反射失败：回退默认行为
            return ServerSocketChannel.open();
        }
    }    static class RoutingPair {
        SocketChannel from;
        SocketChannel to;
        ByteBuffer writeBuffer = ByteBuffer.allocate(BUFFER_SIZE);
    }
}
