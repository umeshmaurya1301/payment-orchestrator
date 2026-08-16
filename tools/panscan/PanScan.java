import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.payorch.infra.logging.Masking;
import com.payorch.infra.logging.mask.SensitivePatterns;

/**
 * Scans captured database dumps and container output for unmasked PII.
 *
 * <p>Phase 1 seeded this as a card-number check against a dump. Phase 4 makes it
 * the build test: it runs after the k6 suite has driven real traffic through a
 * live stack, scans everything the stack wrote, and <strong>fails the
 * build</strong> if a Luhn-valid card number, an unmasked VPA or a full mobile
 * number appears anywhere.
 *
 * <p>This is the difference between claiming PII is handled and having enforced
 * it. Tokenization at the edge is the actual control; {@code @Sensitive} and
 * {@code Redactor} are the second and third layers. This is the thing that finds
 * out whether all three worked, on real output, on every commit.
 *
 * <h2>It shares its definitions with production</h2>
 *
 * <p>The Luhn check is {@code Masking.isLuhnValid} and the patterns are
 * {@link SensitivePatterns} - the same ones {@code Redactor} masks with, not a
 * second copy. A scanner with its own definitions can disagree with the thing it
 * audits, and the disagreement is never symmetric: it is always the scanner that
 * ends up more lenient, because that is the direction that makes a failing build
 * go green.
 *
 * <h2>What counts as a finding</h2>
 *
 * <ul>
 *   <li><strong>PAN</strong> - a 13-19 digit run that passes Luhn. The checksum
 *       is what keeps order ids and timestamps out of the report.</li>
 *   <li><strong>VPA</strong> - {@code user@okhdfcbank}, unmasked. Emails are
 *       matched first and excluded, because the VPA pattern is the looser of the
 *       two and would otherwise claim every email address in the output.</li>
 *   <li><strong>Mobile</strong> - a full Indian number. Already-masked values
 *       are not findings; the point is what survived unmasked.</li>
 * </ul>
 *
 * <pre>{@code
 * java -cp infra-core/logging-starter/build/libs/logging-starter-0.1.0-SNAPSHOT.jar \
 *      tools/panscan/PanScan.java dump.sql logs.txt
 * }</pre>
 *
 * <p>Exits 1 if anything is found, so it can gate a script or a Gradle task.
 */
public class PanScan {

    /** Findings are truncated in the report; the point is to locate, not to reprint. */
    private static final int CONTEXT = 40;

