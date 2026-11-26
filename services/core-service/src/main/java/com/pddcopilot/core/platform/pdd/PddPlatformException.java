package com.pddcopilot.core.platform.pdd;

/** Fixed diagnostic codes only; platform bodies may contain private account data. */
public final class PddPlatformException extends RuntimeException {
  private final String code;

  public PddPlatformException(String code) {
    super(code);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
