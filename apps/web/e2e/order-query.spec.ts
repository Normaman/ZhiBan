import { test, expect } from "@playwright/test";

test.skip(!process.env.ORDER_UI_HARNESS_URL, "Requires a local Vite component harness; no live platform calls");

test("订单安全核验窗口与只读重查可恢复卡片状态", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", error => errors.push(error.message));
  await page.route("**/api/v1/auth/session", route => route.fulfill({ json: {
    authenticated: true, csrf_token: "synthetic-csrf", csrf_header: "X-CSRF-TOKEN",
  } }));
  let reads = 0;
  await page.route("**/api/v1/admin/shop/order-query/verification", async route => {
    expect(route.request().postDataJSON()).toEqual({ order_id: "123456-123456789", buyer_ref: "synthetic-buyer" });
    await route.fulfill({ json: { viewer_url: "/login-desktop/vnc.html#password=synthetic&autoconnect=true" } });
  });
  await page.route("**/api/v1/shop/order-query/retry", async route => {
    reads++;
    await route.fulfill({ json: { order: {
      order_id: "123456-123456789", order_status: "cancelled", status_text: "已取消",
      fetched_at: "2026-11-11T00:00:00Z",
    } } });
  });
  await page.goto(process.env.ORDER_UI_HARNESS_URL!);
  await expect(page.getByTestId("order-card-status")).toContainText("平台安全验证");
  const popupEvent = page.waitForEvent("popup");
  await page.getByRole("button", { name: "安全核验", exact: true }).click();
  const popup = await popupEvent;
  await expect(popup).toHaveURL(/login-desktop\/vnc.html#password=synthetic/);
  await page.getByRole("button", { name: "已完成验证，重新查询", exact: true }).click();
  await expect(page.getByTestId("order-card-status")).toContainText("已取消");
  await expect(page.getByRole("button", { name: "安全核验", exact: true })).toHaveCount(0);
  await expect.poll(() => popup.isClosed()).toBe(true);
  expect(reads).toBe(1);
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  expect(errors).toEqual([]);
});
