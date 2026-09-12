"use client";

/**
 * Vue Caisse (I5, P0.5-6) — le parcours monétaire réel du BF.
 *
 * Admission → CAISSE → salle d'attente → consultation : le ticket
 * modérateur se règle AVANT l'acte clinique, et l'exonération (indigent
 * attesté, enfant < 5 ans, césarienne, grossesse suivie) se décide à la
 * caisse, TRACÉE (nature + motif obligatoires). La consultation est
 * refusée 402 tant que le ticket du jour n'est pas réglé.
 */

import { useEffect, useMemo, useState } from "react";
import {
  BadgeCheck,
  Banknote,
  HandCoins,
  HeartHandshake,
  Loader2,
  Search,
  Users,
} from "lucide-react";
import { useToast } from "@/hooks/use-toast";
import { useAppStore } from "@/lib/store";
import { useCurrentUser } from "@/lib/session";
import { roleHasPermission } from "@/lib/rbac";
import { formatXof, EXONERATION_NATURES, type ExonerationNature, type FraisAccesTicket } from "@/lib/types";
import { ApiError } from "@/lib/api-client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

/* ----------------------------- Icônes libres --------------------------- */

function IconeStatut({ statut }: { statut: FraisAccesTicket["statut"] }) {
  if (statut === "paye") return <BadgeCheck className="h-4 w-4 text-emerald-600" aria-hidden />;
  if (statut === "exonere") return <HeartHandshake className="h-4 w-4 text-sky-600" aria-hidden />;
  return <Users className="h-4 w-4 text-amber-600" aria-hidden />;
}

function LibelleStatut({ statut }: { statut: FraisAccesTicket["statut"] }) {
  if (statut === "paye") return <Badge className="bg-emerald-100 text-emerald-800">Payé</Badge>;
  if (statut === "exonere") return <Badge className="bg-sky-100 text-sky-800">Exonéré</Badge>;
  return <Badge className="bg-amber-100 text-amber-800">En attente</Badge>;
}

function libelleNature(nature?: ExonerationNature): string {
  return EXONERATION_NATURES.find((n) => n.value === nature)?.label ?? nature ?? "—";
}

/* -------------------------------- La vue -------------------------------- */

