package com.ecovate.rtc.stun;

import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class LoggingConfig {
  public static void configureLogging() {
    try {
      final ConsoleHandler consoleHandler = new ConsoleHandler();
      consoleHandler.setLevel(Level.INFO);
      consoleHandler.setFormatter(new SimpleFormatter());
      final Logger app = Logger.getLogger("HTTPServer");
      app.setLevel(Level.OFF);
      app.addHandler(consoleHandler);
    } catch (Exception e) {
      // The runtime won't show stack traces if the exception is thrown
      e.printStackTrace();
    }
  }
}
