import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../api";
import { errorMessage } from "../errors";

interface CacheMetadata {
  has_value: boolean;
  state: "empty" | "fresh" | "stale";
  refreshing: boolean;
  error_code: string | null;
  expires_at: string | null;
}

interface View<T> {
  key: string | null;
  data: T | null;
  loading: boolean;
  error: string;
}

/** Polls refresh progress without reissuing a forced platform query. */
export function useOrderView<T>(path: string | null) {
  const [view, setView] = useState<View<T>>({ key: null, data: null, loading: false, error: "" });
  const generation = useRef(0);
  const timer = useRef<number | undefined>(undefined);

  const load = useCallback((force = false) => {
    const current = ++generation.current;
    window.clearTimeout(timer.current);
    if (!path) return;
    setView(previous => ({ key: path, data: previous.key === path ? previous.data : null,
      loading: true, error: "" }));
    let busyRetries = 0;

    async function poll(refresh: boolean) {
      try {
        const separator = path!.includes("?") ? "&" : "?";
        const response = await api<T & { cache: CacheMetadata }>(path + (refresh ? `${separator}refresh=true` : ""));
        if (current !== generation.current) return;
        if (!response.cache) throw new ApiError(502, "ORDER_CACHE_UNAVAILABLE");
        const metadata = response.cache;
        const retryBusy = metadata.error_code === "ORDER_REFRESH_BUSY" && busyRetries < 12;
        if (retryBusy) busyRetries++;
        setView(previous => ({ key: path,
          data: metadata.has_value ? response : previous.key === path ? previous.data : null,
          loading: metadata.refreshing || retryBusy,
          error: !retryBusy && metadata.error_code ? errorMessage(new ApiError(502, metadata.error_code)) : "",
        }));
        if (retryBusy)
          timer.current = window.setTimeout(() => void poll(force), 5000);
        else if (metadata.refreshing)
          timer.current = window.setTimeout(() => void poll(false), 2000);
      } catch (cause) {
        if (current !== generation.current) return;
        const unauthorized = cause instanceof ApiError && [401, 403].includes(cause.status);
        setView(previous => ({ key: path,
          data: unauthorized || previous.key !== path ? null : previous.data,
          loading: false, error: errorMessage(cause),
        }));
      }
    }
    void poll(force);
  }, [path]);

  useEffect(() => {
    load();
    return () => { ++generation.current; window.clearTimeout(timer.current); };
  }, [load]);

  return {
    data: view.key === path ? view.data : null,
    loading: path !== null && (view.key !== path || view.loading),
    error: view.key === path ? view.error : "",
    refresh: () => load(true),
  };
}
