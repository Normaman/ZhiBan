import { requestPasswordVerification } from "./security";

let csrfToken = "";
let csrfHeader = "X-CSRF-TOKEN";
let restoring: Promise<boolean> | null = null;
let activeShop = localStorage.getItem("copilot.activeShop") || "";

export function selectShop(id: string) {
  activeShop = id;
  if (id) localStorage.setItem("copilot.activeShop", id);
  else localStorage.removeItem("copilot.activeShop");
}

/** Authentication stays in an HttpOnly server cookie; browser storage holds no password. */
export function restoreSession(): Promise<boolean> {
  if (restoring) return restoring;
  restoring = fetch("/api/v1/auth/session", {
    credentials: "same-origin",
    cache: "no-store",
  })
    .then(async (response) => {
      if (!response.ok)
        throw new ApiError(response.status, "SESSION_UNAVAILABLE");
      const value = await response.json();
      csrfToken = value.csrf_token;
      csrfHeader = value.csrf_header;
      return value.authenticated === true;
    })
    .finally(() => {
      restoring = null;
    });
  return restoring;
}

export async function authenticate(user: string, password: string) {
  selectShop("");
  await api("/auth/login", { username: user, password });
  await restoreSession();
}

export async function signOut() {
  await api("/auth/logout", {});
  csrfToken = "";
  selectShop("");
}

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
  ) {
    super(code);
  }
}

export async function api<T = unknown>(
  path: string,
  body?: unknown,
  method?: "DELETE" | "PUT" | "PATCH",
  retryVerification = true,
): Promise<T> {
  const shop = activeShop;
  if ((body !== undefined || method === "DELETE") && !csrfToken)
    await restoreSession();
  const response = await fetch(`/api/v1${path}`, {
    method: method || (body === undefined ? "GET" : "POST"),
    credentials: "same-origin",
    headers: {
      ...(shop && !path.startsWith("/auth/") ? { "X-Shop-Id": shop } : {}),
      ...(body === undefined && method !== "DELETE"
        ? {}
        : {
            ...(body === undefined
              ? {}
              : { "Content-Type": "application/json" }),
            [csrfHeader]: csrfToken,
          }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    if (error.code === "REAUTHENTICATION_REQUIRED" && retryVerification) {
      await requestPasswordVerification();
      return api<T>(path, body, method, false);
    }
    if (error.code === "CSRF_INVALID") csrfToken = "";
    throw new ApiError(
      response.status,
      error.code || `HTTP_${response.status}`,
    );
  }
  return response.status === 204 ? (null as T) : response.json();
}
