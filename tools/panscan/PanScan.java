import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.payorch.infra.logging.Masking;

/**
 * Scans captured database dumps and container logs for Luhn-valid card numbers.
 *
 * <p>This is phase 1's last exit criterion, automated. It is also the seed of
 * the phase-4 build test that runs the k6 suite against a live stack and fails
 * the build if a PAN appears anywhere in the captured output.
 *
 * <p>It runs as a single-file source program against the logging starter's jar,
 * so it uses the <em>same</em> {@code Masking.isLuhnValid} the runtime masking
 * filter uses. A second, re-implemented Luhn check here would be able to
 * disagree with the one in production, and the disagreement would always be in
 * the direction of this scanner being more lenient than the thing it audits.
 *
 * <pre>{@code
 * java -cp infra-core/logging-starter/build/libs/logging-starter-0.1.0-SNAPSHOT.jar \
 *      tools/panscan/PanScan.java dump.sql logs.txt
 * }</pre>
 *
 * <p>Exits 1 if anything is found, so it can gate a script.
 */
public class PanScan {

    /**
     * A run of 13-19 digits, optionally broken by single spaces or hyphens.
     * Identical to the pattern in {@code Redactor}, and bounded so there is no
     * catastrophic-backtracking risk on a multi-megabyte dump.
     */
    private static final Pattern CANDIDATE = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");

    /** Findings are truncated in the report; the point is to locate, not to reprint. */
    private static final int CONTEXT = 40;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("usage: PanScan <file>...");
            System.exit(2);
        }

        List<String> findings = new ArrayList<>();
        long scannedBytes = 0;

        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.isReadable(path)) {
                System.err.println("cannot read " + path + " - refusing to report a clean scan");
                System.exit(2);
            }
            scannedBytes += Files.size(path);
            findings.addAll(scan(path));
        }

        System.out.printf("scanned %d file(s), %.1f MiB%n", args.length, scannedBytes / 1048576.0);

        if (findings.isEmpty()) {
            System.out.println("PASS: no Luhn-valid card numbers found");
            return;
        }

        System.out.println("FAIL: " + findings.size() + " Luhn-valid card number(s) found");
        findings.forEach(finding -> System.out.println("  " + finding));
        System.exit(1);
    }

    private static List<String> scan(Path path) throws IOException {
        List<String> findings = new ArrayList<>();

        // Line by line rather than slurping the file: a dump of a real database
        // does not fit in a heap, and a scanner that only works on small inputs
        // is a scanner that gets skipped.
        //
        // ISO-8859-1, not UTF-8, and this is not a detail. A mysqldump of this
        // schema contains raw BINARY(16) primary keys, which are not valid
        // UTF-8 - strict decoding throws MalformedInputException part way
        // through and the scan dies. Latin-1 maps every byte to exactly one
        // char and cannot fail, and since the only thing being searched for is
        // a run of ASCII digits, nothing is lost. A scanner that crashes on the
        // very file it exists to check is worse than no scanner, because the
        // crash looks like tooling trouble rather than a missing result.
        try (var lines = Files.lines(path, StandardCharsets.ISO_8859_1)) {
            int[] lineNumber = {0};
            lines.forEach(line -> {
                lineNumber[0]++;
                Matcher matcher = CANDIDATE.matcher(line);
                while (matcher.find()) {
                    String digits = Masking.digitsOf(matcher.group());
                    if (!Masking.isLuhnValid(digits)) {
                        continue;
                    }
                    findings.add("%s:%d  %s  in: %s".formatted(
                            path.getFileName(), lineNumber[0], Masking.pan(digits), excerpt(line)));
                }
            });
        }
        return findings;
    }

    /**
     * The surrounding text, masked.
     *
     * <p>A report that printed the leaked number in full would be a second copy
     * of the leak, sitting in CI output that is usually more widely readable
     * than the log it came from.
     */
    private static String excerpt(String line) {
        String masked = maskEveryCard(line);
        return masked.length() <= CONTEXT * 2 ? masked : masked.substring(0, CONTEXT * 2) + "...";
    }

    private static String maskEveryCard(String line) {
        Matcher matcher = CANDIDATE.matcher(line);
        StringBuilder out = new StringBuilder(line.length());
        while (matcher.find()) {
            String digits = Masking.digitsOf(matcher.group());
            String replacement = Masking.isLuhnValid(digits) ? Masking.pan(digits) : matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
