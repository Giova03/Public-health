"use client";

/**
 * Vue Rendez-vous (V14, I10) — la machine à états du RDV.
 * Créer (agent ou patient), confirmer, honorer, consigner l'absence,
 * annuler (motif OBLIGATOIRE). Un RDV passé est immuable.
 */

import { useEffect, useState } from "react";
import { CalendarDays, CheckCircle2, CircleAlert, XCircle } from "lucide-react";
import { useAppStore } from "@/lib/store";
import { useSessionStore } from "@/lib/session";
import { usePermission } from "@/lib/session";
import { useToast } from "@/hooks/use-toast";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select, SelectContent, SelectItem, SelectTrigger, SelectValue,
} from "@/components/ui/select";

const STATUTS: Record<string, { label: string; classe: string }> = {
  demande: { label: "Demandé", classe: "bg-amber-500/10 text-amber-700 dark:text-amber-400" },
  confirme: { label: "Confirmé", classe: "bg-sky-500/10 text-sky-700 dark:text-sky-400" },
  honore: { label: "Honoré", classe: "bg-emerald-500/10 text-emerald-700 dark:text-emerald-400" },
  annule: { label: "Annulé", classe: "bg-red-500/10 text-red-700 dark:text-red-400" },
  absent: { label: "Absent", classe: "bg-zinc-500/10 text-zinc-700 dark:text-zinc-400" },
};

const TYPES: Record<string, string> = {
  general: "Consultation", cpn: "CPN (grossesse)", vaccination: "Vaccination (PEV)",
  controle: "Contrôle", suivi: "Suivi",
};

