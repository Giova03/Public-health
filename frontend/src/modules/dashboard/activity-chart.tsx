"use client";

/**
 * Module Tableau de bord — activité des 7 derniers jours (recharts).
 * Deux séries issues du miroir : patients créés / paiements initiés.
 * Thème respecté : couleurs via tokens CSS (var(--chart-1/2)).
 */

import { Area, AreaChart, CartesianGrid, XAxis, YAxis } from "recharts";
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import type { ActivityPoint } from "./helpers";

const chartConfig = {
  patients: { label: "Patients créés", color: "var(--chart-1)" },
  paiements: { label: "Paiements initiés", color: "var(--chart-2)" },
} satisfies ChartConfig;

export function ActivityChart({ data }: { data: ActivityPoint[] }) {
  const hasData = data.some((d) => d.patients + d.paiements > 0);

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Activité du poste</CardTitle>
        <CardDescription>
          7 derniers jours · dossiers et encaissements du miroir local
        </CardDescription>
        {hasData && (
          <div className="flex items-center gap-4 pt-1">
            <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <span
                className="h-2 w-2 rounded-full"
                style={{ backgroundColor: "var(--chart-1)" }}
                aria-hidden="true"
              />
              Patients créés
            </span>
            <span className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <span
                className="h-2 w-2 rounded-full"
                style={{ backgroundColor: "var(--chart-2)" }}
                aria-hidden="true"
              />
              Paiements initiés
            </span>
          </div>
        )}
      </CardHeader>
      <CardContent>
        {hasData ? (
          <ChartContainer
            config={chartConfig}
            className="aspect-auto h-[200px] w-full"
          >
            <AreaChart
              data={data}
              margin={{ top: 8, right: 8, left: 0, bottom: 0 }}
            >
              <CartesianGrid
                vertical={false}
                strokeDasharray="3 3"
                stroke="var(--border)"
              />
              <XAxis
                dataKey="label"
                tickLine={false}
                axisLine={false}
                tickMargin={8}
                tick={{ fontSize: 11 }}
                interval="preserveStartEnd"
              />
              <YAxis
                allowDecimals={false}
                width={28}
                tickLine={false}
                axisLine={false}
                tick={{ fontSize: 11 }}
              />
              <ChartTooltip content={<ChartTooltipContent indicator="line" />} />
              <Area
                dataKey="patients"
                name="Patients créés"
                type="monotone"
                stroke="var(--chart-1)"
                fill="var(--chart-1)"
                fillOpacity={0.12}
                strokeWidth={2}
              />
              <Area
                dataKey="paiements"
                name="Paiements initiés"
                type="monotone"
                stroke="var(--chart-2)"
                fill="var(--chart-2)"
                fillOpacity={0.12}
                strokeWidth={2}
              />
            </AreaChart>
          </ChartContainer>
        ) : (
          <div className="space-y-3" role="status">
            <div className="flex h-[200px] flex-col justify-center gap-4">
              <Skeleton className="h-4 w-1/2" />
              <Skeleton className="h-8 w-3/4" />
              <Skeleton className="h-12 w-full" />
              <Skeleton className="h-6 w-2/3" />
            </div>
            <p className="text-xs text-muted-foreground">
              Aucune activité enregistrée sur les 7 derniers jours — les
              données arriveront à la première synchronisation.
            </p>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
