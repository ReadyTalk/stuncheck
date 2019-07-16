package com.ecovate.rtc.stun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.concurrent.future.FutureUtils;
import org.threadly.concurrent.future.ListenableFuture;
import org.threadly.concurrent.future.SettableListenableFuture;
import org.threadly.litesockets.Client;
import org.threadly.litesockets.SocketExecuter;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.UDPClient;
import org.threadly.litesockets.UDPServer;
import org.threadly.litesockets.buffers.MergedByteBuffers;
import org.threadly.litesockets.protocols.stun.StunMessageType;
import org.threadly.litesockets.protocols.stun.StunPacket;
import org.threadly.litesockets.protocols.stun.StunPacketBuilder;
import org.threadly.litesockets.utils.PortUtils;
import org.threadly.test.concurrent.TestCondition;

public class SimpleStunClientTests {

  private PriorityScheduler PS;
  private SocketExecuter SE;
  private UDPServer fakeServer;
  private int udpPort;
  private ConcurrentHashMap<UDPClient, ConcurrentLinkedQueue<ByteBuffer>> clients;

  @Before
  public void start() throws IOException {
    PS = new PriorityScheduler(5);
    SE = new ThreadedSocketExecuter(PS);
    SE.start();
    clients = new ConcurrentHashMap<>();
    udpPort = PortUtils.findUDPPort();
    fakeServer = SE.createUDPServer("127.0.0.1", udpPort);
    fakeServer.setClientAcceptor((c)->accept(c));
    fakeServer.start();
  }

  @After
  public void stop() {
    SE.stop();
    fakeServer.stop();
    PS.shutdownNow();
    clients.clear();
    clients = null;
  }

  @Test
  public void requestTimeout() throws UnknownHostException, IOException, InterruptedException {
    SimpleStunClient ssc = new SimpleStunClient(SE, InetAddress.getByName("127.0.0.1"), 0, InetAddress.getByName("127.0.0.1"), udpPort, 10);
    ssc.start();
    ssc.sendRequest();
    new TestCondition() {
      @Override
      public boolean get() {
        if(clients.size() > 0 && !ssc.hasPendingRequests()) {
          for(UDPClient c: clients.keySet()) {
            if(clients.get(c).size() > 0) {
              return true;
            }
          }
        }
        return false;
      }
    }.blockTillTrue(5000);
    assertTrue(ssc.currentCompletedPCT() == 0.0);
    assertTrue(ssc.currentFailedPCT() == 1.0);
    assertTrue(ssc.currentLatencyAvg() == 0);
    assertTrue(ssc.totalCompletedPCT() == 0.0);
    assertTrue(ssc.totalFailedPCT() == 1.0);
    assertTrue(ssc.totalLatencyAvg() == 0);
    assertTrue(ssc.totalRequests() == 1);

    ssc.resetStats();
    assertTrue(ssc.currentCompletedPCT() == 1.0);
    assertTrue(ssc.currentFailedPCT() == 0.0);
    assertTrue(ssc.currentLatencyAvg() == 0);
    assertTrue(ssc.totalCompletedPCT() == 1.0);
    assertTrue(ssc.totalFailedPCT() == 0.0);
    assertTrue(ssc.totalLatencyAvg() == 0);
    assertTrue(ssc.totalRequests() == 0);
    ssc.stop();
  }

  @Test
  public void badResponse() throws Exception {
    int localPort = PortUtils.findUDPPort();
    SimpleStunClient ssc = new SimpleStunClient(SE, InetAddress.getByName("127.0.0.1"), localPort, InetAddress.getByName("127.0.0.1"), udpPort, 10);
    ssc.start();
    UDPClient uc = fakeServer.createUDPClient("127.0.0.1", localPort);
    final SettableListenableFuture<ByteBuffer> slf = new SettableListenableFuture<>();
    uc.setReader((c)->{
      MergedByteBuffers mbb = c.getRead();
      slf.setResult(mbb.pullBuffer(mbb.remaining()));
    });
    ssc.sendRequest();
    slf.get(5, TimeUnit.SECONDS);
    uc.write(ByteBuffer.wrap("TEST12345".getBytes()));
    new TestCondition() {
      @Override
      public boolean get() {
        if(!ssc.hasPendingRequests()) {
          return true;
        }
        return false;
      }
    }.blockTillTrue(5000);
    assertTrue(ssc.currentCompletedPCT() == 0.0);
    assertTrue(ssc.currentFailedPCT() == 1.0);
    assertTrue(ssc.currentLatencyAvg() == 0);

    ssc.stop();
  }