export function AppointmentsView() {
  const appointments = useAppStore((s) => s.appointments);
  const patients = useAppStore((s) => s.patients);
  const loadAppointments = useAppStore((s) => s.loadAppointments);
  const createAppointment = useAppStore((s) => s.createAppointment);
  const transitionAppointment = useAppStore((s) => s.transitionAppointment);
  const { toast } = useToast();
  const peutGerer = usePermission("rendezvous:gerer");
  const session = useSessionStore((s) => s.session);
  const estPatient = session?.profile === "PATIENT";

  const [patientId, setPatientId] = useState("");
  const [type, setType] = useState("general");
  const [creneau, setCreneau] = useState("");
  const [motif, setMotif] = useState("");

  useEffect(() => {
    void loadAppointments(estPatient ? session.patientId : undefined);
  }, [loadAppointments, estPatient, session]);

  async function creer() {
    if (!creneau) {
      toast({ title: "Créneau manquant", description: "Choisissez une date et une heure." });
      return;
    }
    const resultat = await createAppointment({
      patientId: estPatient ? undefined : patientId || undefined,
      type: type as "general",
      creneau: new Date(creneau).toISOString(),
      motif: motif.trim() || undefined,
    });
    if (resultat.status !== "ok") {
      toast({ title: "Refusé", description: "message" in resultat ? resultat.message : "Transition refusée", variant: "destructive" });
      return;
    }
    toast({ title: "Rendez-vous enregistré", description: "Statut initial : demande." });
    setMotif(""); setCreneau("");
  }

  async function agir(id: string, action: "confirmer" | "honorer" | "absent" | "annuler", motif?: string) {
    if (action === "annuler" && !motif?.trim()) {
      toast({ title: "Motif obligatoire", description: "L'annulation exige un motif (traçabilité).", variant: "destructive" });
      return;
    }
    const resultat = await transitionAppointment(id, action, motif);
    if (resultat.status !== "ok") {
      toast({ title: "Transition refusée", description: "message" in resultat ? resultat.message : "Transition refusée", variant: "destructive" });
      return;
    }
    toast({ title: "Rendez-vous mis à jour" });
  }

  const visibles = estPatient
    ? appointments.filter((r) => r.patientId === session.patientId)
    : appointments;

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Rendez-vous</h1>
        <p className="text-sm text-muted-foreground">
          Machine à états : demandé → confirmé → honoré (ou annulé / absent).
          Un rendez-vous passé est immuable — l&apos;historique fait foi.
        </p>
      </div>

      {peutGerer && !estPatient && (
        <Card>
          <CardHeader><CardTitle className="text-base">Convoquer un patient</CardTitle></CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <div className="space-y-1.5">
              <Label>Patient</Label>
              <Select value={patientId} onValueChange={setPatientId}>
                <SelectTrigger className="rounded-full"><SelectValue placeholder="Choisir" /></SelectTrigger>
                <SelectContent>
                  {patients.filter((p) => p.active && !p.deceased).slice(0, 30).map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {p.name.family} {p.name.given}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Type</Label>
              <Select value={type} onValueChange={setType}>
                <SelectTrigger className="rounded-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {Object.entries(TYPES).map(([k, v]) => (
                    <SelectItem key={k} value={k}>{v}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5">
              <Label>Créneau</Label>
              <Input type="datetime-local" value={creneau} onChange={(e) => setCreneau(e.target.value)} className="rounded-full" />
            </div>
            <div className="space-y-1.5">
              <Label>Motif</Label>
              <Input value={motif} onChange={(e) => setMotif(e.target.value)} placeholder="CPN 3, contrôle…" className="rounded-full" />
            </div>
            <div className="flex items-end">
              <Button variant="medical" className="w-full rounded-full" onClick={creer}>
                <CalendarDays className="h-4 w-4" /> Convoquer
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {estPatient && (
        <Card>
          <CardHeader><CardTitle className="text-base">Demander un rendez-vous</CardTitle></CardHeader>
          <CardContent className="grid gap-4 sm:grid-cols-4">
            <div className="space-y-1.5 sm:col-span-1">
              <Label>Type</Label>
              <Select value={type} onValueChange={setType}>
                <SelectTrigger className="rounded-full"><SelectValue /></SelectTrigger>
                <SelectContent>
                  {Object.entries(TYPES).map(([k, v]) => (
                    <SelectItem key={k} value={k}>{v}</SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1.5 sm:col-span-1">
              <Label>Créneau</Label>
              <Input type="datetime-local" value={creneau} onChange={(e) => setCreneau(e.target.value)} className="rounded-full" />
            </div>
            <div className="space-y-1.5 sm:col-span-1">
              <Label>Motif</Label>
              <Input value={motif} onChange={(e) => setMotif(e.target.value)} className="rounded-full" />
            </div>
            <div className="flex items-end sm:col-span-1">
              <Button variant="medical" className="w-full rounded-full" onClick={creer}>
                Demander (max 1/jour)
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">
            {estPatient ? "Mes rendez-vous" : "Agenda de la structure"} ({visibles.length})
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-3">
          {visibles.length === 0 && (
            <p className="text-sm text-muted-foreground">Aucun rendez-vous pour l&apos;instant.</p>
          )}
          {visibles.slice(0, 30).map((rdv) => {
            const passe = new Date(rdv.creneau).getTime() < Date.now();
            const patient = patients.find((p) => p.id === rdv.patientId);
            return (
              <div key={rdv.id} className="flex flex-wrap items-center gap-3 rounded-2xl border p-4">
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant="secondary" className={STATUTS[rdv.statut]?.classe}>
                      {STATUTS[rdv.statut]?.label ?? rdv.statut}
                    </Badge>
                    <span className="text-sm font-medium">
                      {patient ? `${patient.name.family} ${patient.name.given}` : "Patient"}
                    </span>
                    <span className="text-xs text-muted-foreground">
                      {TYPES[rdv.type] ?? rdv.type} · {new Date(rdv.creneau).toLocaleString("fr-FR")}
                      {passe && " · PASSÉ (immuable)"}
                    </span>
                  </div>
                  {rdv.motif && <p className="mt-1 text-xs text-muted-foreground">{rdv.motif}</p>}
                  {rdv.motifAnnulation && (
                    <p className="mt-1 text-xs text-red-600">Annulation : {rdv.motifAnnulation}</p>
                  )}
                </div>
                {rdv.statut !== "annule" && rdv.statut !== "honore" && rdv.statut !== "absent" && (
                  <div className="flex flex-wrap gap-2">
                    {peutGerer && !passe && rdv.statut === "demande" && (
                      <Button size="sm" variant="outline" className="rounded-full"
                        onClick={() => agir(rdv.id, "confirmer")}>
                        <CheckCircle2 className="h-4 w-4" /> Confirmer
                      </Button>
                    )}
                    {peutGerer && (
                      <Button size="sm" variant="outline" className="rounded-full"
                        onClick={() => agir(rdv.id, "honorer")}>
                        Honoré
                      </Button>
                    )}
                    {peutGerer && rdv.statut === "confirme" && (
                      <Button size="sm" variant="outline" className="rounded-full"
                        onClick={() => agir(rdv.id, "absent")}>
                        <CircleAlert className="h-4 w-4" /> Absent
                      </Button>
                    )}
                    {!passe && (
                      <Button size="sm" variant="ghost" className="rounded-full text-destructive"
                        onClick={() => {
                          const motif = window.prompt("Motif d'annulation (OBLIGATOIRE) :");
                          if (motif) void agir(rdv.id, "annuler", motif);
                        }}>
                        <XCircle className="h-4 w-4" /> Annuler
                      </Button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </CardContent>
      </Card>
    </div>
  );
}
