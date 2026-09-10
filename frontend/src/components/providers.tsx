"use client";

import { ThemeProvider } from "next-themes";
import type { ReactNode } from "react";

/**
 * Providers — racine client de l'application.
 *
 * - ThemeProvider : mode clair/sombre (le mode sombre clinique est un teal
 *   lumineux, cohérent avec les tokens v0.2 de globals.css) ;
 * - l'écart d'hydratation de next-themes est neutralisé par
 *   `suppressHydrationWarning` sur <html> (layout) ;
 * - le miroir IndexedDB s'hydrate dans l'AppShell (store zustand).
 */
export function Providers({ children }: { children: ReactNode }) {
  return (
    <ThemeProvider
      attribute="class"
      defaultTheme="light"
      enableSystem={false}
      disableTransitionOnChange
    >
      {children}
    </ThemeProvider>
  );
}
