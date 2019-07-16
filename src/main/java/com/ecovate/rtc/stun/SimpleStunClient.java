package com.ecovate.rtc.stun;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.future.FutureCallback;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SingleThreadSocketExecuter;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.UDPClient;
import org.threadly.litesockets.UDPServer;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.util.AbstractService;
import org.threadly.util.Clock;

import io.prometheus.client.Histogram;
import io.prometheus.client.Histogram.Timer;

import org.threadly.litesockets.protocols.stun.StunMessageType;
import org.threadly.litesockets.protocols.stun.StunPacket;
import org.threadly.litesockets.protocols.stun.StunPacketBuilder;
import org.threadly.litesockets.protocols.stun.StunProtocolException;
import org.threadly.litesockets.protocols.stun.TransactionID;

public class SimpleStunClient extends AbstractService {
  
  private static final Histogram stunRequestLatency = Histogram.build()
      .name("stun_requests_latency_seconds")
      .help("Stun Request latency in seconds.")
      .exponentialBuckets(.002, 2, 12)
      .labelNames("ip")
      .register();
  
  public final Logger log;

  private final ConcurrentHashMap<TransactionID, RequestWrapper> pendingRequests = new ConcurrentHashMap<>();
  private final ConcurrentLinkedQueue<TransactionID> tList = new ConcurrentLinkedQueue<>();
  private final LongAdder requests = new LongAdder();
  private final LongAdder completedRequests = new LongAdder();
  private final LongAdder success = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder latency = new LongAdder();
  private final SocketExecuter se;
  private final UDPServer server;
  private final UDPClient client;
  private final InetAddress bindAddress;
  private final int bindPort;
  private final InetAddress remoteAddress;
  private final int remotePort;
  private final int currentStats;


  public SimpleStunClient(SocketExecuter se, 
      InetAddress bindAddress, 
      int bindPort, 
      InetAddress remoteAddress, 
      int remotePort, 
      int currentStats) throws IOException {
    
    this.se = se;
    this.bindAddress = bindAddress;
    this.bindPort = bindPort;
    this.remoteAddress = remoteAddress;
    this.remotePort = remotePort;
    this.currentStats = currentStats;
    se.startIfNotStarted();
    server = se.createUDPServer(this.bindAddress.getHostAddress(), this.bindPort);
    client = server.createUDPClient(this.remoteAddress.getHostAddress(), this.remotePort);
    client.setReader((c)->onRead(c));
    log = LoggerFactory.getLogger(this.bindAddress.getHostAddress()+":"+
        this.bindPort+"->"+this.remoteAddress.getHostAddress()+":"+
        this.remotePort);
  }


  @Override
  protected void startupService() {
    server.start();
  }

  @Override
  protected void shutdownService() {
    server.close();
    client.close();
    resetStats();
    pendingRequests.clear();
    tList.clear();
  }

  public void resetStats() {
    pendingRequests.clear();
    requests.reset();
    success.reset();
    failed.reset();
    tList.clear();
  }

  private void onRead(Client c) {
    MergedByteBuffers mbb = client.getRead();
    ByteBuffer bb = mbb.pullBuffer(mbb.remaining());
    try {
      final StunPacket sp = new StunPacket(bb);
      RequestWrapper rw = pendingRequests.get(sp.getTxID());
      if(rw != null && !rw.rfailed) {
        long rtt = Clock.accurateForwardProgressingMillis() - rw.startTime;
        latency.add(rtt);
        completedRequests.increment();
        rw.complete(sp);
        log.info("CompletedRequest:{}",byteArrayToHex(sp.getTxID().getArray()));
      }
    } catch (StunProtocolException e) {
      log.error("Bad UDP response.", e);
    }
  }

  public ListenableFuture<StunPacket> sendRequest() {
    if(isRunning()) {
      try {
        StunPacket sp = new StunPacketBuilder().setType(StunMessageType.REQUEST).build();
        RequestWrapper rw = new RequestWrapper(sp, remoteAddress.getHostAddress());
        requests.increment();
        tList.add(sp.getTxID());
        pendingRequests.put(sp.getTxID(), rw);
        client.write(sp.getBytes());
        rw.watch(1000);
        log.info("SentRequest:{}",byteArrayToHex(sp.getTxID().getArray()));
        while(tList.size() > currentStats) {
          TransactionID tid = tList.poll();
          pendingRequests.remove(tid);
        }
        return rw.future;
      } catch (StunProtocolException e) {
        log.error("Error sending stun packet.", e);
        return FutureUtils.immediateFailureFuture(e);
      }
    } else {
      return FutureUtils.immediateFailureFuture(new Exception(SimpleStunClient.class.getSimpleName()+" is Not running!"));
    }
  }