  @Test
  public void goodResponse() throws Exception {
    int localPort = PortUtils.findUDPPort();
    SimpleStunClient ssc = new SimpleStunClient(SE, InetAddress.getByName("127.0.0.1"), localPort, InetAddress.getByName("127.0.0.1"), udpPort, 10);
    ssc.start();
    UDPClient uc = fakeServer.createUDPClient("127.0.0.1", localPort);
    List<ListenableFuture<StunPacket>> futures = new ArrayList<>();
    for(int i=0; i<50; i++) {
      final SettableListenableFuture<ByteBuffer> slf = new SettableListenableFuture<>();
      uc.setReader((c)->{
        MergedByteBuffers mbb = c.getRead();
        slf.setResult(mbb.pullBuffer(mbb.remaining()));
      });
      StunPacketBuilder spb = new StunPacketBuilder().setType(StunMessageType.SUCCESS);
      spb.setMappedAddress(new InetSocketAddress("127.0.0.1", localPort));
      futures.add(ssc.sendRequest());
      ByteBuffer bb = slf.get(5, TimeUnit.SECONDS);
      StunPacket rsp = new StunPacket(bb);
      spb.setTxID(rsp.getTxID());
      Thread.sleep(10);
      uc.write(spb.build().getBytes());
    }
    FutureUtils.blockTillAllComplete(futures, 5000);

    assertTrue(ssc.currentCompletedPCT() == 1.0);
    assertTrue(ssc.currentFailedPCT() == 0.0);
    assertTrue(ssc.currentLatencyAvg() > 0);

    assertTrue(ssc.totalCompletedPCT() == 1.0);
    assertTrue(ssc.totalFailedPCT() == 0.0);
    assertTrue(ssc.totalLatencyAvg() > 0);
    assertTrue(ssc.totalRequests() == 50);
    ssc.stop();
  }

  @Test
  public void partialResponse() throws Exception {
    int localPort = PortUtils.findUDPPort();
    SimpleStunClient ssc = new SimpleStunClient(SE, InetAddress.getByName("127.0.0.1"), localPort, InetAddress.getByName("127.0.0.1"), udpPort, 10);
    ssc.start();
    assertEquals(1.0, ssc.currentCompletedPCT(), 0.00000);
    UDPClient uc = fakeServer.createUDPClient("127.0.0.1", localPort);
    List<ListenableFuture<StunPacket>> futures = new ArrayList<>();
    for(int i=0; i<50; i++) {
      final SettableListenableFuture<ByteBuffer> slf = new SettableListenableFuture<>();
      uc.setReader((c)->{
        MergedByteBuffers mbb = c.getRead();
        slf.setResult(mbb.pullBuffer(mbb.remaining()));
      });
      StunPacketBuilder spb = new StunPacketBuilder().setType(StunMessageType.SUCCESS);
      spb.setMappedAddress(new InetSocketAddress("127.0.0.1", localPort));
      futures.add(ssc.sendRequest());
      ByteBuffer bb = slf.get(5, TimeUnit.SECONDS);
      StunPacket rsp = new StunPacket(bb);
      spb.setTxID(rsp.getTxID());
      if(i%2 != 0) {
        Thread.sleep(10);
        uc.write(spb.build().getBytes());
      }
    }
    FutureUtils.blockTillAllComplete(futures, 5000);
    System.out.println(ssc.totalRequests());
    System.out.println(ssc.currentLatencyAvg());
    
    assertEquals(0.5, ssc.currentCompletedPCT(), 0.0);
    assertEquals(0.5, ssc.currentFailedPCT(), 0.0);
    assertTrue(ssc.currentLatencyAvg() > 0);

    assertEquals(0.5, ssc.totalCompletedPCT(), 0.0);
    assertEquals(0.5, ssc.totalFailedPCT(), 0.0);
    assertTrue(ssc.totalLatencyAvg() > 0);


    for(int i=0; i<50; i++) {
      final SettableListenableFuture<ByteBuffer> slf = new SettableListenableFuture<>();
      uc.setReader((c)->{
        MergedByteBuffers mbb = c.getRead();
        slf.setResult(mbb.pullBuffer(mbb.remaining()));
      });
      StunPacketBuilder spb = new StunPacketBuilder().setType(StunMessageType.SUCCESS);
      spb.setMappedAddress(new InetSocketAddress("127.0.0.1", localPort));
      futures.add(ssc.sendRequest());
      ByteBuffer bb = slf.get(5, TimeUnit.SECONDS);
      StunPacket rsp = new StunPacket(bb);
      spb.setTxID(rsp.getTxID());
      if(i%4 != 0) {
        Thread.sleep(10);
        uc.write(spb.build().getBytes());
      }
    }
    FutureUtils.blockTillAllComplete(futures, 5000);

    assertEquals(0.70, ssc.currentCompletedPCT(), 0.00001);
    assertEquals(0.30, ssc.currentFailedPCT(), 0.00001);
    assertTrue(ssc.currentLatencyAvg() > 0);

    assertEquals(0.62, ssc.totalCompletedPCT(), 0.0001);
    assertEquals(0.38, ssc.totalFailedPCT(), 0.00001);
    assertTrue(ssc.totalLatencyAvg() > 0);
    assertEquals(ssc.totalRequests(), 100);
    ssc.stop();
  }

  private void accept(Client c) {
    UDPClient uc = (UDPClient) c;
    ConcurrentLinkedQueue<ByteBuffer> cbb = new ConcurrentLinkedQueue<>();
    clients.putIfAbsent(uc, cbb);
    uc.setReader((nc)->read(nc));
  }

  private void read(Client c) {
    MergedByteBuffers mbb = c.getRead();
    ConcurrentLinkedQueue<ByteBuffer> cbb = clients.get(c);
    if(cbb != null) {
      cbb.add(mbb.pullBuffer(mbb.remaining()));
    }
  }
}
