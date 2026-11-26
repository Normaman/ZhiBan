package com.pddcopilot.core.bootstrap;

import com.pddcopilot.core.repository.BusinessStore;
import com.pddcopilot.core.repository.ShopRepository;
import com.pddcopilot.core.service.UserService;
import com.pddcopilot.core.service.WorkspaceService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates an unbound workspace on first start; platform authorization is an explicit user action. */
@Component
public class Bootstrap implements ApplicationRunner {
  private final BusinessStore store;
  private final ShopRepository shops;
  private final UserService users;
  private final WorkspaceService workspaces;
  private final String reviewer;
  private final String password;

  public Bootstrap(
      BusinessStore store,
      ShopRepository shops,
      UserService users,
      WorkspaceService workspaces,
      @Value("${copilot.reviewer}") String reviewer,
      @Value("${copilot.reviewer-password}") String password) {
    this.store = store;
    this.shops = shops;
    this.users = users;
    this.workspaces = workspaces;
    this.reviewer = reviewer;
    this.password = password;
  }

  @Override
  @org.springframework.transaction.annotation.Transactional
  public void run(ApplicationArguments args) {
    if (shops.findShopIds(store.shopId()).isEmpty()) {
      workspaces.ensureLegacyWorkspace(store.shopId(), "我的工作空间");
      shops.insertShop(store.shopId(), "拼多多店铺", "unbound", "unbound", "live");
    } else {
      shops.resetConnection(store.shopId());
    }
    if (password.length() < 12)
      throw new IllegalArgumentException("REVIEWER_PASSWORD must have at least 12 characters");
    users.ensureAdministrator(reviewer, password);
  }
}