    /**
     * Values this system publishes on purpose, which must not be reported.
     *
     * <p>Kept deliberately tiny and specific. An allowlist is where a leak test
     * goes to die: every false positive is an invitation to add one more entry,
     * and a few years of that produces a test that passes because it has been
     * taught to ignore everything it used to find. Anything added here needs a
     * reason that is about the value being genuinely non-sensitive, never about
     * the build being red.
     */
    private static final List<String> ALLOWED = List.of(
            // The masked forms themselves. Redactor's output contains the last
            // four digits by design - PCI-DSS 3.3 permits BIN and last four -
            // and a scanner that flagged its own masking would be unusable.
            ".*\\*{3,}.*",

            // MySQL's own log announces connections as `root@localhost`, which
            // the VPA pattern reads as a virtual payment address because it has
            // no dot in the handle. It is a hostname, and `localhost` is
            // reserved - no bank will ever issue it as a UPI handle.
            //
            // Note what is NOT excluded: the mysql container's log itself. A
            // slow-query log is a genuinely plausible place for a card number to
            // surface, and skipping the whole file to silence one hostname would
            // trade a false positive for a blind spot.
            ".*@localhost"
    );

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: PanScan <file>...");
            System.exit(2);
        }

        Map<String, List<String>> findings = new LinkedHashMap<>();
        findings.put("card number", new ArrayList<>());
        findings.put("VPA", new ArrayList<>());
        findings.put("mobile number", new ArrayList<>());
        long scannedBytes = 0;

        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.isReadable(path)) {
                // Refusing rather than skipping. A scan that silently omits the
                // file it could not open reports PASS, and a green leak test
                // that scanned nothing is worse than no leak test at all.
                System.err.println("cannot read " + path + " - refusing to report a clean scan");
                System.exit(2);
            }
            scannedBytes += Files.size(path);
            scan(path, findings);
        }

        System.out.printf("scanned %d file(s), %.1f MiB%n", args.length, scannedBytes / 1048576.0);

        int total = findings.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            System.out.println("PASS: no unmasked card numbers, VPAs or mobile numbers found");
            return;
        }

        System.out.println("FAIL: " + total + " unmasked value(s) found");
        findings.forEach((kind, found) -> {
            if (found.isEmpty()) {
                return;
            }
            System.out.println("\n  " + kind + " (" + found.size() + "):");
            found.stream().limit(20).forEach(finding -> System.out.println("    " + finding));
            if (found.size() > 20) {
                System.out.println("    ... and " + (found.size() - 20) + " more");
            }
        });
        System.exit(1);
    }

    private static void scan(Path path, Map<String, List<String>> findings) throws IOException {
        // Line by line rather than slurping the file: a dump of a real database
        // does not fit in a heap, and a scanner that only works on small inputs
        // is a scanner that gets skipped.
        //
        // ISO-8859-1, not UTF-8, and this is not a detail. A mysqldump of this
        // schema contains raw BINARY(16) primary keys, which are not valid
        // UTF-8 - strict decoding throws MalformedInputException part way
        // through and the scan dies. Latin-1 maps every byte to exactly one
        // char and cannot fail, and since everything being searched for is
        // ASCII, nothing is lost. A scanner that crashes on the very file it
        // exists to check is worse than no scanner, because the crash looks
        // like tooling trouble rather than a missing result.
        try (var lines = Files.lines(path, StandardCharsets.ISO_8859_1)) {
            int[] lineNumber = {0};
            lines.forEach(line -> {
                lineNumber[0]++;
                scanLine(path, lineNumber[0], line, findings);
            });
        }
    }

    private static void scanLine(Path path, int lineNumber, String line,
                                 Map<String, List<String>> findings) {
        Matcher pan = SensitivePatterns.CANDIDATE_PAN.matcher(line);
        while (pan.find()) {
            String digits = Masking.digitsOf(pan.group());
            if (Masking.isLuhnValid(digits)) {
                findings.get("card number").add(
                        report(path, lineNumber, Masking.pan(digits), line));
            }
        }

        // Everything below this point is a TEXT pattern with no checksum behind
        // it, so it is matched against the line's TEXT only - see textOnly.
        //
        // A mysqldump of this schema is full of raw BINARY(16) primary keys, and
        // arbitrary bytes decoded as Latin-1 produce plenty of things shaped
        // like `n***@sx`. Those are not VPAs; they are pointers. Matching them
        // produced two dozen "findings" on the first real run, and a leak test
        // that cries wolf is one that gets an exclusion added until it stops
        // finding anything at all.
        //
        // Card numbers do not need this - Luhn already rejects binary noise,
        // which is exactly what a checksum is for.
        String text = textOnly(line);

        // Emails are matched first and their spans remembered, so the looser VPA
        // pattern does not claim them. Redactor does the same thing by masking
        // in order; here the order has to be explicit because nothing is being
        // rewritten.
        List<int[]> emailSpans = new ArrayList<>();
        Matcher email = SensitivePatterns.EMAIL.matcher(text);
        while (email.find()) {
            emailSpans.add(new int[]{email.start(), email.end()});
        }

        Matcher vpa = SensitivePatterns.VPA.matcher(text);
        while (vpa.find()) {
            if (overlaps(emailSpans, vpa.start(), vpa.end()) || isAllowed(vpa.group())) {
                continue;
            }
            findings.get("VPA").add(report(path, lineNumber, Masking.vpa(vpa.group()), line));
        }

        Matcher mobile = SensitivePatterns.MOBILE_IN.matcher(text);
        while (mobile.find()) {
            if (isAllowed(mobile.group())) {
                continue;
            }
            findings.get("mobile number").add(
                    report(path, lineNumber, Masking.mobile(mobile.group()), line));
        }
    }

    /**
     * A {@code _binary '...'} literal in a mysqldump, blanked out.
     *
     * <p>Escaped-quote aware, so a blob containing {@code '} does not end the
     * match early and swallow the rest of the row.
     */
    private static final Pattern BINARY_LITERAL =
            Pattern.compile("_binary '(?:[^'\\\\]|\\\\[\\s\\S])*'");

    /**
     * The line with binary column values removed.
     *
     * <p>Only the text patterns use this. The first attempt filtered
     * non-printable bytes instead, on the theory that a blob is unreadable - and
     * it did not work, because mysqldump escapes the unprintable bytes and
     * leaves the rest as ASCII. A sixteen-byte UUID reliably contains a few
     * characters that spell something ending in {@code @sx}, and the scanner
     * duly reported two dozen VPAs living inside primary keys.
     *
     * <p>A BINARY column cannot hold a virtual payment address anybody could
     * read, so the honest answer is to exclude those spans rather than to teach
     * the patterns to tolerate them. Card numbers still get the whole line:
     * Luhn is a checksum and does not need protecting from noise, which is
     * precisely what a checksum is for.
     *
     * <p>Length-preserving, so reported line offsets still line up.
     */
    private static String textOnly(String line) {
        Matcher matcher = BINARY_LITERAL.matcher(line);
        StringBuilder out = new StringBuilder(line.length());
        while (matcher.find()) {
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(" ".repeat(matcher.group().length())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean overlaps(List<int[]> spans, int start, int end) {
        return spans.stream().anyMatch(span -> start < span[1] && end > span[0]);
    }

    private static boolean isAllowed(String value) {
        return ALLOWED.stream().anyMatch(value::matches);
    }

    private static String report(Path path, int lineNumber, String masked, String line) {
        return "%s:%d  %s  in: %s".formatted(path.getFileName(), lineNumber, masked, excerpt(line));
    }

    /**
     * The surrounding text, masked.
     *
     * <p>A report that printed the leaked value in full would be a second copy
     * of the leak, sitting in CI output that is usually more widely readable
     * than the log it came from. The whole point of finding a PAN in a log is
     * that it should not be in a log.
     */
    private static String excerpt(String line) {
        String masked = maskEverything(line);
        return masked.length() <= CONTEXT * 2 ? masked : masked.substring(0, CONTEXT * 2) + "...";
    }

    private static String maskEverything(String line) {
        String out = maskPans(line);
        out = replaceAll(SensitivePatterns.MOBILE_IN, out, Masking::mobile);
        return out;
    }

    private static String maskPans(String line) {
        Matcher matcher = SensitivePatterns.CANDIDATE_PAN.matcher(line);
        StringBuilder out = new StringBuilder(line.length());
        while (matcher.find()) {
            String digits = Masking.digitsOf(matcher.group());
            String replacement = Masking.isLuhnValid(digits) ? Masking.pan(digits) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String replaceAll(Pattern pattern, String input,
                                     java.util.function.UnaryOperator<String> mask) {
        Matcher matcher = pattern.matcher(input);
        StringBuilder out = new StringBuilder(input.length());
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(mask.apply(matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
