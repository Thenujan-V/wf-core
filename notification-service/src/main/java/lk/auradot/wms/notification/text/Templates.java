package lk.auradot.wms.notification.text;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The texts of notifications and emails (spec §8 "per-customer templates"): the built-in file, with any key replaced
 * by the customer's own properties file. Plain text with {{name}} placeholders.
 */
@Component
public class Templates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    private final Properties texts = new Properties();

    public Templates(@Value("${wms.notification.templates:}") String customerFile) {
        try (InputStream in = Templates.class.getResourceAsStream("/templates/notifications.properties")) {
            load(in);
            if (customerFile != null && !customerFile.isBlank()) {
                try (InputStream own = Files.newInputStream(Path.of(customerFile))) {
                    load(own);
                }
            }
        } catch (IOException e) {
            // A customer who configured their own texts must not silently get the built-in ones.
            throw new IllegalStateException("Could not read the notification texts: " + e.getMessage(), e);
        }
    }

    private void load(InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            texts.load(reader);
        }
    }

    public boolean has(String key) {
        return texts.containsKey(key);
    }

    /** Unknown placeholders become empty; an unknown key is a programming error. */
    public String render(String key, Map<String, String> values) {
        String template = texts.getProperty(key);
        if (template == null) {
            throw new IllegalArgumentException("No text '" + key + "'");
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
