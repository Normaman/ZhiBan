package com.pddcopilot.core.support;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

/** Only configured network peers can supply a forwarded client address. */
@Component
public class ClientAddress {
  private final List<IpAddressMatcher> proxies;
  public ClientAddress(@Value("${copilot.auth-trusted-proxy-cidrs:}") String ranges) {
    proxies=Arrays.stream(ranges.split(",")).map(String::trim).filter(s -> !s.isEmpty())
        .map(IpAddressMatcher::new).toList();
  }
  public String resolve(HttpServletRequest request) {
    String address=request.getRemoteAddr();
    String forwarded=request.getHeader("X-Forwarded-For");
    if (forwarded==null || forwarded.length()>1024) return address;
    var hops=forwarded.split(",");
    if (hops.length>20) return address;
    for (int i=hops.length-1;i>=0;i--) {
      final String current=address;
      if (proxies.stream().noneMatch(p -> p.matches(current))) break;
      String candidate=hops[i].trim();
      if (!literal(candidate)) break;
      address=candidate;
    }
    return address;
  }
  private boolean literal(String value) {
    if (value.contains(":")) return value.length()<=45 && value.matches("[0-9a-fA-F:.]+")
        && validIpv6(value);
    var parts=value.split("\\.",-1);
    if (parts.length!=4) return false;
    for (String part:parts) if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part)>255) return false;
    return true;
  }
  private boolean validIpv6(String value) {
    try { java.net.InetAddress.getByName(value); return true; }
    catch (java.net.UnknownHostException error) { return false; }
  }
}
