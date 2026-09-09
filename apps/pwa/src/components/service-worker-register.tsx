"use client";

import { useEffect } from "react";

/**
 * Enregistrement du service worker PWA (offline-first).
 * Échoue silencieusement hors contexte sécurisé (http local).
 */
export function ServiceWorkerRegister() {
  useEffect(() => {
    if (typeof navigator !== "undefined" && "serviceWorker" in navigator) {
      navigator.serviceWorker
        .register("/sw.js")
        .catch(() => {
          // Hors ligne ou contexte non sécurisé : ignoré volontairement
        });
    }
  }, []);

  return null;
}
