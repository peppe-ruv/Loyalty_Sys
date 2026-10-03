import { notFound } from "next/navigation";
import { getViewer } from "@/lib/auth/viewer";

// V11 (ADR-051, docs/18 M8.14): le schermate Demo (BO-28 simulatore, BO-29 scenari, BO-30 console) chiamano /v1/demo, che
// nel profilo `enterprise` non esiste. Un indirizzo digitato a mano non deve mostrare una pagina che fallisce: 404.
export default async function DemoLayout({ children }: { children: React.ReactNode }) {
  const viewer = await getViewer();
  if (viewer.mode === "enterprise") notFound();
  return <>{children}</>;
}
