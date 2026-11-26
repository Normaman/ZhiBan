package com.pddcopilot.core.platform.pdd;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Owns the temporary X/VNC processes used to display the Java-managed login browser. */
@Service
public class PddLoginDesktopService {
  public record Desktop(Map<String, String> environment, String password, List<Process> processes,
                        Path directory, Path passwordFile, Path vncAuth, Path xAuthority, int rfbPort) {}

  private final String bindHost;
  private final Path noVncRoot;

  public PddLoginDesktopService(
      @Value("${PDD_LOGIN_VIEWER_BIND_HOST:127.0.0.1}") String bindHost,
      @Value("${NOVNC_WEB_ROOT:/usr/share/novnc}") String noVncRoot) {
    this.bindHost = bindHost;
    this.noVncRoot = Path.of(noVncRoot);
  }

  public Desktop start(Path profile, int viewerPort) {
    return start(profile, viewerPort, true);
  }

  /** Starts a private X display for headful browser work without exposing a noVNC viewer. */
  public Desktop startDisplay(Path profile) {
    return start(profile, 0, false);
  }

  private Desktop start(Path profile, int viewerPort, boolean exposeViewer) {
    Path directory = profile.getParent().getParent().resolve("login_desktops").resolve(profile.getFileName());
    List<Process> processes = new ArrayList<>();
    Path passwordFile = directory.resolve("viewer-password.txt");
    Path vncAuth = directory.resolve("vnc-auth");
    Path xAuthority = directory.resolve("x-authority");
    try {
      if (exposeViewer && !Files.isRegularFile(noVncRoot.resolve("vnc.html")))
        throw new PddPlatformException("LOGIN_DESKTOP_DEPENDENCIES_MISSING");
      if (exposeViewer && (viewerPort < 1024 || viewerPort > 65535))
        throw new PddPlatformException("LOGIN_VIEWER_PORT_INVALID");
      if (exposeViewer) requirePortFree(bindHost, viewerPort);
      Files.createDirectories(directory);
      privateDirectory(directory);
      String password = randomString(8);
      Files.writeString(passwordFile, password, StandardCharsets.UTF_8);
      privateFile(passwordFile);
      byte[] encodedPassword = vncPassword(password);
      if (encodedPassword.length != 8) throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
      Files.write(vncAuth, encodedPassword);
      privateFile(vncAuth);

      int display = availableDisplay();
      Files.write(xAuthority, new byte[0]);
      privateFile(xAuthority);
      runChecked(List.of("xauth", "-f", xAuthority.toString()),
          ("add :" + display + " . " + randomHex(16) + "\n").getBytes(StandardCharsets.UTF_8));
      int rfbPort = freePort();
      Map<String, String> environment = new java.util.HashMap<>(System.getenv());
      environment.put("DISPLAY", ":" + display);
      environment.put("XAUTHORITY", xAuthority.toString());

      Path serverLog = directory.resolve("desktop.log");
      ProcessBuilder serverBuilder = new ProcessBuilder(
          "Xtigervnc", ":" + display, "-geometry", "1440x1000", "-depth", "24",
          "-auth", xAuthority.toString(), "-nolisten", "tcp", "-localhost", "yes",
          "-interface", "127.0.0.1", "-rfbport", Integer.toString(rfbPort),
          "-SecurityTypes", "VncAuth", "-rfbauth", vncAuth.toString(), "-AlwaysShared");
      serverBuilder.environment().putAll(environment);
      serverBuilder.redirectErrorStream(true).redirectOutput(serverLog.toFile());
      Process server = serverBuilder.start();
      processes.add(server);
      awaitPort(rfbPort, server);

      if (exposeViewer) {
        Path webRoot = createWebRoot(directory.resolve("web"));
        Path proxyLog = directory.resolve("viewer.log");
        ProcessBuilder proxyBuilder = new ProcessBuilder(
            "websockify", "--web", webRoot.toString(), "--heartbeat", "30",
            bindHost + ":" + viewerPort, "127.0.0.1:" + rfbPort);
        proxyBuilder.redirectErrorStream(true).redirectOutput(proxyLog.toFile());
        Process proxy = proxyBuilder.start();
        processes.add(proxy);
        awaitPort(viewerPort, proxy);
      }
      return new Desktop(Map.copyOf(environment), exposeViewer ? password : "", List.copyOf(processes), directory,
          passwordFile, vncAuth, xAuthority, rfbPort);
    } catch (PddPlatformException error) {
      stop(processes);
      cleanup(directory, passwordFile, vncAuth, xAuthority);
      throw error;
    } catch (Exception error) {
      stop(processes);
      cleanup(directory, passwordFile, vncAuth, xAuthority);
      throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
    }
  }

  public void close(Desktop desktop) {
    if (desktop == null) return;
    stop(desktop.processes());
    cleanup(desktop.directory(), desktop.passwordFile(), desktop.vncAuth(), desktop.xAuthority());
  }

