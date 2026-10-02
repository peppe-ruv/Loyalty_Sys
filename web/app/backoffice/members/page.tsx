import { MembersList } from "@/components/bo/MembersList";

// BO-02 Membri (docs/08 §BO-02): la lista è un componente client; l'indirizzo della console dei membri (ADR-051 dec. 7)
// arriva dal layout server tramite il contesto, mai da NEXT_PUBLIC.
export default function MembersPage() {
  return <MembersList />;
}