  public boolean hasPendingRequests() {
    for(RequestWrapper rw: pendingRequests.values()) {
      if(!rw.done && !rw.rfailed) {
        return true;
      }
    }
    return false;
  }

  public void logStats() {
    double latency = this.latency.sum()/(double)completedRequests.sum();
    double completed = (completedRequests.sum()/(double)requests.sum())*100;
    if(completedRequests.sum() == 0) {
      completed = 0.0;
    }
    log.info("STATS:\n"+
        "Total Requests:{}\n"+
        "Completed Requests:{}\n"+
        "Missed Requests:{}\n"+
        "PCT Completed:{}%\n"+
        "Avg Latency:{}ms", 
        this.requests.sum(), 
        this.completedRequests.sum(), 
        this.failed.sum(),
        completed,
        latency);
  }
  
  private List<RequestWrapper> getCurrentList() {
    ArrayList<TransactionID> copy = new ArrayList<>(tList);
    Collections.reverse(copy);
    ArrayList<RequestWrapper> last = new ArrayList<>();
    for(TransactionID tid: copy) {
      RequestWrapper rw = pendingRequests.get(tid);
      last.add(rw);
      if(last.size() == currentStats) {
        return last;
      }
    }
    return last;
  }

  public double currentLatencyAvg() {
    List<RequestWrapper> last = getCurrentList();
    int count = 0;
    long total = 0;
    for(RequestWrapper rw: last) {
      if(rw.done) {
        total+=rw.endTime-rw.startTime;
        count++;
      }
    }
    if(total == 0) {
      return 0;
    }
    return total/(double)count;
  }

  public double currentCompletedPCT() {
    List<RequestWrapper> last = getCurrentList();
    int done = 0;
    int failed = 0;
    for(RequestWrapper rw: last) {
      if(rw.done) {
        done++;
      } else if(rw.rfailed) {
        failed++;
      }
    }
    if(done+failed == 0) {
      return 1.0;
    }
    return (done/(double)(done+failed));
  }

  public double currentFailedPCT() {
    return 1.0-currentCompletedPCT();
  }

  public double totalLatencyAvg() {
    if(completedRequests.sum() == 0) {
      return 0;
    }
    return this.latency.sum()/(double)completedRequests.sum();
  }

  public double totalCompletedPCT() {
    if(requests.sum() == 0) {
      return 1;
    }
    return (completedRequests.sum()/(double)requests.sum());
  }

  public double totalFailedPCT() {
    return 1.0-totalCompletedPCT();
  }
  
  public long totalRequests() {
    return requests.sum();
  }

  private class RequestWrapper {
    private final StunPacket request;
    private final long startTime;
    private final Timer pTimer;
    private final SettableListenableFuture<StunPacket> future = new SettableListenableFuture<StunPacket>(false);
    private final SettableListenableFuture<Boolean> watched = new SettableListenableFuture<Boolean>(false);
    private volatile long endTime = -1;
    private volatile boolean done = false;
    private volatile boolean rfailed = false;

    RequestWrapper(StunPacket request, String ipAdder) {
      this.request = request;
      this.startTime = Clock.accurateForwardProgressingMillis();
      this.pTimer = stunRequestLatency.labels(ipAdder).startTimer();
    }

    private void complete(StunPacket sp) {
      log.info("latency:{}", pTimer.observeDuration());
      watched.setResult(true);
      done = true;
      endTime = Clock.lastKnownForwardProgressingMillis();
      future.setResult(sp);
    }

    private void cancel() {
      RequestWrapper rw = pendingRequests.get(request.getTxID());
      if(rw != null) {
        log.info("Failed Request:{}", byteArrayToHex(rw.request.getTxID().getArray()));
        rfailed = true;
        failed.increment();
      }
      if(!future.isDone()) {
        future.cancel(false);
      }
    }

    private void watch(long timeout) {

      watched.callback(new FutureCallback<Boolean>() {

        @Override
        public void handleResult(Boolean result) {

        }

        @Override
        public void handleFailure(Throwable t) {
          cancel();
        }});
      se.watchFuture(watched, timeout);
    }
  }

  public static String byteArrayToHex(byte[] a) {
    StringBuilder sb = new StringBuilder(a.length * 2);
    for(byte b: a)
      sb.append(String.format("%02x", b));
    return sb.toString();
  }

  public static void main(String[] args) throws IOException, InterruptedException {

    SimpleStunClient sc = new SimpleStunClient(new SingleThreadSocketExecuter(), InetAddress.getByName("0.0.0.0"), 0, InetAddress.getByName("13.57.246.122"), 30478, 100);
    sc.start();
    for(int i=0; i<100; i++) {
      sc.sendRequest();
      Thread.sleep(10);
    }
    while(sc.hasPendingRequests()) {
      Thread.sleep(100);
    }
    sc.logStats();
  }

}
