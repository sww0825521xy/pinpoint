/*
 * Copyright 2019 NAVER Corp.
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

package com.navercorp.pinpoint.profiler.sender.grpc;

import com.google.protobuf.Empty;
import com.google.protobuf.GeneratedMessageV3;
import com.navercorp.pinpoint.common.util.Assert;
import com.navercorp.pinpoint.common.util.ExecutorFactory;
import com.navercorp.pinpoint.common.util.PinpointThreadFactory;
import com.navercorp.pinpoint.gpc.trace.PSpan;
import com.navercorp.pinpoint.gpc.trace.PSpanChunk;
import com.navercorp.pinpoint.gpc.trace.TraceGrpc;
import com.navercorp.pinpoint.grpc.AgentHeaderFactory;
import com.navercorp.pinpoint.grpc.client.ChannelFactory;
import com.navercorp.pinpoint.grpc.HeaderFactory;
import com.navercorp.pinpoint.profiler.context.thrift.MessageConverter;
import com.navercorp.pinpoint.profiler.sender.DataSender;
import io.grpc.ManagedChannel;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author Woonduk Kang(emeroad)
 */
public class GrpcDataSender implements DataSender<Object> {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    private final String name;
    private final ManagedChannel managedChannel;
    private final TraceGrpc.TraceStub traceStub;

    private final StreamObserver<PSpan> spanStream;
    private final StreamObserver<PSpanChunk> spanChunkStream;

    private MessageConverter<GeneratedMessageV3> messageConverter;

    private final ThreadPoolExecutor executor;

    private final HeaderFactory<AgentHeaderFactory.Header> headerFactory;

    private ThreadPoolExecutor newExecutorService(String name) {
        ThreadFactory threadFactory = new PinpointThreadFactory(name);
        return ExecutorFactory.newFixedThreadPool(1, 1000, threadFactory);
    }

    public GrpcDataSender(String name, String host, int port, MessageConverter<GeneratedMessageV3> messageConverter, HeaderFactory<AgentHeaderFactory.Header> headerFactory) {
        this.name = Assert.requireNonNull(name, "name must not be null");
        this.messageConverter = Assert.requireNonNull(messageConverter, "messageConverter must not be null");

        this.managedChannel = newChannel(name, host, port);

        this.traceStub = TraceGrpc.newStub(managedChannel);
        this.spanStream = traceStub.sendSpan(response);
        this.spanChunkStream = traceStub.sendSpanChunk(response);

        this.executor = newExecutorService(name);
        this.headerFactory = Assert.requireNonNull(headerFactory, "headerFactory must not be null");
    }

    private ManagedChannel newChannel(String name, String host, int port) {
        ChannelFactory channelFactory = new ChannelFactory(name, host, port, headerFactory);
        return channelFactory.build();
    }



    @Override
    public boolean send(final Object data) {
        final Runnable command = new Runnable() {
            @Override
            public void run() {
                send0(data);
            }
        };
        try {
            executor.execute(command);
        } catch (RejectedExecutionException reject) {
            logger.debug("reject:{}", command);
            return false;
        }
        return true;
    }

    private boolean send0(Object data) {
        final GeneratedMessageV3 spanMessage = messageConverter.toMessage(data);
        if (spanMessage instanceof PSpanChunk) {
            final PSpanChunk pSpan = (PSpanChunk) spanMessage;
            spanChunkStream.onNext(pSpan);
            return true;
        }
        if (spanMessage instanceof PSpan) {
            final  PSpan pSpan = (PSpan) spanMessage;
            spanStream.onNext(pSpan);
            return true;
        }
        throw new IllegalStateException("unsupported message " + data);
    }


    @Override
    public void stop() {

        spanStream.onCompleted();
        spanChunkStream.onCompleted();
        if (this.managedChannel != null) {
            this.managedChannel.shutdown();
        }
        if (executor != null) {
            this.executor.shutdown();
            try {
                this.executor.awaitTermination(1000*3, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore
                Thread.currentThread().interrupt();
            }
        }
    }


    public StreamObserver<Empty> response = new StreamObserver<Empty>() {
        @Override
        public void onNext(Empty value) {
            logger.debug("[{}] onNext:{}", name, value);
        }

        @Override
        public void onError(Throwable t) {
            logger.info("{} onError:{}", name, t);
        }

        @Override
        public void onCompleted() {
            logger.debug("{} onCompleted", name);
        }
    };
}
