package com.iflytek.skillhub.gitsync.git;

import com.iflytek.skillhub.gitsync.GitSyncProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the local {@code git} binary for repository access.
 *
 * <p>Syncing reads repositories through git itself instead of each hosting
 * provider's REST API, so any server that speaks standard git-over-HTTP(S) or
 * SSH works without a provider-specific adapter.
 *
 * <p>Every invocation is hardened: no interactive prompts, no hooks, no
 * attribute-driven smudge filters, no submodule recursion and no LFS download,
 * so untrusted repository content cannot influence the host while it is read.
 * Credentials are passed through {@code GIT_CONFIG_*} environment variables and
 * never end up in a remote URL or in {@code .git/config}.
 */
@Component
public class GitCommandRunner {

    private static final Logger log = LoggerFactory.getLogger(GitCommandRunner.class);

    private final GitSyncProperties properties;

    public GitCommandRunner(GitSyncProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs git in {@code workingDirectory} and returns raw stdout.
     *
     * @param environment extra environment variables (credentials, askpass)
     */
    public byte[] run(Path workingDirectory, List<String> arguments, Map<String, String> environment) {
        return run(workingDirectory, arguments, environment, properties.getCommandTimeout());
    }

    byte[] run(Path workingDirectory, List<String> arguments, Map<String, String> environment, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(properties.getGitBinary());
        command.add("-c");
        command.add("core.hooksPath=" + devNull());
        command.add("-c");
        command.add("core.attributesFile=" + devNull());
        command.add("-c");
        command.add("core.symlinks=false");
        command.add("-c");
        command.add("advice.detachedHead=false");
        command.addAll(arguments);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        Map<String, String> env = builder.environment();
        env.putAll(hardening(environment));
        if (environment != null) {
            env.putAll(environment);
        }

        Process process = null;
        try {
            process = builder.start();
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            Process running = process;
            Thread outReader = drain(running.getInputStream(), stdout);
            Thread errReader = drain(running.getErrorStream(), stderr);

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new GitProviderException("git " + describe(arguments) + " timed out after "
                        + timeout.toSeconds() + "s");
            }
            outReader.join(TimeUnit.SECONDS.toMillis(5));
            errReader.join(TimeUnit.SECONDS.toMillis(5));

            if (process.exitValue() != 0) {
                String message = stderr.toString(StandardCharsets.UTF_8).trim();
                throw new GitProviderException("git " + describe(arguments) + " failed: " + trim(message));
            }
            return stdout.toByteArray();
        } catch (IOException ex) {
            throw new GitProviderException("Could not run git (" + properties.getGitBinary()
                    + "); install git in the runtime image or set skillhub.gitsync.git-binary", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitProviderException("git " + describe(arguments) + " was interrupted", ex);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * Runs git and decodes stdout as UTF-8 text, normalizing the line endings git
     * may emit for paths on some platforms.
     */
    public String runForText(Path workingDirectory, List<String> arguments, Map<String, String> environment) {
        return new String(run(workingDirectory, arguments, environment), StandardCharsets.UTF_8);
    }

    /**
     * Same as above with an explicit timeout: cheap probes (a ref check while an
     * administrator fills in a form) must not wait as long as a fetch may.
     */
    public String runForText(Path workingDirectory, List<String> arguments, Map<String, String> environment,
                             Duration timeout) {
        return new String(run(workingDirectory, arguments, environment, timeout), StandardCharsets.UTF_8);
    }

    /**
     * Environment that makes git non-interactive and keeps repository content from
     * running anything on the host.
     */
    static Map<String, String> hardening(Map<String, String> credentials) {
        Map<String, String> env = new java.util.HashMap<>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_ASKPASS", "");
        env.put("SSH_ASKPASS", "");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_ADVICE", "0");
        env.put("LC_ALL", "C");
        if (credentials != null) {
            env.putAll(credentials);
        }
        return env;
    }

    private static Thread drain(InputStream stream, ByteArrayOutputStream target) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try (stream) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    target.write(buffer, 0, read);
                }
            } catch (IOException ex) {
                log.debug("git output stream closed early: {}", ex.getMessage());
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String describe(List<String> arguments) {
        // Never log credentials: only the subcommand and its first argument.
        if (arguments.isEmpty()) {
            return "";
        }
        String sub = arguments.get(0);
        return arguments.size() > 1 ? sub + " " + arguments.get(1) : sub;
    }

    private static String trim(String message) {
        String normalized = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        int limit = 500;
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String devNull() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "NUL"
                : "/dev/null";
    }
}
