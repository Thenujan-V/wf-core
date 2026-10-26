package lk.auradot.wms.notification.plan;

import java.util.Map;

/** An email a process's "send email" step asked for. {@code to} is an address, or a user id to look up. */
public record ProcessEmail(String to, String template, Map<String, String> values) {
}
