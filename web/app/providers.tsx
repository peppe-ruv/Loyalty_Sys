"use client";

import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState } from "react";
import { LhError } from "@/lib/api/client";
import { redirectToLogin } from "@/lib/auth/browser";

/** Sessione del BFF scaduta (solo profilo enterprise, 401 `UNAUTHENTICATED`): si torna al login e poi qui. */
function onError(error: unknown) {
  if (error instanceof LhError && error.unauthenticated) redirectToLogin();
}

/** Provider di TanStack Query (docs/07 §1). staleTime di default prudente; le viste lo affinano. */
export function Providers({ children }: { children: React.ReactNode }) {
  const [client] = useState(
    () =>
      new QueryClient({
        queryCache: new QueryCache({ onError }),
        mutationCache: new MutationCache({ onError }),
        defaultOptions: {
          queries: {
            staleTime: 15_000,
            // Una sessione scaduta non si ripara riprovando.
            retry: (failures, error) => !(error instanceof LhError && error.unauthenticated) && failures < 1,
            refetchOnWindowFocus: false,
          },
        },
      }),
  );
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
