package com.pddcopilot.core.mapper;

import com.pddcopilot.core.domain.UserAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL mappings for UserRepository; transaction boundaries remain in the calling service. */
@Mapper
public interface UserMapper {
  UserAccount findByUsername(@Param("username") String username);

  int insert(@Param("account") UserAccount account, @Param("createdAt") long createdAt);

  Long authVersion(@Param("username") String username);

  int revokeAll(@Param("username") String username);

  int changePassword(
      @Param("hash") String hash,
      @Param("username") String username,
      @Param("version") long version,
      @Param("accountId") String accountId);
}