  public Desktop exposeViewer(Desktop desktop, int viewerPort) {
    if (!desktop.password().isEmpty()) return desktop;
    try {
      requirePortFree(bindHost, viewerPort);
      Path webRoot = createWebRoot(desktop.directory().resolve("web"));
      ProcessBuilder builder = new ProcessBuilder("websockify", "--web", webRoot.toString(),
          "--heartbeat", "30", bindHost + ":" + viewerPort, "127.0.0.1:" + desktop.rfbPort());
      builder.redirectErrorStream(true).redirectOutput(desktop.directory().resolve("viewer.log").toFile());
      Process proxy = builder.start();
      try { awaitPort(viewerPort, proxy); }
      catch (Exception failure) { proxy.destroyForcibly(); throw failure; }
      List<Process> processes = new ArrayList<>(desktop.processes());
      processes.add(proxy);
      return new Desktop(desktop.environment(), Files.readString(desktop.passwordFile()),
          List.copyOf(processes), desktop.directory(), desktop.passwordFile(), desktop.vncAuth(),
          desktop.xAuthority(), desktop.rfbPort());
    } catch (Exception error) {
      throw new PddPlatformException("LOGIN_VIEWER_UNAVAILABLE");
    }
  }

  public Desktop hideViewer(Desktop desktop) {
    if (desktop == null || desktop.password().isEmpty()) return desktop;
    List<Process> processes = new ArrayList<>(desktop.processes());
    Process proxy = processes.remove(processes.size() - 1);
    stop(List.of(proxy));
    return new Desktop(desktop.environment(), "", List.copyOf(processes), desktop.directory(),
        desktop.passwordFile(), desktop.vncAuth(), desktop.xAuthority(), desktop.rfbPort());
  }

  private Path createWebRoot(Path webRoot) throws Exception {
    Files.createDirectories(webRoot);
    try (var entries = Files.list(noVncRoot)) {
      entries.filter(path -> !path.getFileName().toString().equals("index.html"))
          .forEach(source -> {
            try {
              Path target = webRoot.resolve(source.getFileName());
              if (!Files.exists(target)) Files.createSymbolicLink(target, source.toAbsolutePath());
            } catch (Exception error) {
              throw new IllegalStateException(error);
            }
          });
    }
    Files.writeString(webRoot.resolve("index.html"), "<!doctype html><meta charset=\"utf-8\">"
        + "<meta http-equiv=\"refresh\" content=\"0;url=vnc.html?autoconnect=true&amp;resize=scale\">"
        + "<title>拼多多登录桌面</title><p>正在打开登录桌面…</p>", StandardCharsets.UTF_8);
    return webRoot;
  }

  private static byte[] vncPassword(String password) throws Exception {
    Process process = new ProcessBuilder("tigervncpasswd", "-f").redirectErrorStream(true).start();
    try (OutputStream input = process.getOutputStream()) {
      input.write((password + "\n").getBytes(StandardCharsets.UTF_8));
    }
    byte[] result = process.getInputStream().readAllBytes();
    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
      throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
    return result;
  }

  private static void runChecked(List<String> command, byte[] input) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    try (OutputStream stream = process.getOutputStream()) { stream.write(input); }
    process.getInputStream().transferTo(OutputStream.nullOutputStream());
    if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0)
      throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
  }

  private static void requirePortFree(String host, int port) {
    try (ServerSocket socket = new ServerSocket()) {
      socket.setReuseAddress(false);
      socket.bind(new java.net.InetSocketAddress(host, port));
    } catch (Exception error) {
      throw new PddPlatformException("LOGIN_VIEWER_PORT_BUSY");
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
      return socket.getLocalPort();
    }
  }

  private static int availableDisplay() {
    for (int display = 90; display < 190; display++) {
      if (!Files.exists(Path.of("/tmp/.X" + display + "-lock"))
          && !Files.exists(Path.of("/tmp/.X11-unix/X" + display))) return display;
    }
    throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
  }

  private static void awaitPort(int port, Process process) throws Exception {
    for (int i = 0; i < 80; i++) {
      if (!process.isAlive()) throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
      try (Socket socket = new Socket("127.0.0.1", port)) { return; }
      catch (Exception ignored) { Thread.sleep(100); }
    }
    throw new PddPlatformException("LOGIN_DESKTOP_START_FAILED");
  }

  private static byte[] randomBytes(int count) {
    byte[] bytes = new byte[count];
    new SecureRandom().nextBytes(bytes);
    return bytes;
  }

  private static String randomHex(int bytes) { return java.util.HexFormat.of().formatHex(randomBytes(bytes)); }

  private static String randomString(int length) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    byte[] bytes = randomBytes(length);
    StringBuilder value = new StringBuilder(length);
    for (byte item : bytes) value.append(alphabet.charAt(Byte.toUnsignedInt(item) % alphabet.length()));
    java.util.Arrays.fill(bytes, (byte) 0);
    return value.toString();
  }

  private static void stop(List<Process> processes) {
    for (int i = processes.size() - 1; i >= 0; i--) {
      Process process = processes.get(i);
      process.destroy();
      try {
        if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
  }

  private static void cleanup(Path directory, Path... files) {
    for (Path path : files) {
      if (path != null) try { Files.deleteIfExists(path); } catch (Exception ignored) { }
    }
    if (directory != null) {
      if (Files.exists(directory)) {
        try (var paths = Files.walk(directory)) {
          paths.sorted(Comparator.reverseOrder()).forEach(path -> {
            try { Files.deleteIfExists(path); } catch (Exception ignored) { }
          });
        } catch (Exception ignored) { }
      }
    }
  }

  private static void privateDirectory(Path directory) {
    try { Files.setPosixFilePermissions(directory,
        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")); }
    catch (Exception ignored) { }
  }

  private static void privateFile(Path file) {
    try { Files.setPosixFilePermissions(file,
        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
    catch (Exception ignored) { }
  }
}
