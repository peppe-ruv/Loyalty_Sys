import { Card, CardBody } from "@/components/ui/card";
import { it } from "@/lib/i18n/it";

// Backoffice — indice (shell M0.6). Le schermate BO-xx arrivano da M1 (docs/08).
export default function BackofficeHome() {
  return (
    <Card>
      <CardBody className="pt-4">
        <h1 className="mb-2 text-xl font-semibold">Backoffice</h1>
        <p className="max-w-xl text-sm text-[var(--color-bo-ink-2)]">{it.shell.backofficeEmpty}</p>
      </CardBody>
    </Card>
  );
}
