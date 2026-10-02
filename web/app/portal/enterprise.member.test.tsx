import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemberProvider } from "@/components/portal/MemberContext";
import { PortalShell } from "@/components/portal/PortalShell";
import { InboxBell } from "@/components/portal/InboxBell";
import { PopupHost } from "@/components/portal/PopupHost";
import { checkPortalBody, hasMemberIdInPath, hasMemberIdInQuery } from "@/lib/auth/memberScope";
import PortalHome from "./page";
import EarnPage from "./earn/page";
import RewardsPage from "./rewards/page";
import RewardPage from "./rewards/[code]/page";
import MyRewardsPage from "./my-rewards/page";
import PlayPage from "./play/page";
import PlayContestPage from "./play/[code]/page";
import AchievementsPage from "./achievements/page";
import LeaderboardPage from "./leaderboard/page";
import InboxPage from "./inbox/page";
import InvitePage from "./invite/page";
import ProfilePage from "./profile/page";
import ActivityPage from "./activity/page";

// Il portale intero in enterprise (F2-SEC-09, PT-01…PT-16, ADR-048, ADR-051; regole 6-bis e 18): ogni schermata e la
// shell chiamano solo API del portale senza memberId — né nel percorso, né in query, né nel corpo — e nessuna API del
// backoffice (realm operatori: un membro non ne ha la sessione). In demo le stesse schermate non cambiano
// (test di pagina e testbook esistenti). Qui i servizi dormono (503): bastano le richieste di ogni vista al montaggio.

vi.mock("next/navigation", () => ({
  usePathname: () => "/portal",
  useParams: () => ({ code: "RWD-1" }),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));
vi.mock("@/components/shared/ThemeContext", () => ({
  usePortalTheme: () => ({ programName: "Club Aurora", heroTitle: null }),
  ThemeProvider: ({ children }: { children: React.ReactNode }) => children,
}));

const SECRET_ID = "MBR-000777";
let calls: { url: string; init?: RequestInit }[];

beforeEach(() => {
  calls = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, init });
      return new Response(JSON.stringify({ type: "SERVICE_ASLEEP" }), { status: 503 });
    }),
  );
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function mount(ui: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemberProvider memberId={SECRET_ID} enterprise>
        <PortalShell demo={false}>{ui}</PortalShell>
      </MemberProvider>
    </QueryClientProvider>,
  );
}

const SCREENS: [string, () => React.ReactNode][] = [
  ["PT-01 Home", () => <PortalHome />],
  ["PT-02 Guadagna", () => <EarnPage />],
  ["PT-03 Premi", () => <RewardsPage />],
  ["PT-04 Premio", () => <RewardPage />],
  ["PT-13 I miei premi", () => <MyRewardsPage />],
  ["PT-05 Gioca", () => <PlayPage />],
  ["PT-06 Concorso", () => <PlayContestPage />],
  ["PT-07 Traguardi", () => <AchievementsPage />],
  ["PT-10 Classifica", () => <LeaderboardPage />],
  ["PT-12 Notifiche", () => <InboxPage />],
  ["PT-11 Porta un amico", () => <InvitePage />],
  ["PT-08 Profilo", () => <ProfilePage />],
  ["PT-09 Attività", () => <ActivityPage />],
  ["campanella e pop-up", () => (
    <>
      <InboxBell />
      <PopupHost />
    </>
  )],
];

describe("portale in enterprise: il membro viene solo dal token", () => {
  it.each(SCREENS)("%s", async (_name, ui) => {
    mount(ui());
    await waitFor(() => expect(calls.length).toBeGreaterThan(0));
    // Lascia partire anche le richieste dei componenti figli.
    await new Promise((r) => setTimeout(r, 30));
    for (const { url, init } of calls) {
      const parsed = new URL(url, "http://x");
      const segments = parsed.pathname.split("/").filter(Boolean).slice(3); // /api/lh/<servizio>/…
      const where = `${init?.method ?? "GET"} ${url}`;
      expect(url, where).not.toContain(SECRET_ID);
      expect(hasMemberIdInQuery(parsed.searchParams), where).toBe(false);
      expect(hasMemberIdInPath(segments), where).toBe(false);
      // Solo API del portale (sessione del membro): niente `/v1/members`, `/v1/editions`, `/v1/reward-categories`…
      expect(segments.slice(0, 2).join("/"), where).toBe("v1/portal");
      if (init?.body) {
        const bytes = new TextEncoder().encode(String(init.body));
        expect(checkPortalBody("application/json", bytes), where).toBeNull();
      }
    }
  });
});
