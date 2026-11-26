package com.pddcopilot.core.domain;

/** Credentials are confined to authentication; never serialize this record as an API response. */
public record UserAccount(
    String id, String username, String passwordHash, String shopId, String role, boolean enabled) {}
