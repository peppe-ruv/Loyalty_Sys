import { cn } from "@/lib/cn";

type Variant = "primary" | "ghost";

interface Props extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

export function Button({ className, variant = "primary", ...props }: Props) {
  const base =
    "inline-flex items-center justify-center gap-2 rounded-md px-4 py-2 text-sm font-semibold transition-colors disabled:opacity-50 disabled:cursor-not-allowed";
  const styles: Record<Variant, string> = {
    primary: "bg-[var(--color-bo-accent)] text-white hover:opacity-90",
    ghost: "border border-[var(--color-bo-border)] text-[var(--color-bo-ink)] hover:bg-[var(--color-bo-bg)]",
  };
  return <button className={cn(base, styles[variant], className)} {...props} />;
}
