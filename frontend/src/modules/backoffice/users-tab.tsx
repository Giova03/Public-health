"use client";

/**
 * Module Back-office (E6) — onglet Utilisateurs : comptes du personnel,
 * rôles (labels complets du référentiel), MFA et dernière activité.
 * Table sur desktop, cartes empilées sur mobile (lecture seule).
 */

import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import type { Variants } from "framer-motion";
import { KeyRound, ShieldCheck } from "lucide-react";
import { BackofficeEmpty } from "./backoffice-empty";
import {
  MFA_ABSENT_BADGE_CLASS,
  MFA_ENABLED_BADGE_CLASS,
  ROLE_BADGE_CLASS,
  ROLE_FILTER_OPTIONS,
  type RoleFilterValue,
  initialsOf,
  relativeFromNow,
} from "./backoffice-helpers";
import { Badge } from "@/components/ui/badge";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useIsHydrated } from "@/hooks/use-hydrated";
import { STAFF_ROLE_LABELS } from "@/lib/demo/reference";
import type { StaffRole, StaffUser } from "@/lib/types";
import { formatDate, formatTime } from "@/lib/types";
import { cn } from "@/lib/utils";

const LIST_VARIANTS: Variants = {
  hidden: {},
  visible: { transition: { staggerChildren: 0.04 } },
};

const CARD_VARIANTS: Variants = {
  hidden: { opacity: 0, y: 6 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.16, ease: "easeOut" },
  },
};

interface UsersTabProps {
  users: StaffUser[];
  loading: boolean;
  offline: boolean;
  error: string | null;
}

