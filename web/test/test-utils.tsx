import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PersonaProvider } from "@/components/bo/PersonaContext";
import type { Role } from "@/lib/persona/personas";

export function renderWithProviders(ui: React.ReactNode, role: Role = "ADMIN") {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PersonaProvider value={{ username: "test.user", displayName: "Test User", role }}>
        {ui}
      </PersonaProvider>
    </QueryClientProvider>
  );
}
