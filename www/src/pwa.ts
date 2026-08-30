import { registerSW } from "virtual:pwa-register";

let updateServiceWorker: ((reloadPage?: boolean) => Promise<void>) | null = null;

export function initializePwa(): void {
  updateServiceWorker = registerSW({
    immediate: true,
    onNeedRefresh: () => dispatchEvent(new CustomEvent("readthat:pwa-update")),
    onOfflineReady: () => dispatchEvent(new CustomEvent("readthat:pwa-offline-ready")),
    onRegisterError: (error) => console.error("Service worker registration failed", error),
  });
}

export async function activatePwaUpdate(): Promise<void> {
  await updateServiceWorker?.(true);
}
