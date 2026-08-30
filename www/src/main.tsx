import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App";
import { AppProvider } from "./app-context";
import { initializePwa } from "./pwa";
import "./styles.css";
import { VideoCoordinatorProvider } from "./video";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <BrowserRouter>
      <AppProvider>
        <VideoCoordinatorProvider>
          <App />
        </VideoCoordinatorProvider>
      </AppProvider>
    </BrowserRouter>
  </StrictMode>,
);

initializePwa();

async function configureVideoCacheBudget(): Promise<void> {
  if (!("serviceWorker" in navigator) || !("storage" in navigator)) return;
  const [registration, estimate] = await Promise.all([
    navigator.serviceWorker.ready,
    navigator.storage.estimate(),
  ]);
  const memory = (navigator as Navigator & { deviceMemory?: number }).deviceMemory ?? 4;
  const tier = memory <= 2 ? 64 : memory >= 8 ? 384 : 192;
  const quotaCap = estimate.quota ? Math.floor(estimate.quota * 0.02) : tier * 1024 * 1024;
  registration.active?.postMessage({
    type: "VIDEO_CACHE_BUDGET",
    bytes: Math.min(tier * 1024 * 1024, quotaCap),
  });
}

void configureVideoCacheBudget();
