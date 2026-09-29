package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberPrincipal.Origin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@link MemberPrincipal}: matrice modo × metodo (Q-410, Q-553, ADR-048). In demo gli errori sono identici a quelli di
 * oggi; in enterprise il membro è solo quello del token e ogni indicazione del chiamante è un errore.
 */
class MemberPrincipalTest {

    private static final MemberPrincipal TOKEN = MemberPrincipal.token("MBR-000101");
    private static final MemberPrincipal DEMO_WITH_ID = MemberPrincipal.demo("MBR-000003");
    private static final MemberPrincipal DEMO_NO_ID = MemberPrincipal.demo(null);
    private static final MemberPrincipal NONE = MemberPrincipal.none();

    private static void assertError(Runnable call, int status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(LhException.class, e -> {
            assertThat(e.status().value()).isEqualTo(status);
            assertThat(e.code()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("idOrNull: TOKEN l'id, DEMO l'id esplicito o null, NONE null")
    void idOrNull() {
        assertThat(TOKEN.idOrNull()).isEqualTo("MBR-000101");
        assertThat(DEMO_WITH_ID.idOrNull()).isEqualTo("MBR-000003");
        assertThat(DEMO_NO_ID.idOrNull()).isNull();
        assertThat(NONE.idOrNull()).isNull();
        assertThat(TOKEN.origin()).isEqualTo(Origin.TOKEN);
        assertThat(DEMO_NO_ID.present()).isFalse();
        assertThat(TOKEN.present()).isTrue();
    }

    @Test
    @DisplayName("invarianti: un principal TOKEN ha sempre l'id, NONE non lo ha")
    void invariants() {
        assertThatThrownBy(() -> MemberPrincipal.token(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberPrincipal.token(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberPrincipal("MBR-1", Origin.NONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberPrincipal("MBR-1", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("requireParam: con un id lo restituisce; DEMO senza id ⇒ 400 BAD_REQUEST identico a quello di Spring; NONE ⇒ 403 MEMBER_REQUIRED")
    void requireParam() {
        assertThat(TOKEN.requireParam()).isEqualTo("MBR-000101");
        assertThat(DEMO_WITH_ID.requireParam()).isEqualTo("MBR-000003");
        assertThatThrownBy(DEMO_NO_ID::requireParam).isInstanceOfSatisfying(LhException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("BAD_REQUEST");
            assertThat(e.typeSuffix()).isEqualTo("bad-request");
            assertThat(e.getMessage()).isEqualTo("Parametro obbligatorio assente: memberId");
        });
        assertError(NONE::requireParam, 403, "MEMBER_REQUIRED");
    }

    @Test
    @DisplayName("merge, TOKEN: un memberId del corpo, anche il proprio, ⇒ 400 MEMBER_FROM_TOKEN; assente ⇒ l'id del token")
    void mergeToken() {
        assertThat(TOKEN.merge(null)).isEqualTo("MBR-000101");
        assertError(() -> TOKEN.merge("MBR-000101"), 400, "MEMBER_FROM_TOKEN");
        assertError(() -> TOKEN.merge("MBR-000102"), 400, "MEMBER_FROM_TOKEN");
        assertError(() -> TOKEN.merge(""), 400, "MEMBER_FROM_TOKEN");
    }

    @Test
    @DisplayName("merge, DEMO: nullo o uguale ⇒ l'id; solo nel corpo ⇒ quello del corpo; diversi ⇒ 400 MEMBER_MISMATCH")
    void mergeDemo() {
        assertThat(DEMO_WITH_ID.merge(null)).isEqualTo("MBR-000003");
        assertThat(DEMO_WITH_ID.merge("MBR-000003")).isEqualTo("MBR-000003");
        assertThat(DEMO_NO_ID.merge("MBR-000004")).isEqualTo("MBR-000004");
        assertThat(DEMO_NO_ID.merge(null)).isNull();
        assertError(() -> DEMO_WITH_ID.merge("MBR-000004"), 400, "MEMBER_MISMATCH");
    }

    @Test
    @DisplayName("merge, NONE: il corpo non può portare un membro")
    void mergeNone() {
        assertThat(NONE.merge(null)).isNull();
        assertError(() -> NONE.merge("MBR-000004"), 400, "MEMBER_FROM_TOKEN");
    }

    @Test
    @DisplayName("checkOwner: un membro presente e diverso dal proprietario ⇒ 404 NOT_FOUND; il proprio o nessun id in demo passa; NONE (operatore) mai")
    void checkOwner() {
        TOKEN.checkOwner("MBR-000101");
        DEMO_WITH_ID.checkOwner("MBR-000003");
        DEMO_NO_ID.checkOwner("MBR-000004"); // demo senza id: la proprietà resta facoltativa come oggi
        assertError(() -> TOKEN.checkOwner("MBR-000102"), 404, "NOT_FOUND");
        assertError(() -> TOKEN.checkOwner(null), 404, "NOT_FOUND");
        assertError(() -> DEMO_WITH_ID.checkOwner("MBR-000004"), 404, "NOT_FOUND");
        // NONE (un operatore su un handler OPTIONAL, solo oidc) non è il proprietario di nulla: mai l'oggetto di un
        // membro attraverso l'API del membro, nemmeno se l'owner è nullo.
        assertError(() -> NONE.checkOwner("MBR-000004"), 404, "NOT_FOUND");
        assertError(() -> NONE.checkOwner(null), 404, "NOT_FOUND");
        assertThatThrownBy(() -> NONE.checkOwner("MBR-000004", "Richiesta non trovata"))
                .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.getMessage()).isEqualTo("Richiesta non trovata"));
        assertThatThrownBy(() -> TOKEN.checkOwner("MBR-000102", "Richiesta non trovata"))
                .isInstanceOfSatisfying(LhException.class, e -> assertThat(e.getMessage()).isEqualTo("Richiesta non trovata"));
    }

    @Test
    @DisplayName("il detail di 404 e degli errori del membro non contiene l'id del proprietario né quello del membro")
    void detailsNeverEchoIds() {
        assertThatThrownBy(() -> TOKEN.checkOwner("MBR-000102"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("MBR-000102").doesNotContain("MBR-000101"));
        assertThatThrownBy(() -> TOKEN.merge("MBR-000102"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("MBR-000102"));
        assertThatThrownBy(() -> DEMO_WITH_ID.merge("MBR-000004"))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("MBR-000004").doesNotContain("MBR-000003"));
    }

    @Test
    @DisplayName("MemberSubject: demo senza account; toString maschera emittente e soggetto (regola 20)")
    void memberSubject() {
        MemberSubject demo = MemberSubject.demo();
        assertThat(demo.isDemo()).isTrue();
        assertThat(demo.linked()).isFalse();
        MemberSubject token = new MemberSubject("https://idp.example.test/realms/x", "sub-segreto", "a".repeat(64), null);
        assertThat(token.isDemo()).isFalse();
        assertThat(token.toString()).isEqualTo("MemberSubject[***]").doesNotContain("sub-segreto").doesNotContain("idp");
        assertThat(new MemberSubject("i", "s", "r", "MBR-1").linked()).isTrue();
        assertThat(new MemberTokenClaims("https://idp", "sub-segreto").toString()).doesNotContain("sub-segreto");
    }

    @Test
    @DisplayName("ActorContext del membro: ANALYST con username l'id; member:- se non risolto; mai da preferred_username")
    void memberActor() {
        assertThat(ActorContext.member("MBR-000101").asActorString()).isEqualTo("member:MBR-000101");
        assertThat(ActorContext.member(null).asActorString()).isEqualTo("member:-");
        assertThat(ActorContext.member(" ").asActorString()).isEqualTo("member:-");
        assertThat(ActorContext.member("MBR-000101").role()).isEqualTo(Role.ANALYST);
        assertThat(ActorContext.member("MBR-000101").member()).isTrue();
        // Il record non-membro resta com'era: due argomenti, forma RUOLO:username.
        assertThat(new ActorContext(Role.CARE, "paolo").member()).isFalse();
        assertThat(new ActorContext(Role.CARE, "paolo").asActorString()).isEqualTo("CARE:paolo");
        assertThat(new ActorContext(Role.CARE, "paolo")).isEqualTo(new ActorContext(Role.CARE, "paolo", false));
    }

    @Test
    @DisplayName("isMemberIdName: qualunque grafia, anche come ultimo segmento di un percorso di proprietà")
    void memberIdNames() {
        for (String yes : new String[] {"memberId", "MEMBERID", "memberid", "member_id", "Member-Id", "filter.memberId",
                "items[0].memberId", "items[0][1].memberId", "a.b.MEMBER_ID", "memberId[]", "memberId[3]", "MEMBER_ID[x]",
                // più suffissi d'indice in coda e un « [ » dentro l'indice: si tolgono tutti
                "memberId[0][1]", "memberId[][]", "memberId[a[b]", "filter.memberId[0][1]",
                // prefissi del binder di Spring: !campo (valore di default) e _campo (marcatore), anche annidati
                "!memberId", "!member_id", "!MEMBER-ID", "_memberId", "!_memberId", "_!memberId", "!filter.memberId",
                "filter.!memberId", "!items[0].memberId", "!memberId[]", "!memberId[0]", "_!memberId[0][1]"}) {
            assertThat(MemberPrincipals.isMemberIdName(yes)).as(yes).isTrue();
        }
        for (String no : new String[] {"member", "memberIds", "id", "ownerMemberId2", "", "memberIdentity", "codes", "!", "_",
                "!member", "!memberIds", "!id", "!codes", "xmemberId", "memberIdx", "memberIds[0]", "memberId[0]x", "memberId[",
                "memberId]", "memberId[0]]", "[memberId]", "[memberId", "member[0]Id", "items[0].id", "items[0]", "[]", "[", "]"}) {
            assertThat(MemberPrincipals.isMemberIdName(no)).as(no).isFalse();
        }
        assertThat(MemberPrincipals.isMemberIdName(null)).isFalse();
    }

    /**
     * Il nome di un parametro arriva dalla richiesta e non ha un limite di lunghezza: la sua analisi è lineare (nessuna
     * espressione regolare con backtracking), quindi una sequenza di {@code [} o di suffissi non rallenta il servizio.
     */
    @Test
    @DisplayName("isMemberIdName: nomi patologici molto lunghi si risolvono subito e con l'esito giusto")
    void memberIdNamesAreLinear() {
        int n = 100_000;
        Duration limit = Duration.ofSeconds(1);
        String brackets = "[".repeat(n);
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName(brackets + "memberId"))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("memberId" + brackets))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName(brackets))).isFalse();
        // Nessun « ] » in coda dopo tante « [ » (il caso peggiore della vecchia regex) e un « ] » finale che le chiude.
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName(brackets + "memberId]"))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("memberId" + brackets + "]"))).isTrue();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName(brackets + "]"))).isFalse();
        // Suffissi ripetuti, chiusure senza aperture, prefissi del binder e trattini ripetuti.
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("memberId" + "[]".repeat(n)))).isTrue();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("memberId" + "]".repeat(n)))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("[]".repeat(n) + "memberId"))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("!".repeat(n) + "memberId"))).isTrue();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("_".repeat(n) + "memberId[0]"))).isTrue();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("!".repeat(n)))).isFalse();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("a.".repeat(n) + "memberId"))).isTrue();
        assertThat(assertTimeoutPreemptively(limit, () -> MemberPrincipals.isMemberIdName("member" + "-".repeat(n) + "Id"))).isTrue();
    }
}
