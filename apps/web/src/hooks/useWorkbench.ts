import { useEffect, useRef, useState } from "react";
import {
  api,
  authenticate,
  signOut,
  restoreSession,
  ApiError,
  selectShop,
} from "../api";
import { errorMessage } from "../errors";
import type { Bootstrap, ConversationDetail, PerformAction } from "../types";

/** Own server state and polling; views own only presentation and unsaved form edits. */
export function useWorkbench() {
  const [session, setSession] = useState<Bootstrap | null>(null);
  const [detail, setDetail] = useState<ConversationDetail | null>(null);
  const [selected, setSelected] = useState("");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState(false);
  const [restoring, setRestoring] = useState(true);
  const [readingLatest, setReadingLatest] = useState(false);
  const [switching, setSwitching] = useState(false);
  const selectedRef = useRef("");
  const sessionEpoch = useRef(0);
  const acting = useRef(false);
  const switchingRef = useRef(false);
  const refreshSequence = useRef(0);

  useEffect(() => {
    let active = true;
    restoreSession()
      .then(async (authenticated) => {
        if (active && authenticated) {
          try {
            await refresh();
          } catch (e) {
            if (e instanceof ApiError && e.code === "SHOP_FORBIDDEN") {
              selectShop("");
              await refresh();
            } else throw e;
          }
        }
      })
      .catch((e) => {
        if (active) setError(errorMessage(e));
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
    };
  }, []);

  async function refresh() {
    const epoch = sessionEpoch.current;
    const sequence = ++refreshSequence.current;
    const requested = selectedRef.current;
    const [data, existingDetail] = await Promise.all([
      api<Bootstrap>("/bootstrap"),
      requested
        ? api<ConversationDetail>(`/conversations/${requested}`)
        : Promise.resolve(null),
    ]);
    if (epoch !== sessionEpoch.current || sequence !== refreshSequence.current)
      return;
    const conversationId = requested || data.conversations[0]?.id;
    const response =
      existingDetail ||
      (conversationId
        ? await api<ConversationDetail>(`/conversations/${conversationId}`)
        : null);
    if (epoch !== sessionEpoch.current || sequence !== refreshSequence.current)
      return;
    selectShop(data.active_shop_id);
    setSession(data);
    selectedRef.current = conversationId || "";
    setSelected(conversationId || "");
    setDetail(response);
  }

  useEffect(() => {
    if (!session) return;
    let inFlight = false;
    async function poll() {
      if (
        document.visibilityState !== "visible" ||
        inFlight ||
        acting.current ||
        switchingRef.current
      )
        return;
      inFlight = true;
      const epoch = sessionEpoch.current;
      try {
        await refresh();
      } catch (e) {
        if (epoch !== sessionEpoch.current) return;
        if (e instanceof ApiError && e.status === 401) clearSession();
        setError(errorMessage(e));
      } finally {
        inFlight = false;
      }
    }
    const timer = setInterval(poll, 2000);
    document.addEventListener("visibilitychange", poll);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", poll);
    };
  }, [!!session, session?.active_shop_id]);

  useEffect(() => {
    if (!session || !detail || detail.id !== selected || !readingLatest) return;
    let cancelled = false;
    let marking = false;
    const epoch = sessionEpoch.current;
    const conversationId = detail.id;
    const unread = detail.messages
      .filter(
        (m) =>
          m.direction === "inbound" &&
          m.message_type !== "system_hint" &&
          m.workbench_read === false,
      )
      .map((m) => m.id)
      .slice(0, 200);
    async function markVisible() {
      if (
        marking ||
        !unread.length ||
        document.visibilityState !== "visible" ||
        !document.hasFocus()
      )
        return;
      marking = true;
      try {
        const result = await api<{ unread_count: number }>(
          `/conversations/${conversationId}/read`,
          { message_ids: unread },
        );
        if (
          cancelled ||
          epoch !== sessionEpoch.current ||
          selectedRef.current !== conversationId
        )
          return;
        const ids = new Set(unread);
        setDetail((current) =>
          current?.id === conversationId
            ? {
                ...current,
                messages: current.messages.map((m) =>
                  ids.has(m.id) ? { ...m, workbench_read: true } : m,
                ),
              }
            : current,
        );
        setSession((current) =>
          current
            ? {
                ...current,
                conversations: current.conversations.map((c) =>
                  c.id === conversationId
                    ? { ...c, unread_count: result.unread_count }
                    : c,
                ),
              }
            : current,
        );
      } catch (e) {
        if (!cancelled && epoch === sessionEpoch.current)
          setError(errorMessage(e));
      } finally {
        marking = false;
      }
    }
    void markVisible();
    window.addEventListener("focus", markVisible);
    document.addEventListener("visibilitychange", markVisible);
    return () => {
      cancelled = true;
      window.removeEventListener("focus", markVisible);
      document.removeEventListener("visibilitychange", markVisible);
    };
  }, [detail, selected, !!session, readingLatest]);

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(""), 4500);
    return () => clearTimeout(timer);
  }, [notice]);

  async function login(user: string, password: string) {
    sessionEpoch.current++;
    try {
      await authenticate(user, password);
      await refresh();
      setError("");
    } catch (e) {
      setSession(null);
      setError(errorMessage(e));
    }
  }

  function clearSession() {
    sessionEpoch.current++;
    selectedRef.current = "";
    setSession(null);
    setDetail(null);
    setReadingLatest(false);
    setSelected("");
    setError("");
    selectShop("");
  }

  async function switchShop(id: string) {
    if (
      acting.current ||
      switchingRef.current ||
      id === session?.active_shop_id
    )
      return;
    switchingRef.current = true;
    sessionEpoch.current++;
    const previous = session?.active_shop_id || "";
    selectedRef.current = "";
    setSelected("");
    setDetail(null);
    setReadingLatest(false);
    setError("");
    setNotice("");
    setSwitching(true);
    selectShop(id);
    try {
      await refresh();
    } catch (e) {
      selectShop(previous);
      setError(errorMessage(e));
      await refresh().catch(() => {});
    } finally {
      switchingRef.current = false;
      setSwitching(false);
    }
  }

  async function logout() {
    try {
      await signOut();
      clearSession();
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  async function selectConversation(id: string) {
    if (id === selectedRef.current) return;
    const sequence = ++refreshSequence.current;
    selectedRef.current = id;
    setSelected(id);
    setDetail(null);
    setReadingLatest(false);
    setError("");
    const epoch = sessionEpoch.current;
    try {
      const response = await api<ConversationDetail>(`/conversations/${id}`);
      if (
        selectedRef.current === id &&
        epoch === sessionEpoch.current &&
        sequence === refreshSequence.current
      )
        setDetail(response);
    } catch (e) {
      setError(errorMessage(e));
    }
  }

  const action: PerformAction = async (work, message) => {
    if (acting.current || switchingRef.current) return;
    acting.current = true;
    setError("");
    setBusy(true);
    try {
      await work();
      await refresh();
      if (message) setNotice(message);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      acting.current = false;
      setBusy(false);
    }
  };

  return {
    session,
    setReadingLatest,
    detail,
    selected,
    error,
    notice,
    busy,
    restoring,
    switching,
    switchShop,
    login,
    logout,
    selectConversation,
    action,
    notify: setNotice,
    clearError: () => setError(""),
  };
}
