import { expect, test } from "@playwright/test";

test.skip(
  process.env.RUN_DEPLOYMENT_CSP_CHECKS !== "1",
  "需要部署后的 Nginx 响应头，普通 Vite 环境不运行此检查",
);

test("部署策略允许拼多多图片，同时阻止非平台图片来源", async ({ page }) => {
  await page.route("**/api/v1/auth/session", (route) =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        authenticated: false,
        csrf_token: "deployment-fixture",
        csrf_header: "X-CSRF-TOKEN",
      }),
    }),
  );
  const pixel = Buffer.from(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMB/SpVzsAAAAASUVORK5CYII=",
    "base64",
  );
  await page.route(/https?:\/\/(?!127\.0\.0\.1|localhost)/, (route) =>
    route.fulfill({ contentType: "image/png", body: pixel }),
  );
  await page.addInitScript(() => {
    const state = window as unknown as { imageViolations: string[] };
    state.imageViolations = [];
    document.addEventListener("securitypolicyviolation", (event) => {
      if (event.effectiveDirective === "img-src")
        state.imageViolations.push(event.blockedURI);
    });
  });
  const response = await page.goto("/");
  expect(response?.headers()["content-security-policy"]).toContain("img-src");
  await expect(page.getByRole("button", { name: "登录工作台", exact: true })).toBeVisible();

  async function loadImage(src: string) {
    return page.evaluate(
      (url) =>
        new Promise<boolean>((resolve) => {
          const image = document.createElement("img");
          const finish = (loaded: boolean) => {
            image.remove();
            resolve(loaded);
          };
          image.onload = () => finish(image.naturalWidth > 0);
          image.onerror = () => finish(false);
          image.src = url;
          document.body.appendChild(image);
        }),
      src,
    );
  }

  for (const origin of [
    "https://pddpic.com",
    "https://img.pddpic.com",
    "https://pinduoduo.com",
    "https://static.pinduoduo.com",
    "https://yangkeduo.com",
    "https://img.yangkeduo.com",
  ]) {
    expect(await loadImage(origin + "/deployment-fixture.png"), origin).toBe(true);
  }
  for (const origin of [
    "https://pddpic.com.evil.invalid",
    "https://evilpddpic.com",
    "https://untrusted.invalid",
    "http://img.pddpic.com",
  ]) {
    expect(await loadImage(origin + "/deployment-fixture.png"), origin).toBe(false);
    await expect
      .poll(() =>
        page.evaluate((prefix) => {
          const state = window as unknown as { imageViolations: string[] };
          return state.imageViolations.some((url) => url.startsWith(prefix));
        }, origin),
      )
      .toBe(true);
  }
});
