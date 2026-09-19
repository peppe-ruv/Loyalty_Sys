import Link from "next/link";
import { Card, CardBody } from "@/components/ui/card";
import { visibleNav } from "@/lib/nav";

// Indice del backoffice (docs/08 §1). La dashboard vera (BO-01) arriva con M2.
export default function BackofficeHome() {
  const groups = visibleNav();
  return (
    <div>
      <h1 className="mb-1 text-xl font-semibold">Backoffice</h1>
      <p className="mb-6 max-w-2xl text-sm text-[var(--color-bo-ink-2)]">
        Gestione del programma. La panoramica (BO-01) e altre schermate compaiono con le milestone successive.
      </p>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {groups.flatMap((g) =>
          g.items.map((item) => (
            <Link key={item.id} href={item.href}>
              <Card className="h-full transition hover:border-[var(--color-bo-accent)]">
                <CardBody className="pt-4">
                  <p className="text-xs text-[var(--color-bo-ink-2)]">
                    {g.label} · {item.id}
                  </p>
                  <p className="mt-1 font-medium">{item.label}</p>
                </CardBody>
              </Card>
            </Link>
          )),
        )}
      </div>
    </div>
  );
}
