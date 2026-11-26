import { test, expect, type Page } from "@playwright/test";

// Only run against the disposable deployment used for security verification.
test.skip(process.env.SECURITY_E2E_PROJECT !== "pdd-security-check", "Requires an isolated security deployment");
const user = process.env.SECURITY_E2E_USER || "";
const password = process.env.SECURITY_E2E_PASSWORD || "";
async function login(page: Page, name = user, secret = password) {
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(name);
  await page.getByLabel("密码", { exact: true }).fill(secret);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await expect(page.getByRole("button", { name: "账号安全", exact: true })).toBeVisible();
}
async function openSecurity(page: Page) {
  await page.getByRole("button", { name: "账号安全", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "账号安全", exact: true })).toBeVisible();
  await expect(page.getByText(/当前会话/)).toBeVisible();
}

test("account sessions are readable and the panel fits a narrow viewport", async ({ page }) => {
  await login(page);
  await page.reload();
  await openSecurity(page);
  await expect(page.getByRole("heading", { name: "最近安全记录" })).toBeVisible();
  await expect(page.getByText("登录成功", { exact: true }).first()).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  const box = await page.getByRole("dialog", { name: "账号安全", exact: true }).boundingBox();
  expect(box && box.width <= 390 && box.x >= 0).toBeTruthy();
});

test("revoking another session requests password verification and preserves the current session", async ({ page, browser }) => {
  await login(page);
  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await login(other);
  const sessions = await (await other.request.get("/api/v1/auth/sessions")).json();
  const reference = sessions.find((s: { current: boolean }) => s.current).id;
  let intercepted = false;
  await page.route(`**/api/v1/auth/sessions/${reference}`, async (route) => {
    if (route.request().method() === "DELETE" && !intercepted) {
      intercepted = true;
      await route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ code: "REAUTHENTICATION_REQUIRED" }) });
    } else await route.continue();
  });
  await openSecurity(page);
  await page.getByTestId(`security-session-${reference}`).getByRole("button", { name: "注销", exact: true }).click();
  const verify = page.getByRole("dialog", { name: "确认是你本人" });
  await expect(verify).toBeVisible();
  await verify.getByLabel("登录密码").fill(password);
  await verify.getByRole("button", { name: "验证并继续" }).click();
  await expect(verify).not.toBeVisible();
  await expect(page.getByTestId(`security-session-${reference}`)).toHaveCount(0);
  expect((await other.request.get("/api/v1/bootstrap")).status()).toBe(401);
  expect((await page.request.get("/api/v1/bootstrap")).status()).toBe(200);
  await otherContext.close();
});

test("changing a disposable account password logs it out and permits only the new password", async ({ page }) => {
  const account = `security-${Date.now()}`;
  const oldPassword = "isolated-old-password-123";
  const newPassword = "isolated-new-password-456";
  await page.goto("/");
  const state = await (await page.request.get("/api/v1/auth/session")).json();
  const registration = await page.request.post("/api/v1/auth/register", {
    headers: { [state.csrf_header]: state.csrf_token },
    data: { username: account, password: oldPassword, invite_code: process.env.SECURITY_E2E_INVITE },
  });
  expect(registration.status()).toBe(201);
  await login(page, account, oldPassword);
  await openSecurity(page);
  await page.getByLabel("当前密码", { exact: true }).fill(oldPassword);
  await page.getByLabel("新密码", { exact: true }).fill(newPassword);
  await page.getByLabel("确认新密码", { exact: true }).fill(newPassword);
  await page.getByRole("button", { name: "保存新密码" }).click();
  await expect(page.getByRole("button", { name: "登录工作台", exact: true })).toBeVisible();
  await login(page, account, newPassword);
});
