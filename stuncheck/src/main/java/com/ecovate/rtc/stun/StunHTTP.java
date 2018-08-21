package com.ecovate.rtc.stun;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.threadly.concurrent.PriorityScheduler;
import org.threadly.litesockets.ThreadedSocketExecuter;
import org.threadly.litesockets.protocols.http.request.HTTPRequest;
import org.threadly.litesockets.protocols.http.response.HTTPResponse;
import org.threadly.litesockets.protocols.http.response.HTTPResponseBuilder;
import org.threadly.litesockets.protocols.http.shared.HTTPConstants;
import org.threadly.litesockets.protocols.http.shared.HTTPResponseCode;
import org.threadly.litesockets.server.http.HTTPServer;
import org.threadly.litesockets.server.http.HTTPServer.BodyFuture;
import org.threadly.litesockets.server.http.HTTPServer.ResponseWriter;
import org.threadly.util.Clock;
import org.threadly.util.ExceptionUtils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.inf.Argument;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;

public class StunHTTP {
  private static final Logger log = LoggerFactory.getLogger(StunHTTP.class);
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final HTTPResponse BAD_RESPONSE = new HTTPResponseBuilder().setHeader(HTTPConstants.HTTP_KEY_CONNECTION, "close").setResponseCode(HTTPResponseCode.BadRequest).build();
  private static final HTTPResponse SimpleResponse = new HTTPResponseBuilder().setHeader(HTTPConstants.HTTP_KEY_CONNECTION, "close").setResponseCode(HTTPResponseCode.OK).build();

  private final PriorityScheduler PS = new PriorityScheduler(3);
  private final ThreadedSocketExecuter tse = new ThreadedSocketExecuter(PS, 100, 1);
  private final ConcurrentHashMap<InetSocketAddress, SimpleStunClient> clientList = new ConcurrentHashMap<>();
  private final InetSocketAddress listenAddress;
  private final HTTPServer httpServer;
  private final List<InetSocketAddress> remoteStunServers;
  private final Runnable checkDNSRunner = ()->checkDNS();
  private final Runnable doChecksRunner = ()->doChecks();
  private final Runnable statusRunner = ()->updateStats();
  private final int delay;
  private final int cached;
  private final int maxLatency;
  private final double failed;
  private volatile StunResponse response;
  private volatile long lastBad = Clock.lastKnownForwardProgressingMillis()-120000;

  public StunHTTP(InetSocketAddress listenAddress, List<InetSocketAddress> remoteStunServers, int delay, int cached, int maxLatency, double failed) throws IOException {
    this.tse.start();
    this.remoteStunServers = remoteStunServers;
    this.listenAddress = listenAddress;
    if(delay >= 1000) {
      this.delay = delay;
    } else {
      this.delay = 5000;
    }
    if(maxLatency > 20) {
      this.maxLatency = maxLatency;
    } else {
      this.maxLatency = 20;
    }
    if(cached > 5) {
      this.cached = cached;
    } else {
      this.cached = 5;
    }
    if(failed > 0.0) {
      this.failed = failed;
    } else {
      this.failed = 0.0;
    }
    this.response = new StunResponse(
        new HTTPResponseBuilder()
        .setHeader(HTTPConstants.HTTP_KEY_CONTENT_LENGTH, Integer.toString(0))
        .setHeader(HTTPConstants.HTTP_KEY_CONNECTION, "close")
        .setResponseCode(HTTPResponseCode.OK)
        .build(),
        "");
    this.httpServer = new HTTPServer(tse, listenAddress.getAddress().getHostAddress(), listenAddress.getPort());
    this.httpServer.addHandler((x,y,z)->handler(x,y,z));
    this.httpServer.start();
    checkDNSRunner.run();
    PS.scheduleAtFixedRate(statusRunner, 1000, 1000);
    PS.scheduleAtFixedRate(checkDNSRunner, 7000, 5000);
    PS.scheduleAtFixedRate(doChecksRunner, 500, this.delay);
    log.info("Server Started.");
  }

