package edu.harvard.hms.dbmi.avillach.conventions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * R20: every {@code FROM} in a Dockerfile that names an external image pins it by digest, as
 * {@code image:tag@sha256:<digest>}. A floating tag such as {@code amazoncorretto:25-alpine} resolves to
 * whatever the registry points it at on build day, so two builds of one commit can run on different bases.
 *
 * <p>Unlike the other rules this one reads files, not compiled classes. It covers every git-tracked file
 * named {@code Dockerfile} or {@code Dockerfile.*} in the repository. A {@code FROM} that names a stage
 * declared earlier in the same file ({@code FROM builder}) is exempt, as is {@code FROM scratch}. Flags such
 * as {@code --platform=...} are skipped when finding the image. An image built from an {@code ARG} is
 * checked like any other reference, so {@code FROM ${BASE}} fails unless the text carries a digest.
 *
 * <p>{@code USER} and {@code HEALTHCHECK} are out of scope. Some images declare {@code HEALTHCHECK NONE}
 * on purpose, and whether an image runs as root is a separate question.
 */
public final class DockerfileRules {

    private static final Pattern FROM = Pattern.compile("^\\s*FROM\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final String DIGEST = "@sha256:";
    private static final String SCRATCH = "scratch";

    private DockerfileRules() {}

    /**
     * Says whether a repository path names a Dockerfile this rule covers.
     *
     * @param path a slash-separated path relative to the repository root
     * @return true when the file name is {@code Dockerfile} or starts with {@code Dockerfile.}
     */
    public static boolean isDockerfile(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.equals("Dockerfile") || name.startsWith("Dockerfile.");
    }

    /**
     * Lists the Dockerfiles git tracks under a repository root. Reading the index rather than walking the
     * tree skips build output and anything ignored, and makes an untracked scratch file irrelevant.
     *
     * @param root the repository root
     * @return slash-separated paths relative to {@code root}, in the order git lists them
     * @throws IllegalStateException if git cannot be run or exits with an error, so a missing checkout
     *     fails the rule instead of passing it with nothing to check
     */
    public static List<String> trackedDockerfiles(Path root) {
        ProcessBuilder command = new ProcessBuilder("git", "ls-files", "-z").directory(root.toFile()).redirectErrorStream(true);
        try {
            Process process = command.start();
            String output;
            try (InputStream stdout = process.getInputStream()) {
                output = new String(stdout.readAllBytes(), StandardCharsets.UTF_8);
            }
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git ls-files failed in " + root + " (exit " + exit + "): " + output.strip());
            }
            List<String> dockerfiles = new ArrayList<>();
            for (String path : output.split("\0")) {
                if (!path.isEmpty() && isDockerfile(path)) {
                    dockerfiles.add(path);
                }
            }
            return dockerfiles;
        } catch (IOException e) {
            throw new IllegalStateException("could not run git ls-files in " + root, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running git ls-files in " + root, e);
        }
    }

    /**
     * R20: every {@code FROM} that names an external image carries an {@code @sha256:} digest.
     *
     * @param path the file's path, used in the violation text
     * @param contents the file's full text
     * @return one violation per unpinned {@code FROM}, naming the path, the line and the image
     */
    public static List<String> baseImagesArePinned(String path, String contents) {
        List<String> violations = new ArrayList<>();
        Set<String> stages = new HashSet<>();
        String[] lines = contents.split("\\R", -1);
        int index = 0;
        while (index < lines.length) {
            int lineNumber = index + 1;
            StringBuilder instruction = new StringBuilder();
            String line = lines[index++];
            while (line.stripTrailing().endsWith("\\") && index < lines.length) {
                String stripped = line.stripTrailing();
                instruction.append(stripped, 0, stripped.length() - 1).append(' ');
                line = lines[index++];
            }
            instruction.append(line);

            Matcher from = FROM.matcher(instruction);
            if (!from.matches()) {
                continue;
            }
            List<String> operands = new ArrayList<>();
            for (String token : from.group(1).trim().split("\\s+")) {
                if (!token.startsWith("--")) {
                    operands.add(token);
                }
            }
            if (operands.isEmpty()) {
                continue;
            }
            String image = operands.get(0);
            String reference = image.toLowerCase(Locale.ROOT);
            boolean exempt = reference.equals(SCRATCH) || stages.contains(reference) || image.contains(DIGEST);
            if (!exempt) {
                violations.add(
                    path + ":" + lineNumber + " FROM " + image + " has no @sha256 digest (pin it as " + image + "@sha256:<digest>)"
                );
            }
            if (operands.size() >= 3 && operands.get(1).equalsIgnoreCase("AS")) {
                stages.add(operands.get(2).toLowerCase(Locale.ROOT));
            }
        }
        return violations;
    }
}
