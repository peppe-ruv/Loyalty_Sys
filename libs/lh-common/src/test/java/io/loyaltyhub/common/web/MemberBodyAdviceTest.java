package io.loyaltyhub.common.web;

import io.loyaltyhub.common.web.MemberTestSupport.NestedRequest;
import io.loyaltyhub.common.web.MemberTestSupport.WriteRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MemberBodyAdvice}: in enterprise un corpo con {@code memberId} non nullo, a qualunque profondità (fino a 4), è un
 * {@code 400 MEMBER_FROM_TOKEN} (Q-553, D6, ADR-048); la difesa non dipende dalla disciplina di ogni controller.
 */
class MemberBodyAdviceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Bean di un package {@code io.loyaltyhub.*} con un campo {@code memberId} non record. */
    public static class LegacyBean {
        public String rewardCode = "RWD-1";
        public String memberId;
        public List<LegacyBean> children = List.of();
    }

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    @DisplayName("record: memberId non nullo ⇒ vero; nullo o assente ⇒ falso; a ogni grafia")
    void recordComponent() {
        assertThat(MemberBodyAdvice.carriesMemberId(new WriteRequest("RWD-1", "MBR-000102"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(new WriteRequest("RWD-1", ""))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(new WriteRequest("RWD-1", null))).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(null)).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId("testo")).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(42)).isFalse();
    }

    @Test
    @DisplayName("record annidato, elenchi, array, mappe e bean di io.loyaltyhub.*")
    void nestedContainers() {
        assertThat(MemberBodyAdvice.carriesMemberId(new NestedRequest("RWD-1", new NestedRequest.Inner("Roma", "MBR-2")))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(new NestedRequest("RWD-1", new NestedRequest.Inner("Roma", null)))).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(List.of(new WriteRequest("a", null), new WriteRequest("b", "MBR-2")))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(new WriteRequest[] {new WriteRequest("a", "MBR-2")})).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(new int[] {1, 2})).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(Map.of("memberId", "MBR-2"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(Map.of("MemberID", "MBR-2"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(Map.of("outer", Map.of("member_id", "MBR-2")))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(Map.of("outer", Map.of("code", "X")))).isFalse();
        LegacyBean bean = new LegacyBean();
        assertThat(MemberBodyAdvice.carriesMemberId(bean)).isFalse();
        bean.memberId = "MBR-2";
        assertThat(MemberBodyAdvice.carriesMemberId(bean)).isTrue();
        LegacyBean parent = new LegacyBean();
        parent.children = List.of(bean);
        assertThat(MemberBodyAdvice.carriesMemberId(parent)).isTrue();
    }

    @Test
    @DisplayName("JsonNode: le chiavi a ogni profondità entro il limite, in qualunque grafia; null esplicito passa")
    void jsonNodeKeys() {
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"memberId\":\"MBR-2\"}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"MEMBER_ID\":\"MBR-2\"}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":{\"b\":{\"memberId\":\"x\"}}}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":[{\"memberId\":1}]}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"memberId\":null}"))).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":{\"memberId\":null}}"))).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"rewardCode\":\"RWD-1\"}"))).isFalse();
        assertThat(MemberBodyAdvice.carriesMemberId(json("[]"))).isFalse();
        // Un valore che contiene il testo memberId non è una chiave.
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"note\":\"memberId=MBR-2\"}"))).isFalse();
    }

    @Test
    @DisplayName("profondità: 0…4 ispezionate, oltre il limite no (il corpo resta ai controller)")
    void depthLimit() {
        assertThat(MemberBodyAdvice.MAX_DEPTH).isEqualTo(4);
        // La radice è a profondità 0: le chiavi dell'oggetto a profondità 4 sono le ultime viste.
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":{\"b\":{\"c\":{\"memberId\":\"x\"}}}}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":{\"b\":{\"c\":{\"d\":{\"memberId\":\"x\"}}}}}"))).isTrue();
        assertThat(MemberBodyAdvice.carriesMemberId(json("{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"memberId\":\"x\"}}}}}}"))).isFalse();
    }

    @Test
    @DisplayName("un riferimento circolare non manda in ciclo l'ispezione")
    void cycleSafe() {
        LegacyBean a = new LegacyBean();
        LegacyBean b = new LegacyBean();
        a.children = List.of(b);
        b.children = List.of(a);
        assertThat(MemberBodyAdvice.carriesMemberId(a)).isFalse();
    }

    @Test
    @DisplayName("afterBodyRead rifiuta con 400 MEMBER_FROM_TOKEN; senza memberId restituisce lo stesso corpo")
    void afterBodyRead() {
        MemberBodyAdvice advice = new MemberBodyAdvice(IdentityMode.OIDC);
        WriteRequest clean = new WriteRequest("RWD-1", null);
        assertThat(advice.afterBodyRead(clean, null, null, WriteRequest.class, JacksonJsonHttpMessageConverter.class)).isSameAs(clean);
        assertThatThrownBy(() -> advice.afterBodyRead(new WriteRequest("RWD-1", "MBR-2"), null, null, WriteRequest.class,
                JacksonJsonHttpMessageConverter.class)).isInstanceOfSatisfying(LhException.class, e -> {
            assertThat(e.status().value()).isEqualTo(400);
            assertThat(e.code()).isEqualTo("MEMBER_FROM_TOKEN");
            assertThat(e.getMessage()).doesNotContain("MBR-2");
        });
    }

    @Test
    @DisplayName("supports: solo in oidc e solo sugli handler @MemberEndpoint (anche dichiarato sulla classe)")
    void supportsOnlyMemberEndpointsInOidc() throws Exception {
        MemberBodyAdvice oidc = new MemberBodyAdvice(IdentityMode.OIDC);
        MemberBodyAdvice demo = new MemberBodyAdvice(IdentityMode.HEADER);
        MethodParameter member = new MethodParameter(
                MemberTestSupport.Portal.class.getMethod("write", WriteRequest.class, MemberPrincipal.class), 0);
        assertThat(oidc.supports(member, WriteRequest.class, JacksonJsonHttpMessageConverter.class)).isTrue();
        assertThat(demo.supports(member, WriteRequest.class, JacksonJsonHttpMessageConverter.class)).isFalse();
        // Un handler che non è del membro non è toccato dall'advice.
        MethodParameter other = new MethodParameter(WithBody.class.getMethod("post", WriteRequest.class), 0);
        assertThat(oidc.supports(other, WriteRequest.class, JacksonJsonHttpMessageConverter.class)).isFalse();
    }

    /** Un handler con corpo che non dichiara il membro. */
    public static class WithBody {
        @RequiresRole
        public void post(WriteRequest body) {
        }
    }
}