  private void handler(HTTPRequest httpRequest, ResponseWriter rw, BodyFuture bodyListener) {
    final String path = httpRequest.getHTTPRequestHeader().getRequestPath();
    log.info("Got HTTPRequest:{}", httpRequest.toString().replaceAll("\r\n", "\\\\r\\\\n"));
    if(path.equals("/status")) {
      rw.closeOnDone();
      rw.sendHTTPResponse(SimpleResponse);
      rw.done();
    } else if(path.equals("/stun_status")) {
      rw.closeOnDone();
      rw.sendHTTPResponse(response.response);
      rw.writeBody(ByteBuffer.wrap(response.body.getBytes()));
      rw.done();
    } else {
      rw.closeOnDone();
      rw.sendHTTPResponse(BAD_RESPONSE);
      rw.done();
    }
  }

  private void updateStats() {
    HashMap<InetSocketAddress, StunStats> tmp = new HashMap<>();
    HTTPResponseCode rc = HTTPResponseCode.OK;
    for(Map.Entry<InetSocketAddress, SimpleStunClient> map: clientList.entrySet()) {
      SimpleStunClient ssc = map.getValue();
      if(ssc.currentLatencyAvg() > maxLatency || ssc.currentFailedPCT() > failed) {
        lastBad = Clock.lastKnownForwardProgressingMillis();
      }
      StunStats s = new StunStats(ssc.currentLatencyAvg(), ssc.currentFailedPCT(), ssc.currentCompletedPCT(), 
          ssc.totalLatencyAvg(), 
          ssc.totalFailedPCT(),
          ssc.totalCompletedPCT(),
          ssc.totalRequests());
      tmp.put(map.getKey(), s);
    }
    String body = GSON.toJson(tmp);
    if(Clock.lastKnownForwardProgressingMillis()-lastBad < 120000) {
      rc = HTTPResponseCode.InternalServerError;
    }

    response = new StunResponse(
        new HTTPResponseBuilder()
        .setHeader(HTTPConstants.HTTP_KEY_CONTENT_LENGTH, Integer.toString(body.length()))
        .setHeader(HTTPConstants.HTTP_KEY_CONNECTION, "close")
        .setResponseCode(rc)
        .build(),
        body);
  }

  private void doChecks() {
    for(SimpleStunClient ssc: clientList.values()) {
      ssc.sendRequest();
    }
  }

  private void checkDNS() {
    Set<InetSocketAddress> ias = new HashSet<InetSocketAddress>();
    for(InetSocketAddress isa: remoteStunServers) {
      try { 
        for(InetAddress ia: InetAddress.getAllByName(isa.getHostString())) {
          ias.add(new InetSocketAddress(ia, isa.getPort()));
        }
      } catch (UnknownHostException e) {
        log.error("Problem looking up address for:{}\n{}", ias, ExceptionUtils.stackToString(e));
      }
    }
    for(InetSocketAddress ia: ias) {
      if(!clientList.containsKey(ia)) {
        try {
          SimpleStunClient ssc = new SimpleStunClient(tse, listenAddress.getAddress(), 0, ia.getAddress(), ia.getPort(), cached);
          ssc.start();
          if(clientList.putIfAbsent(ia, ssc) == null) {
            log.info("Added new StunClient:{}:{}", ia, ia.getPort());
            for(int i=0; i<cached; i+=1) {
              PS.schedule(()->ssc.sendRequest(), i*50);
            }
          }
        } catch (IOException e) {
          log.error("Problem looking up address for:{}\n{}", ias, ExceptionUtils.stackToString(e));
        }
      }
    }
    Set<InetSocketAddress> clcs = new HashSet<>(clientList.keySet());
    for(InetSocketAddress ia: clcs) {
      if(!ias.contains(ia)) {
        log.info("Removed StunClient:{}:{}", ia, ia.getPort());
        SimpleStunClient ssc = clientList.remove(ia);
        ssc.stop();
      }
    }
  }

  private static class StunResponse {
    HTTPResponse response;
    String body;

    public StunResponse(HTTPResponse r, String body) {
      this.response = r;
      this.body = body;
    }
  }

