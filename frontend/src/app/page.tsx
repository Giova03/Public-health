"use client";

import { AppShell } from "@/components/app-shell";

/**
 * PUBLIC HEALTH — application clinique PWA (mono-page : la plateforme de
 * démonstration n'expose que cette route ; la navigation entre modules est
 * portée par l'état, le miroir IndexedDB et le moteur de synchronisation).
 */
export default function Home() {
  return <AppShell />;
}
