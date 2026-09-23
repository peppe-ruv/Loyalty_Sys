package io.loyaltyhub.reward.api;

import io.loyaltyhub.common.web.LhException;
import io.loyaltyhub.common.web.PageResponse;
import io.loyaltyhub.common.web.RequiresRole;
import io.loyaltyhub.common.web.Role;
import io.loyaltyhub.reward.application.CouponService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Pool e codici coupon (docs/servizi/reward-service.md §3; F-CPN-01..03, BO-12). */
@RestController
@RequestMapping("/v1")
public class CouponController {

    public record GenerateRequest(Integer count) {
    }

    public record ImportRequest(List<String> codes) {
    }

    private final CouponService coupons;

    public CouponController(CouponService coupons) {
        this.coupons = coupons;
    }

    @GetMapping("/coupon-pools")
    public List<CouponService.PoolView> pools() {
        return coupons.pools();
    }

    @PostMapping("/coupon-pools")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public ResponseEntity<CouponService.PoolView> createPool(@RequestBody CouponService.PoolRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(coupons.createPool(r));
    }

    @GetMapping("/coupon-pools/{id}")
    public CouponService.PoolView pool(@PathVariable String id) {
        return coupons.pool(id);
    }

    @PostMapping("/coupon-pools/{id}/generate")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public CouponService.GenerateResult generate(@PathVariable String id, @RequestBody GenerateRequest r) {
        if (r == null || r.count() == null) {
            throw LhException.badRequest("count è obbligatorio");
        }
        return coupons.generate(id, r.count(), true);
    }

    @PostMapping("/coupon-pools/{id}/import")
    @RequiresRole({Role.ADMIN, Role.MARKETING})
    public CouponService.ImportResult importCodes(@PathVariable String id, @RequestBody ImportRequest r) {
        return coupons.importCodes(id, r == null ? null : r.codes());
    }

    @GetMapping("/coupon-pools/{id}/coupons")
    public PageResponse<CouponService.CouponView> coupons(@PathVariable String id,
                                                          @RequestParam(required = false) String status,
                                                          @RequestParam(required = false) String memberId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return coupons.coupons(id, status, memberId, page, size);
    }

    @GetMapping("/coupons/{code}")
    public CouponService.CouponView coupon(@PathVariable String code) {
        return coupons.get(code);
    }

    /** Cassa simulata: ogni ruolo operativo (non ANALYST, che non scrive mai). */
    @PostMapping("/coupons/{code}/use")
    @RequiresRole({Role.ADMIN, Role.MARKETING, Role.LEGAL, Role.CARE})
    public CouponService.CouponView use(@PathVariable String code) {
        return coupons.use(code);
    }

    // SPEC-GAP: Q-52 — docs/08 BO-12 cita `coupon.void` ma la matrice permessi (§2) non lo elenca: come redemption.handle.
    @PostMapping("/coupons/{code}/void")
    @RequiresRole({Role.ADMIN, Role.CARE})
    public CouponService.CouponView voidCoupon(@PathVariable String code) {
        return coupons.voidCoupon(code);
    }
}