  public static void main(String[] args) throws IOException, InterruptedException {
    LoggingConfig.configureLogging();
    
    String env_servers = System.getenv("STUN_SERVERS");
    String env_listen = System.getenv("STUN_LISTEN_ADDRESS");
    Integer env_delay = null;
    if(System.getenv("STUN_DELAY") != null) {
      try {
        env_delay = Integer.parseInt(System.getenv("STUN_DELAY"));
      }catch(Exception e) {}
    }
    Integer env_latency = null;
    if(System.getenv("STUN_MAX_LATENCY") != null) {
      try {
        env_latency = Integer.parseInt(System.getenv("STUN_MAX_LATENCY"));
      }catch(Exception e) {}
    }
    Double env_failpct = null;
    if(System.getenv("STUN_FAILPCT") != null) {
      try {
        env_failpct = Double.parseDouble(System.getenv("STUN_FAILPCT"));
      }catch(Exception e) {}
    }
    Integer env_cached = null;
    if(System.getenv("STUN_CACHED_RESULTS") != null) {
      try {
        env_cached = Integer.parseInt(System.getenv("STUN_CACHED_RESULTS"));
      }catch(Exception e) {}
    }

    ArgumentParser parser = ArgumentParsers.newFor("StunHTTP").build()
        .defaultHelp(true)
        .description("Tests Stun Server and reports stats");
    Argument arg_servers = parser.addArgument("--stun_servers")
        .required(true)
        .help("Stun servers to check (ie. stun.test.com:2234,stun.test2.com:3322)");
    Argument arg_listen = parser.addArgument("--listen_address")
        .required(true)
        .help("The IP/port to have the http service listen on (ie. 127.0.0.1:8080)  Note this IP will also be used to bind to for the stun requests!");
    Argument arg_delay = parser.addArgument("-d", "--delay")
        .type(Integer.class)
        .required(false)
        .setDefault(5)
        .help("Delay in seconds between checks (each host will be checked this often)");
    Argument arg_latency = parser.addArgument("--max_latency")
        .type(Integer.class)
        .required(false)
        .setDefault(100)
        .help("Delay in milliseconds before a request is considered to be timed out.");
    Argument arg_failpct = parser.addArgument("--maxFailurePCT")
        .type(Double.class)
        .required(false)
        .setDefault(0.10)
        .help("Toleratable failurePCT befor returning a 500 0.0 - 1.0 (Default: 0.10");
    Argument arg_cached = parser.addArgument("--cachedResults")
        .type(Integer.class)
        .required(false)
        .setDefault(100)
        .help("Number of results to keep cached for checking (Default: 100)");
    if(env_servers != null) {
      arg_servers.required(false);
      arg_servers.setDefault(env_servers);
    }
    
    if(env_listen != null) {
      arg_listen.required(false);
      arg_listen.setDefault(env_listen);
    }
    if(env_latency != null) {
      arg_latency.setDefault(env_latency);
    }
    if(env_delay != null) {
      arg_delay.setDefault(env_delay);
    }
    if(env_failpct != null) {
      arg_failpct.setDefault(env_failpct);
    }
    if(env_cached != null) {
      arg_cached.setDefault(env_cached);
    }
    Namespace res = null;
    try {
      res = parser.parseArgs(args);
    } catch (ArgumentParserException e) {
      parser.handleError(e);
      System.exit(1);
    }

    final String servers = res.getString("stun_servers");
    final String listen = res.getString("listen_address");
    int tmp_delay = res.getInt("delay");
    if(tmp_delay < 1) {
      tmp_delay = 1;
    } else if(tmp_delay > 120) {
      tmp_delay = 120;
    }
    final int delay = tmp_delay;
    final int latency = res.getInt("max_latency");
    double tmp_failures = res.getDouble("maxFailurePCT");
    if(tmp_failures < .001) {
      tmp_failures = .001;
    } else if (tmp_failures > 1) {
      tmp_failures = .9999;
    }
    final double failures = tmp_failures;
    int tmp_cached = res.getInt("cachedResults");
    if(tmp_cached < 10) {
      tmp_cached = 10;
    } else if(tmp_cached > 10000) {
      tmp_cached = 10000;
    }
    final int cached = tmp_cached;
    
    log.info("Starting Service with the following arguments:\nservers:{}\nlisten:{}\ndelay:{}\nlatency:{}\nfailures:{}\ncached:{}", servers, listen, delay, latency, failures, cached);
    
    final InetSocketAddress listen_addr = new InetSocketAddress(listen.split(":")[0],Integer.parseInt(listen.split(":")[1]));
    final List<InetSocketAddress> ra = new ArrayList<>();
    for(String server: servers.split(",")) {
      if(server.contains(":")) {
        String[] tmp = server.split(":");
        ra.add(new InetSocketAddress(tmp[0], Integer.valueOf(tmp[1])));
      } else {
        ra.add(new InetSocketAddress(server, 3478));
      }
    }

    StunHTTP H = new StunHTTP(listen_addr, ra, delay*1000, cached, latency, failures);
    while(true) {
      Thread.sleep(10000000);
    }
  }
}
