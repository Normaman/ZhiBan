package com.pddcopilot.core.repository;

import com.pddcopilot.core.domain.UserAccount;
import com.pddcopilot.core.mapper.UserMapper;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {
  private final UserMapper mapper;

  public UserRepository(UserMapper mapper) {
    this.mapper = mapper;
  }

  public Optional<UserAccount> findByUsername(String username) {
    return Optional.ofNullable(mapper.findByUsername(username));
  }

  public void insert(UserAccount account, long createdAt) {
    mapper.insert(account, createdAt);
  }

  public long authVersion(String username) {
    Long version = mapper.authVersion(username);
    return version == null ? -1 : version;
  }

  public void revokeAll(String username) {
    mapper.revokeAll(username);
  }

  public int changePassword(String username, String hash, long version, String accountId) {
    return mapper.changePassword(hash, username, version, accountId);
  }
}