export function UsersTab({ users, loading, offline, error }: UsersTabProps) {
  const [roleFilter, setRoleFilter] = useState<RoleFilterValue>("ALL");
  const hydrated = useIsHydrated();
  /** Heure de référence pour le temps relatif (jamais pendant le SSR). */
  const now = useMemo(() => (hydrated ? new Date() : null), [hydrated]);

  const filtered = useMemo(
    () =>
      roleFilter === "ALL"
        ? users
        : users.filter((user) => user.role === roleFilter),
    [users, roleFilter],
  );

  if (users.length === 0) {
    return <BackofficeEmpty loading={loading} offline={offline} error={error} />;
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <Select
          value={roleFilter}
          onValueChange={(value) => setRoleFilter(value as RoleFilterValue)}
        >
          <SelectTrigger
            id="role-filter"
            className="h-10 w-full sm:w-64"
            aria-label="Filtrer les comptes par rôle"
          >
            <SelectValue placeholder="Tous les rôles" />
          </SelectTrigger>
          <SelectContent>
            {ROLE_FILTER_OPTIONS.map((option) => (
              <SelectItem key={option.value} value={option.value}>
                {option.label}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <p className="tnum text-xs text-muted-foreground">
          {filtered.length} / {users.length} comptes
        </p>
      </div>

      {filtered.length === 0 ? (
        <p
          className="rounded-xl border border-dashed p-6 text-center text-sm text-muted-foreground"
          role="status"
        >
          Aucun compte pour ce rôle.
        </p>
      ) : (
        <>
          {/* Mobile : cartes empilées */}
          <motion.ul
            variants={LIST_VARIANTS}
            initial="hidden"
            animate="visible"
            className="space-y-2 md:hidden"
            aria-label="Comptes du personnel"
          >
            {filtered.map((user) => (
              <motion.li key={user.id} variants={CARD_VARIANTS}>
                <UserCard user={user} now={now} />
              </motion.li>
            ))}
          </motion.ul>

          {/* Desktop : table */}
          <div className="hidden overflow-hidden rounded-xl border md:block">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/50 hover:bg-muted/50">
                  <TableHead>Compte</TableHead>
                  <TableHead>Rôle</TableHead>
                  <TableHead>Structure</TableHead>
                  <TableHead>MFA</TableHead>
                  <TableHead>Dernière activité</TableHead>
                  <TableHead>Statut</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {filtered.map((user) => (
                  <UserRow key={user.id} user={user} now={now} />
                ))}
              </TableBody>
            </Table>
          </div>
        </>
      )}
    </div>
  );
}

/* ------------------------------ Badges --------------------------------- */

function RoleBadge({ role }: { role: StaffRole }) {
  return (
    <Badge
      variant="outline"
      className={cn("max-w-48 truncate", ROLE_BADGE_CLASS[role])}
      title={STAFF_ROLE_LABELS[role]}
    >
      {STAFF_ROLE_LABELS[role]}
    </Badge>
  );
}

function MfaBadge({ enabled }: { enabled: boolean }) {
  return enabled ? (
    <Badge variant="outline" className={cn("gap-1", MFA_ENABLED_BADGE_CLASS)}>
      <ShieldCheck aria-hidden="true" />
      MFA
    </Badge>
  ) : (
    <Badge variant="outline" className={cn("gap-1", MFA_ABSENT_BADGE_CLASS)}>
      <KeyRound aria-hidden="true" />
      MFA absent
    </Badge>
  );
}

function ActiveBadge({ active }: { active: boolean }) {
  return (
    <Badge
      variant="outline"
      className={
        active
          ? "border-emerald-600/30 bg-emerald-500/10 text-emerald-600 dark:border-emerald-400/30 dark:text-emerald-400"
          : "border-border bg-muted/60 text-muted-foreground"
      }
    >
      {active ? "Actif" : "Inactif"}
    </Badge>
  );
}

function LastSeen({ iso, now }: { iso: string; now: Date | null }) {
  const relative = now ? relativeFromNow(iso, now) : null;
  return (
    <div className="min-w-0">
      <p className="tnum text-sm">
        {formatDate(iso)} · {formatTime(iso)}
      </p>
      {relative && (
        <p className="text-xs text-muted-foreground">{relative}</p>
      )}
    </div>
  );
}

/* ------------------------------ Lignes --------------------------------- */

function UserRow({ user, now }: { user: StaffUser; now: Date | null }) {
  return (
    <TableRow>
      <TableCell className="font-medium">
        <span className="flex items-center gap-2">
          <span
            className="flex size-7 shrink-0 items-center justify-center rounded-full bg-primary/10 text-[11px] font-semibold text-primary"
            aria-hidden="true"
          >
            {initialsOf(user.fullName)}
          </span>
          <span className="truncate" title={user.fullName}>
            {user.fullName}
          </span>
        </span>
      </TableCell>
      <TableCell>
        <RoleBadge role={user.role} />
      </TableCell>
      <TableCell className="text-muted-foreground">
        {user.facility}
      </TableCell>
      <TableCell>
        <MfaBadge enabled={user.mfaEnabled} />
      </TableCell>
      <TableCell>
        <LastSeen iso={user.lastSeenAt} now={now} />
      </TableCell>
      <TableCell>
        <ActiveBadge active={user.active} />
      </TableCell>
    </TableRow>
  );
}

function UserCard({ user, now }: { user: StaffUser; now: Date | null }) {
  return (
    <div className="rounded-xl border p-3">
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2.5">
          <span
            className="flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-xs font-semibold text-primary"
            aria-hidden="true"
          >
            {initialsOf(user.fullName)}
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-medium" title={user.fullName}>
              {user.fullName}
            </p>
            <p className="truncate text-xs text-muted-foreground">
              {user.facility}
            </p>
          </div>
        </div>
        <ActiveBadge active={user.active} />
      </div>
      <div className="mt-2.5 flex flex-wrap items-center gap-1.5">
        <RoleBadge role={user.role} />
        <MfaBadge enabled={user.mfaEnabled} />
      </div>
      <p className="mt-2 flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
        <span className="tnum" title={user.lastSeenAt}>
          {formatDate(user.lastSeenAt)} · {formatTime(user.lastSeenAt)}
        </span>
        {now && relativeFromNow(user.lastSeenAt, now) && (
          <>
            <span aria-hidden="true">·</span>
            <span>{relativeFromNow(user.lastSeenAt, now)}</span>
          </>
        )}
      </p>
    </div>
  );
}
