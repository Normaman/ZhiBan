let handler: (() => Promise<void>) | null = null;
let pending: Promise<void> | null = null;
export function registerPasswordVerification(value: () => Promise<void>) {
  handler = value;
  return () => { handler = null; };
}
export function requestPasswordVerification(): Promise<void> {
  if (pending) return pending;
  if (!handler) return Promise.reject(new Error("请重新登录后操作。"));
  pending = handler().finally(() => { pending = null; });
  return pending;
}
