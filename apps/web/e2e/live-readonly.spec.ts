import { test, expect } from "@playwright/test";

test.skip(
  process.env.RUN_LIVE_READONLY !== "1",
  "Explicit opt-in for read-only checks of a connected live workspace",
);

test("真实店铺工作台显示真实状态且不提供模拟咨询", async ({ page }) => {
  const user = process.env.REVIEWER_USER || "reviewer";
  const password = process.env.REVIEWER_PASSWORD;
  if (!password)
    throw new Error("Set REVIEWER_PASSWORD for the running workspace");
  await page.goto("/");
  await page.getByLabel("账号", { exact: true }).fill(user);
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "全部会话", exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId("connection")).toContainText("在线");
  await expect(page.locator(".live-connection-panel")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "测试咨询", exact: true }),
  ).toHaveCount(0);
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "全部会话", exact: true }),
  ).toBeVisible();
  const cookies = await page.context().cookies();
  expect(
    cookies.find((cookie) => cookie.name === "PDD_SESSION")?.httpOnly,
  ).toBe(true);
  expect(
    await page.evaluate(() =>
      Object.keys(localStorage).some((key) =>
        /credential|password|token/i.test(key),
      ),
    ),
  ).toBe(false);
  await page.getByRole("button", { name: "退出登录", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "登录工作台", exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("button", { name: "登录工作台", exact: true }),
  ).toBeVisible();
});

test("已有咨询显示实际商品图片，商品目录显示平台缩略图", async ({ page }) => {
  const password = process.env.REVIEWER_PASSWORD;
  if (!password)
    throw new Error("Set REVIEWER_PASSWORD for the running workspace");
  await page.goto("/");
  await page
    .getByLabel("账号", { exact: true })
    .fill(process.env.REVIEWER_USER || "reviewer");
  await page.getByLabel("密码", { exact: true }).fill(password);
  await page.getByRole("button", { name: "登录工作台", exact: true }).click();
  const bootstrap = await (await page.request.get("/api/v1/bootstrap")).json();
  let buyerSuffix = "";
  for (const conversation of bootstrap.conversations) {
    const detail = await (
      await page.request.get(`/api/v1/conversations/${conversation.id}`)
    ).json();
    if (
      detail.messages.some(
        (message: { media?: { kind: string; image_url?: string } }) =>
          message.media?.kind === "product" && message.media.image_url,
      )
    ) {
      buyerSuffix = conversation.buyer_ref.slice(-4);
      break;
    }
  }
  test.skip(
    !buyerSuffix,
    "No live product card has been received in this workspace",
  );
  await page
    .locator(".conversation-row")
    .filter({ hasText: `访客 · ${buyerSuffix}` })
    .click();
  const image = page.getByTestId("message-product").locator("img").first();
  await expect(image).toBeVisible();
  await expect
    .poll(() =>
      image.evaluate((element: HTMLImageElement) => element.naturalWidth),
    )
    .toBeGreaterThan(0);
  await page.getByRole("button", { name: "知识库", exact: true }).click();
  await expect(
    page.getByRole("region", { name: "平台商品资料" }),
  ).toBeVisible();
  const thumbnail = page.locator(".catalog-product img").first();
  await expect(thumbnail).toBeVisible();
  await expect
    .poll(() =>
      thumbnail.evaluate((element: HTMLImageElement) => element.naturalWidth),
    )
    .toBeGreaterThan(0);
});
