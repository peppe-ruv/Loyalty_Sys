"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useLhMutation, useLhQuery, type LhError } from "@/lib/api/client";
import type { CouponPool, Reward, RewardBand, RewardCategory, Tier } from "@/lib/api/types";
import { QueryState } from "@/components/bo/QueryState";
import { PageHeader, CodeText } from "@/components/bo/primitives";
import { LifecycleBar } from "@/components/bo/LifecycleBar";
import { useApprovalPolicy, useReviewEntry } from "@/lib/approvals/usePolicy";
import { requiresApproval } from "@/lib/approvals/queue";
import { DuplicateButton } from "@/components/bo/DuplicateButton";
import { VersionConflict } from "@/components/bo/VersionConflict";
import { isVersionConflict, withVersion } from "@/lib/api/version";
import { RewardForm, type RewardInput } from "@/components/bo/rewards/RewardForm";
import { StockBar } from "@/components/bo/rewards/RewardBits";

// BO-10 Editor premio (docs/08 §BO-10): `/rewards/new` crea in DRAFT; `/rewards/[id]` mostra la barra del ciclo di
// vita, *Duplica* e l'editor con i blocchi di modifica per stato.
export default function RewardEditorPage() {
  const id = String(useParams().id);
  const creating = id === "new";
  const reward = useLhQuery<Reward>("reward", `/v1/rewards/${id}`, undefined, { enabled: !creating });
  const bands = useLhQuery<RewardBand[]>("reward", "/v1/reward-bands");
  const categories = useLhQuery<RewardCategory[]>("reward", "/v1/reward-categories");
  const tiers = useLhQuery<Tier[]>("wallet", "/v1/tiers");
  const pools = useLhQuery<CouponPool[]>("reward", "/v1/coupon-pools");

  return (
    <div>
      <Link href="/backoffice/rewards" className="mb-2 inline-block text-xs text-[var(--color-bo-ink-2)] hover:underline">
        ← Catalogo premi
      </Link>
      <QueryState query={bands} service="reward">
        {(bs) =>
          creating ? (
            <CreateReward bands={bs} categories={categories.data ?? []} tiers={tiers.data ?? []} pools={pools.data ?? []} />
          ) : (
            <QueryState query={reward} service="reward">
              {(r) => (
                <EditReward
                  key={`${r.id}-${r.version}-${r.status}`}
                  reward={r}
                  bands={bs}
                  categories={categories.data ?? []}
                  tiers={tiers.data ?? []}
                  pools={pools.data ?? []}
                  onChanged={() => reward.refetch()}
                />
              )}
            </QueryState>
          )
        }
      </QueryState>
    </div>
  );
}

function CreateReward({
  bands,
  categories,
  tiers,
  pools,
}: {
  bands: RewardBand[];
  categories: RewardCategory[];
  tiers: Tier[];
  pools: CouponPool[];
}) {
  const router = useRouter();
  const [error, setError] = useState<LhError | null>(null);
  const create = useLhMutation<Reward, RewardInput>("reward", "POST", () => "/v1/rewards", {
    onSuccess: (r) => router.replace(`/backoffice/rewards/${r.id}`),
  });
  return (
    <>
      <PageHeader title="Nuovo premio" subtitle="Nasce in bozza (DRAFT); lo pubblichi dalla barra del ciclo di vita." />
      <RewardForm
        reward={null}
        bands={bands}
        categories={categories}
        tiers={tiers}
        pools={pools}
        saving={create.isPending}
        error={error}
        onSubmit={(input) => {
          setError(null);
          create.mutate(input, { onError: setError });
        }}
      />
    </>
  );
}

function EditReward({
  reward,
  bands,
  categories,
  tiers,
  pools,
  onChanged,
}: {
  reward: Reward;
  bands: RewardBand[];
  categories: RewardCategory[];
  tiers: Tier[];
  pools: CouponPool[];
  onChanged: () => void;
}) {
  const policy = useApprovalPolicy();
  const review = useReviewEntry("reward", reward.id, reward.status);
  const [error, setError] = useState<LhError | null>(null);
  const [pending, setPending] = useState<RewardInput | null>(null);
  const update = useLhMutation<Reward, RewardInput & { version?: number }>("reward", "PUT", () => `/v1/rewards/${reward.id}`, {
    onSuccess: () => onChanged(),
  });
  const conflict = isVersionConflict(error);

  return (
    <>
      <PageHeader
        title={reward.name}
        actions={
          <>
            <CodeText>{reward.code}</CodeText>
            <span className="text-xs text-[var(--color-bo-ink-2)]">v{reward.version}</span>
            <DuplicateButton
              service="reward"
              path={`/v1/rewards/${reward.id}/duplicate`}
              hrefFor={(copy) => `/backoffice/rewards/${copy.id}`}
            />
          </>
        }
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <LifecycleBar
          service="reward"
          transitionsPath={`/v1/rewards/${reward.id}/transitions`}
          status={reward.status}
          approvalRequired={requiresApproval("REWARD", policy.data)}
          requiredRole={review?.requiredRole}
          submittedAt={review?.submittedAt}
          onChanged={onChanged}
        />
        <div className="w-56">
          <StockBar reward={reward} />
        </div>
      </div>
      {conflict && pending && (
        <div className="mb-3">
          <VersionConflict
            what="il premio"
            busy={update.isPending}
            onReload={onChanged}
            onOverwrite={() => {
              setError(null);
              update.mutate(pending, { onError: setError });
            }}
          />
        </div>
      )}
      <RewardForm
        reward={reward}
        bands={bands}
        categories={categories}
        tiers={tiers}
        pools={pools}
        saving={update.isPending}
        error={conflict ? null : error}
        onSubmit={(_, changed) => {
          setError(null);
          setPending(changed);
          update.mutate(withVersion(changed, reward.version), { onError: setError });
        }}
      />
    </>
  );
}