export function CaisseView() {
  const fraisAcces = useAppStore((s) => s.fraisAcces);
  const patients = useAppStore((s) => s.patients);
  const loadCaisse = useAppStore((s) => s.loadCaisse);
  const ouvrirTicket = useAppStore((s) => s.ouvrirTicket);
  const encaisserTicket = useAppStore((s) => s.encaisserTicket);
  const exonererTicket = useAppStore((s) => s.exonererTicket);
  const { toast } = useToast();
  const user = useCurrentUser();
  const structure = user.facility && user.facility !== "—" ? user.facility : "CSPS Ouaga 12";
  const peutInitier = roleHasPermission(user.role, "paiement:initier");

  // Ouverture de ticket : recherche patient + montant.
  const [recherche, setRecherche] = useState("");
  const [patientChoisi, setPatientChoisi] = useState<string | null>(null);
  const [montant, setMontant] = useState("1000");
  const [enCours, setEnCours] = useState(false);

  // Exonération : ticket ciblé + nature + motif.
  const [ticketExonerer, setTicketExonerer] = useState<FraisAccesTicket | null>(null);
  const [nature, setNature] = useState<ExonerationNature>("indigent_atteste");
  const [motif, setMotif] = useState("");

  useEffect(() => { void loadCaisse(structure); }, [loadCaisse, structure]);

  const aujourdhui = new Date().toISOString().slice(0, 10);
  const ticketsJour = useMemo(
    () => fraisAcces.filter((t) => t.createdAt.slice(0, 10) === aujourdhui),
    [fraisAcces, aujourdhui],
  );
  const enAttente = ticketsJour.filter((t) => t.statut === "en_attente");
  const encaisses = ticketsJour.filter((t) => t.statut === "paye");
  const exonerations = ticketsJour.filter((t) => t.statut === "exonere");
  const recette = encaisses.reduce((somme, t) => somme + t.montantXof, 0);

  const resultats = useMemo(() => {
    const q = recherche.trim().toLowerCase();
    if (!q) return patients.slice(0, 6);
    return patients
      .filter((p) => p.active !== false)
      .filter((p) =>
        `${p.name.family} ${p.name.given} ${p.phReference} ${p.phone ?? ""}`
          .toLowerCase().includes(q),
      )
      .slice(0, 8);
  }, [patients, recherche]);

  async function ouvrir() {
    if (!patientChoisi) {
      toast({ title: "Patient manquant", description: "Choisissez le patient dans la liste.", variant: "destructive" });
      return;
    }
    setEnCours(true);
    const resultat = await ouvrirTicket(patientChoisi, structure, Number(montant) || 1000);
    setEnCours(false);
    if (resultat.status !== "ok") {
      toast({ title: "Ticket refusé", description: resultat.status === "error" ? resultat.message : "Refus", variant: "destructive" });
      return;
    }
    toast({ title: "Ticket ouvert", description: `File d'attente caisse — ${structure}` });
    setPatientChoisi(null);
    setRecherche("");
    setMontant("1000");
  }

  async function encaisser(ticket: FraisAccesTicket) {
    setEnCours(true);
    const resultat = await encaisserTicket(ticket.id, ticket.montantXof);
    setEnCours(false);
    if (resultat.status !== "ok") {
      toast({ title: "Encaissement refusé", description: resultat.status === "error" ? resultat.message : "Refus", variant: "destructive" });
      return;
    }
    toast({ title: "Encaissé", description: `${formatXof(ticket.montantXof)} — le patient peut consulter` });
  }

  async function validerExoneration() {
    if (!ticketExonerer) return;
    if (!motif.trim()) {
      toast({ title: "Motif obligatoire", description: "L'exonération est TRACÉE : le motif est exigé (I5).", variant: "destructive" });
      return;
    }
    setEnCours(true);
    const resultat = await exonererTicket(ticketExonerer.id, nature, motif.trim());
    setEnCours(false);
    if (resultat.status !== "ok") {
      toast({ title: "Exonération refusée", description: resultat.status === "error" ? resultat.message : "Refus", variant: "destructive" });
      return;
    }
    toast({ title: "Exonération enregistrée", description: `${libelleNature(nature)} — ${motif.trim()}` });
    setTicketExonerer(null);
    setMotif("");
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Caisse — tickets d&apos;accès</h1>
        <p className="text-sm text-muted-foreground">
          Le parcours monétaire réel : admission → caisse → salle d&apos;attente → consultation.
          Sans ticket réglé du jour (payé ou exonéré), la consultation est refusée (402).
          L&apos;exonération — indigent attesté, enfant de moins de 5 ans, césarienne,
          grossesse suivie — est la norme tracée, pas l&apos;exception ignorée.
        </p>
      </div>

      {/* KPIs de caisse */}
      <div className="grid gap-3 grid-cols-2 lg:grid-cols-4">
        <Card>
          <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-sm"><Users className="h-4 w-4 text-amber-600" /> En attente</CardTitle></CardHeader>
          <CardContent><p className="text-3xl font-bold text-amber-600">{enAttente.length}</p><p className="text-xs text-muted-foreground">patient(s) dans la file</p></CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-sm"><BadgeCheck className="h-4 w-4 text-emerald-600" /> Encaissés</CardTitle></CardHeader>
          <CardContent><p className="text-3xl font-bold text-emerald-600">{encaisses.length}</p><p className="text-xs text-muted-foreground">ticket(s) réglé(s) du jour</p></CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-sm"><HeartHandshake className="h-4 w-4 text-sky-600" /> Exonérations</CardTitle></CardHeader>
          <CardContent><p className="text-3xl font-bold text-sky-600">{exonerations.length}</p><p className="text-xs text-muted-foreground">gratuité tracée (motif exigé)</p></CardContent>
        </Card>
        <Card>
          <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-sm"><Banknote className="h-4 w-4" /> Recette du jour</CardTitle></CardHeader>
          <CardContent><p className="text-3xl font-bold">{formatXof(recette)}</p><p className="text-xs text-muted-foreground">espèces encaissées</p></CardContent>
        </Card>
      </div>

      {/* Ouverture d'un ticket */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <HandCoins className="h-5 w-5" /> Ouvrir le ticket d&apos;accès
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {!peutInitier && (
            <p className="text-sm text-muted-foreground">
              Lecture seule : votre rôle ne porte pas <code>paiement:initier</code> —
              seuls l&apos;agent financier (caissier), l&apos;ICP et l&apos;admin règlent la caisse.
            </p>
          )}
          <div className="grid gap-3 md:grid-cols-3">
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="caisse-recherche">Patient (nom, PH, téléphone)</Label>
              <div className="relative">
                <Search className="absolute left-2.5 top-2.5 h-4 w-4 text-muted-foreground" aria-hidden />
                <Input
                  id="caisse-recherche"
                  className="pl-8"
                  placeholder="TRAORE, PH-2025-000158, 76…"
                  value={recherche}
                  onChange={(e) => { setRecherche(e.target.value); setPatientChoisi(null); }}
                />
              </div>
              {resultats.length > 0 && !patientChoisi && (
                <ul className="mt-1 divide-y rounded-md border" role="listbox">
                  {resultats.map((p) => (
                    <li key={p.id}>
                      <button
                        type="button"
                        className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-muted"
                        onClick={() => { setPatientChoisi(p.id); setRecherche(`${p.name.family} ${p.name.given}`); }}
                      >
                        <span>{p.name.family} {p.name.given}</span>
                        <span className="font-mono text-[11px] text-muted-foreground">{p.phReference}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {patientChoisi && (
                <p className="text-xs text-emerald-700">Patient sélectionné — prêt à ouvrir le ticket.</p>
              )}
            </div>
            <div className="space-y-2">
              <Label htmlFor="caisse-montant">Montant (XOF)</Label>
              <Input
                id="caisse-montant"
                inputMode="numeric"
                value={montant}
                onChange={(e) => setMontant(e.target.value.replace(/[^0-9]/g, ""))}
                disabled={!peutInitier}
              />
              <p className="text-xs text-muted-foreground">Ticket modérateur usuel : 1 000 XOF.</p>
            </div>
          </div>
          <Button onClick={ouvrir} disabled={!peutInitier || enCours || !patientChoisi}>
            {enCours && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
            Ouvrir le ticket du jour
          </Button>
        </CardContent>
      </Card>

      {/* File d'attente */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">File d&apos;attente — tickets en attente du jour</CardTitle>
        </CardHeader>
        <CardContent>
          {enAttente.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              Aucun patient en attente : la file de la caisse est vide.
            </p>
          ) : (
            <ul className="space-y-3">
              {enAttente.map((t) => (
                <li key={t.id} className="rounded-lg border p-3 sm:flex sm:items-center sm:justify-between sm:gap-4">
                  <div className="min-w-0">
                    <div className="flex items-center gap-2">
                      <IconeStatut statut={t.statut} />
                      <p className="truncate font-medium">
                        {t.patientName ? `${t.patientName.family} ${t.patientName.given}` : t.patientId}
                      </p>
                      <LibelleStatut statut={t.statut} />
                    </div>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {formatXof(t.montantXof)} · ouvert {new Date(t.createdAt).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}
                    </p>
                  </div>
                  {peutInitier && (
                    <div className="mt-3 flex flex-wrap gap-2 sm:mt-0">
                      <Button size="sm" onClick={() => void encaisser(t)} disabled={enCours}>
                        <Banknote className="mr-1.5 h-4 w-4" aria-hidden /> Encaisser
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => { setTicketExonerer(t); setMotif(""); }}>
                        <HeartHandshake className="mr-1.5 h-4 w-4" aria-hidden /> Exonérer
                      </Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* Registre du jour */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Registre du jour — tickets réglés</CardTitle>
        </CardHeader>
        <CardContent>
          {encaisses.length + exonerations.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">
              Aucun ticket réglé aujourd&apos;hui pour {structure}.
            </p>
          ) : (
            <ul className="space-y-2">
              {[...encaisses, ...exonerations].map((t) => (
                <li key={t.id} className="rounded-lg border p-3 sm:flex sm:items-center sm:justify-between">
                  <div className="flex items-center gap-2">
                    <IconeStatut statut={t.statut} />
                    <p className="truncate font-medium">
                      {t.patientName ? `${t.patientName.family} ${t.patientName.given}` : t.patientId}
                    </p>
                    <LibelleStatut statut={t.statut} />
                  </div>
                  <div className="text-xs text-muted-foreground sm:text-right">
                    {t.statut === "paye" && t.encaisseLe
                      ? `Encaissé ${formatXof(t.montantXof)} à ${new Date(t.encaisseLe).toLocaleTimeString("fr-FR", { hour: "2-digit", minute: "2-digit" })}`
                      : t.exonerationNature
                        ? `${libelleNature(t.exonerationNature)} — ${t.exonerationMotif}`
                        : ""}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {/* Dialog d'exonération (inline, sans Dialog lourd — panneau conditionnel) */}
      {ticketExonerer && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true">
          <Card className="w-full max-w-md">
            <CardHeader>
              <CardTitle className="text-base">Exonération — {ticketExonerer.patientName?.family} {ticketExonerer.patientName?.given}</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Label>Nature (I5 — la gratuité tracée)</Label>
                <Select value={nature} onValueChange={(v) => setNature(v as ExonerationNature)}>
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {EXONERATION_NATURES.map((n) => (
                      <SelectItem key={n.value} value={n.value}>{n.label}</SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-2">
                <Label htmlFor="exoneration-motif">Motif (OBLIGATOIRE)</Label>
                <Input
                  id="exoneration-motif"
                  placeholder="Attestation N0123-2025 du maire de Saaba…"
                  value={motif}
                  onChange={(e) => setMotif(e.target.value)}
                />
                <p className="text-xs text-muted-foreground">
                  L&apos;exonération est forward-only et auditée : elle ne peut plus être
                  annulée une fois enregistrée.
                </p>
              </div>
              <div className="flex justify-end gap-2">
                <Button variant="outline" onClick={() => setTicketExonerer(null)} disabled={enCours}>Annuler</Button>
                <Button onClick={() => void validerExoneration()} disabled={enCours}>
                  {enCours && <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />}
                  Enregistrer l&apos;exonération
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>
      )}
    </div>
  );
}

/* Réexport utilitaire pour la vue consultation (renvoi vers la caisse). */
export function messageFraisAcces(error: unknown): string | null {
  if (error instanceof ApiError && error.status === 402) {
    return typeof error.body.detail === "string"
      ? error.body.detail
      : "Passage à la caisse obligatoire avant la consultation.";
  }
  return null;
}
