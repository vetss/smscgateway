/*
 * TeleStax, Open Source Cloud Communications  
 * Copyright 2012, Telestax Inc and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
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
package org.mobicents.smsc.smpp;

import io.netty.channel.nio.NioEventLoopGroup;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javolution.util.FastList;

import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.impl.DefaultSmppClient;

/**
 * @author Amit Bhayani
 * 
 */
public class SmppClientManagement implements SmppClientManagementMBean {

	private final EsmeManagement esmeManagement;
	private final SmppSessionHandlerInterface smppSessionHandlerInterface;

	private final String name;

	private NioEventLoopGroup workerGroup;
	private ScheduledThreadPoolExecutor monitorExecutor;

	private DefaultSmppClient clientBootstrap = null;

	private SmppClientOpsThread smppClientOpsThread = null;

	private int expectedSessions = 25;

	/**
	 * 
	 */
	public SmppClientManagement(String name, EsmeManagement esmeManagement,
			SmppSessionHandlerInterface smppSessionHandlerInterface) {
		this.name = name;
		this.esmeManagement = esmeManagement;
		this.smppSessionHandlerInterface = smppSessionHandlerInterface;
	}

	public void start() throws Exception {

		// for monitoring thread use, it's preferable to create your own
		// instance of an executor and cast it to a ThreadPoolExecutor from
		// Executors.newCachedThreadPool() this permits exposing thinks like
		// executor.getActiveCount() via JMX possible no point renaming the
		// threads in a factory since underlying Netty framework does not easily
		// allow you to customize your thread names
        this.workerGroup = new NioEventLoopGroup();
        // (ThreadPoolExecutor) Executors.newCachedThreadPool();

		// to enable automatic expiration of requests, a second scheduled
		// executor is required which is what a monitor task will be executed
		// with - this is probably a thread pool that can be shared with between
		// all client bootstraps
		this.monitorExecutor = (ScheduledThreadPoolExecutor) Executors.newScheduledThreadPool(1, new ThreadFactory() {
			private AtomicInteger sequence = new AtomicInteger(0);

			@Override
			public Thread newThread(Runnable r) {
				Thread t = new Thread(r);
				t.setName("SmppServer-SessionWindowMonitorPool-" + sequence.getAndIncrement());
				return t;
			}
		});

		// a single instance of a client bootstrap can technically be shared
		// between any sessions that are created (a session can go to any
		// different number of SMSCs) - each session created under a client
		// bootstrap will use the executor and monitorExecutor set in its
		// constructor - just be *very* careful with the "expectedSessions"
		// value to make sure it matches the actual number of total concurrent
		// open sessions you plan on handling - the underlying netty library
		// used for NIO sockets essentially uses this value as the max number of
		// threads it will ever use, despite the "max pool size", etc. set on
		// the executor passed in here

		// Setting expected session to be 25. May be this should be
		// configurable?
//        this.clientBootstrap = new DefaultSmppClient(this.workerGroup, 25, monitorExecutor);
        this.clientBootstrap = new DefaultSmppClient(this.workerGroup, monitorExecutor);

		this.smppClientOpsThread = new SmppClientOpsThread(this.clientBootstrap, this.smppSessionHandlerInterface);

		(new Thread(this.smppClientOpsThread)).start();

		FastList<Esme> esmes = this.esmeManagement.esmes;
		for (FastList.Node<Esme> n = esmes.head(), end = esmes.tail(); (n = n.getNext()) != end;) {
			Esme esme = n.getValue();

			if (esme.getSmppSessionType() == SmppSession.Type.CLIENT) {
				this.startSmppClientSession(esme);
			}
		}

	}

	public void stop() throws Exception {
		this.smppClientOpsThread.setStarted(false);
		this.clientBootstrap.destroy();

		this.workerGroup.shutdownGracefully();
		this.monitorExecutor.shutdownNow();
	}

	protected void startSmppClientSession(Esme esme) {
		this.smppClientOpsThread.scheduleConnect(esme);
	}

	protected void stopSmppClientSession(Esme esme) {
		SmppSession smppSession = esme.getSmppSession();
		if (smppSession != null) {
			if (smppSession.isBound()) {
				smppSession.unbind(5000);
			}
			smppSession.close();
			smppSession.destroy();
		}
	}

	@Override
	public String getName() {
		return this.name;
	}

	@Override
	public int getExpectedSessions() {
		return this.expectedSessions;
	}

	@Override
	public void setExpectedSessions(int expectedSessions) {
		this.expectedSessions = expectedSessions;
	}
}
