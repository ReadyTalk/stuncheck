package com.ecovate.rtc.stun;

class StunStats {
  private final String current_latency;
  private final String current_failed;
  private final String current_completed;
  
  private final String total_latency;
  private final String total_failed;
  private final String total_completed;
  
  private final long total_requests;
  
  public StunStats(double cl, double cf,double cc, double tl, double tf, double tc, long tr) {
    
    this.current_latency = String.format("%.4f",cl);
    this.current_failed = String.format("%.4f",cf);
    this.current_completed = String.format("%.4f",cc);
    this.total_latency = String.format("%.4f",tl);
    this.total_failed = String.format("%.4f",tf);
    this.total_completed = String.format("%.4f",tc);;
    this.total_requests = tr;
  }

  public String getCurrent_latency() {
    return current_latency;
  }

  public String getCurrent_failed() {
    return current_failed;
  }

  public String getCurrent_completed() {
    return current_completed;
  }

  public String getTotal_latency() {
    return total_latency;
  }

  public String getTotal_failed() {
    return total_failed;
  }

  public String getTotal_completed() {
    return total_completed;
  }

  public long getTotal_requests() {
    return total_requests;
  }
}