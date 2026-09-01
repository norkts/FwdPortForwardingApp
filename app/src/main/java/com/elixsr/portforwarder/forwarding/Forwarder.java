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

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Channel;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The {@link Forwarder} class represents all details shared by a protocol forwarding class.
 *
 * @author Niall McShane
 */
public abstract class Forwarder implements Callable<Void> {

    /**
     * Message to describe starting of port forwarding thread.
     */
    public static final String START_MESSAGE = "%s Port Forwarding Started from port %s to port %s";

    /**
     * Message to describe a failed binding from a Forwarding class.
     */
    public static final String BIND_FAILED_MESSAGE = "Could not bind port %s for %s Rule '%s'";

    /**
     * Message to describe a thread interruption.
     */
    public static final String THREAD_INTERRUPT_CLEANUP_MESSAGE = "%s Thread interrupted, will perform cleanup";

    /**
     * 全局跟踪所有打开的资源，用于在停止时释放端口
     */
    private static final CopyOnWriteArrayList<Closeable> openResources = new CopyOnWriteArrayList<>();

    /**
     * The from and target {@link InetSocketAddress} objects.
     */
    protected final InetSocketAddress from, to;

    /**
     * The name of the rule being forwarded.
     */
    protected final String ruleName;

    /**
     * The name of the protocol being used to forward.
     */
    protected final String protocol;

    /**
     * 标记转发器是否正在运行
     */
    protected volatile boolean isRunning = true;

    public Forwarder(String protocol, InetSocketAddress form, InetSocketAddress to, String ruleName) {
        this.protocol = protocol;
        this.from = form;
        this.to = to;
        this.ruleName = ruleName;
    }

    /**
     * 注册资源以便在停止时关闭
     */
    protected void registerResource(Closeable resource) {
        openResources.addIfAbsent(resource);
    }

    /**
     * 注销资源
     */
    protected void unregisterResource(Closeable resource) {
        openResources.remove(resource);
    }

    /**
     * 关闭所有打开的资源，释放端口
     */
    public static void closeAllResources() {
        for (Closeable resource : openResources) {
            try {
                if (resource != null) {
                    resource.close();
                }
            } catch (IOException e) {
                // 忽略关闭时的异常
            }
        }
        openResources.clear();
    }

    /**
     * 关闭转发器，释放所有资源和端口
     */
    public abstract void close();

    /**
     * 检查转发器是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
}
