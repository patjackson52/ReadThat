import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api } from "./api";
import type { AuthState } from "./types";

interface AppContextValue {
  auth: AuthState | null;
  authLoading: boolean;
  online: boolean;
  pendingMutations: number;
  signInRequested: () => void;
  authRequestId: number;
  notify: (message: string, tone?: "info" | "success" | "error") => void;
}

interface Toast { id: string; message: string; tone: "info" | "success" | "error" }

const AppContext = createContext<AppContextValue | null>(null);

export function AppProvider({ children }: { children: ReactNode }) {
  const [auth, setAuth] = useState<AuthState | null>(null);
  const [authLoading, setAuthLoading] = useState(true);
  const [online, setOnline] = useState(navigator.onLine);
  const [pendingMutations, setPendingMutations] = useState(0);
  const [authRequestId, setAuthRequestId] = useState(0);
  const [toasts, setToasts] = useState<Toast[]>([]);

  const notify = useCallback((message: string, tone: Toast["tone"] = "info") => {
    const id = crypto.randomUUID();
    setToasts((current) => [...current.slice(-2), { id, message, tone }]);
    window.setTimeout(() => setToasts((current) => current.filter((toast) => toast.id !== id)), 4_500);
  }, []);

  useEffect(() => {
    const unsubscribeAuth = api.subscribeAuth(setAuth);
    const unsubscribeOutbox = api.subscribeOutbox(setPendingMutations);
    void api.restore().then(() => api.flushOutbox()).finally(() => setAuthLoading(false));
    const handleOnline = () => {
      setOnline(true);
      void api.flushOutbox().then(() => notify("Queued changes synced", "success"));
    };
    const handleOffline = () => setOnline(false);
    const handleServiceWorker = (event: MessageEvent<{ type?: string }>) => {
      if (event.data?.type === "FLUSH_OUTBOX") void api.flushOutbox();
    };
    addEventListener("online", handleOnline);
    addEventListener("offline", handleOffline);
    navigator.serviceWorker?.addEventListener("message", handleServiceWorker);
    return () => {
      unsubscribeAuth();
      unsubscribeOutbox();
      removeEventListener("online", handleOnline);
      removeEventListener("offline", handleOffline);
      navigator.serviceWorker?.removeEventListener("message", handleServiceWorker);
    };
  }, [notify]);

  const value = useMemo<AppContextValue>(() => ({
    auth,
    authLoading,
    online,
    pendingMutations,
    authRequestId,
    signInRequested: () => setAuthRequestId((value) => value + 1),
    notify,
  }), [auth, authLoading, authRequestId, notify, online, pendingMutations]);

  return <AppContext.Provider value={value}>
    {children}
    <div className="toast-region" aria-live="polite" aria-atomic="false">
      {toasts.map((toast) => <div className={`toast toast-${toast.tone}`} key={toast.id}>{toast.message}</div>)}
    </div>
  </AppContext.Provider>;
}

export function useApp(): AppContextValue {
  const value = useContext(AppContext);
  if (!value) throw new Error("useApp must be used inside AppProvider");
  return value;
}
