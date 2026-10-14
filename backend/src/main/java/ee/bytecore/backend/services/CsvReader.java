package ee.bytecore.backend.services;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: comma separated, double-quote quoting, CRLF/LF records. */
final class CsvReader {
    private CsvReader() {}

    static final class CsvFormatException extends IllegalArgumentException {
        private final int record;

        CsvFormatException(int record, String message) {
            super(message);
            this.record = record;
        }

        /** 0-based record index (0 is the header). */
        int record() {
            return record;
        }
    }

    /** Parses all records. A trailing line break does not produce an empty record. */
    static List<List<String>> parse(String text) {
        String input = text.startsWith("\uFEFF") ? text.substring(1) : text;
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        boolean afterQuote = false;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                    afterQuote = true;
                } else {
                    field.append(c);
                }
                i++;
                continue;
            }
            if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
                fieldStarted = true;
                afterQuote = false;
            } else if (c == '\r' || c == '\n') {
                current.add(field.toString());
                records.add(current);
                current = new ArrayList<>();
                field.setLength(0);
                fieldStarted = false;
                afterQuote = false;
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
            } else if (c == '"') {
                if (field.length() > 0 || afterQuote)
                    throw new CsvFormatException(records.size(), "Unexpected quote inside an unquoted field");
                quoted = true;
                fieldStarted = true;
            } else {
                if (afterQuote)
                    throw new CsvFormatException(records.size(), "Unexpected character after a closing quote");
                field.append(c);
                fieldStarted = true;
            }
            i++;
        }
        if (quoted) throw new CsvFormatException(records.size(), "Unterminated quoted field");
        if (fieldStarted || field.length() > 0 || !current.isEmpty() || afterQuote) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
