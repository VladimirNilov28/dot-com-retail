package ee.bytecore.backend.services.audit;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds audit detail maps from key/value pairs, omitting null values. */
public final class AuditDetails {
    private AuditDetails() {}

    public static Map<String, Object> details(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("Audit details need key/value pairs");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }
}
