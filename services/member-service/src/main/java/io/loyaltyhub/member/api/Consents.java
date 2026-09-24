package io.loyaltyhub.member.api;

/** Consensi del membro (docs/servizi/member-service.md §2, colonna {@code consents}): {@code null} = non toccare. */
public record Consents(Boolean marketing, Boolean profiling) {

    public static final Consents NONE = new Consents(false, false);

    /** Sovrappone i valori presenti a quelli correnti. */
    public Consents over(Consents current) {
        Consents base = current != null ? current : NONE;
        return new Consents(marketing != null ? marketing : base.marketing(),
                profiling != null ? profiling : base.profiling());
    }

    public String toJson() {
        return "{\"marketing\":" + Boolean.TRUE.equals(marketing) + ",\"profiling\":" + Boolean.TRUE.equals(profiling) + "}";
    }
}
