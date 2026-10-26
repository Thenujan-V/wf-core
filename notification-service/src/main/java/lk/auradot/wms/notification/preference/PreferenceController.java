package lk.auradot.wms.notification.preference;

import jakarta.validation.Valid;
import lk.auradot.wms.common.error.ApiException;
import lk.auradot.wms.notification.plan.Kind;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Spec §8 per-user preferences: what the signed-in person wants in the app and by email. */
@RestController
public class PreferenceController {

    private final PreferenceStore store;

    public PreferenceController(PreferenceStore store) {
        this.store = store;
    }

    @GetMapping("/preferences")
    public List<Preference> preferences(@AuthenticationPrincipal Jwt jwt) {
        return store.all(jwt.getSubject());
    }

    @PutMapping("/preferences")
    public List<Preference> replace(@Valid @RequestBody PreferencesRequest request, @AuthenticationPrincipal Jwt jwt) {
        Set<Kind> seen = EnumSet.noneOf(Kind.class);
        for (PreferencesRequest.Choice choice : request.preferences()) {
            if (!seen.add(choice.kind())) {
                throw ApiException.badRequest("NOTIF_PREFERENCE_DUPLICATE", "Each kind of notification can be set only once");
            }
        }
        store.replace(jwt.getSubject(), request.preferences().stream()
                .map(choice -> new Preference(choice.kind(), choice.inApp(), choice.email())).toList());
        return store.all(jwt.getSubject());
    }
}
