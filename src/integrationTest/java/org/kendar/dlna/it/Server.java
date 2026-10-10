package org.kendar.dlna.it;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The server under test, shared by all test classes of the JVM: either a container started by Testcontainers (image
 * built from the repository Dockerfile, generated library copied in, no bind mounts, mapped port on the possibly
 * remote Docker host) or an already running server given by {@code KENDAR_IT_BASE_URL}.
 */
final class Server {

    /** Password the managed container's admin account is switched to after the forced first change. */
    static final String MANAGED_PASSWORD = "kendar-it-Passw0rd-2026";

    private static final Pattern PASSWORD = Pattern.compile("password:\\s+(\\S+)");
    private static final Pattern UUID = Pattern.compile("uuid=([0-9a-fA-F-]{36})");
    private static Server instance;

    private final boolean managed;
    private final String baseUrl;
    private final String uuid;
    private final String descriptionUrl;
    private final LibraryFactory.Library library;
    private final GenericContainer<?> container;
    private final Path workDir;
    private String adminPassword;
    private String adminSkipReason;
    private AdminClient admin;
    private UpnpClient upnp;

    private Server(boolean managed, String baseUrl, String uuid, String descriptionUrl,
            LibraryFactory.Library library, GenericContainer<?> container, Path workDir, String adminPassword,
            String adminSkipReason) {
        this.managed = managed;
        this.baseUrl = baseUrl;
        this.uuid = uuid;
        this.descriptionUrl = descriptionUrl;
        this.library = library;
        this.container = container;
        this.workDir = workDir;
        this.adminPassword = adminPassword;
        this.adminSkipReason = adminSkipReason;
    }

    static synchronized Server get() {
        if (instance == null) {
            try {
                instance = ItEnv.baseUrl() != null ? external() : managedContainer();
            } catch (Exception e) {
                throw new IllegalStateException("Cannot prepare the server under test: " + e, e);
            }
            Server s = instance;
            Runtime.getRuntime().addShutdownHook(new Thread(s::shutdown, "it-server-shutdown"));
        }
        return instance;
    }

    // ---------------------------------------------------------------- managed (Testcontainers)

    private static Server managedContainer() throws Exception {
        Path work = Files.createTempDirectory("kendar-it-");
        LibraryFactory.Library lib = LibraryFactory.generate(work);

        GenericContainer<?> c = (ItEnv.image() != null ? new GenericContainer<>(DockerImageName.parse(ItEnv.image()))
                : new GenericContainer<>(buildImage()))
                .withExposedPorts(8200)
                // root: the files copied in are owned by root and the admin API must be able to write tags
                .withEnv("PUID", "0").withEnv("PGID", "0")
                .withCopyFileToContainer(MountableFile.forHostPath(lib.music()), "/music")
                .withCopyFileToContainer(MountableFile.forHostPath(lib.playlists()), "/playlists")
                .waitingFor(Wait.forHttp("/health").forPort(8200).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        c.start();

        String logs = awaitLogs(c);
        Matcher pw = PASSWORD.matcher(logs.substring(logs.indexOf("KENDAR DLNA ADMIN PASSWORD")));
        if (!pw.find()) {
            throw new IllegalStateException("Admin password not found in the container log:\n" + logs);
        }
        Matcher id = UUID.matcher(logs);
        if (!id.find()) {
            throw new IllegalStateException("Device uuid not found in the container log:\n" + logs);
        }
        String base = "http://" + c.getHost() + ":" + c.getMappedPort(8200);
        String uuid = id.group(1);
        Server s = new Server(true, base, uuid, base + "/upnp/dev/" + uuid + "/desc", lib, c, work, pw.group(1),
                null);
        s.firstLoginAndWaitForScan();
        return s;
    }

    /** Tag of the image built from the Dockerfile; removed again when the JVM ends. */
    static final String BUILT_IMAGE = "kendar-dlna-it:test";

    private static ImageFromDockerfile buildImage() {
        Path root = ItEnv.projectDir();
        ImageFromDockerfile image = new ImageFromDockerfile(BUILT_IMAGE, false);
        // Exactly what the Dockerfile copies; src/test and the Gradle output are left out.
        for (String f : new String[] { "Dockerfile", "gradlew", "settings.gradle", "build.gradle", "gradle.properties",
                "gradle", "src/main", "docker/entrypoint.sh" }) {
            image.withFileFromPath(f, root.resolve(f));
        }
        return image;
    }

    /** The password block and the uuid line are written at the very start; wait for both. */
    private static String awaitLogs(GenericContainer<?> c) throws InterruptedException {
        Instant end = Instant.now().plusSeconds(60);
        String logs = c.getLogs();
        while (Instant.now().isBefore(end)
                && !(logs.contains("KENDAR DLNA ADMIN PASSWORD") && UUID.matcher(logs).find())) {
            Thread.sleep(500);
            logs = c.getLogs();
        }
        return logs;
    }

    /** Logs in with the generated password, makes the forced change, and waits for the initial scan. */
    private void firstLoginAndWaitForScan() throws Exception {
        AdminClient a = new AdminClient(baseUrl);
        AdminClient.Reply r = a.login("admin", adminPassword);
        if (r.status() != 200) {
            throw new IllegalStateException("Admin login with the logged password failed: " + r.status() + " "
                    + r.body());
        }
        if (r.json().path("mustChange").asBoolean()) {
            AdminClient.Reply c = a.changePassword(adminPassword, MANAGED_PASSWORD);
            if (c.status() != 200) {
                throw new IllegalStateException("Forced password change failed: " + c.status() + " " + c.body());
            }
            adminPassword = MANAGED_PASSWORD;
        }
        Instant end = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(end)) {
            JsonNode scan = a.get("/api/v1/admin/scan").json();
            if (!scan.path("running").asBoolean(true) && scan.path("trackCount").asInt() >= library.trackCount()) {
                admin = a;
                return;
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("The initial scan did not finish: " + a.get("/api/v1/admin/scan").body());
    }

    // ---------------------------------------------------------------- external server

    private static Server external() throws Exception {
        String base = ItEnv.baseUrl();
        URI u = URI.create(base);
        String desc = ItEnv.descriptionUrl();
        if (desc == null) {
            desc = locateBySsdp(u.getHost());
        }
        if (desc == null) {
            throw new IllegalStateException("No device description URL: the server at " + base
                    + " did not answer a unicast SSDP search on UDP 1900; set KENDAR_IT_DESCRIPTION_URL");
        }
        String uuid = desc.replaceFirst(".*/dev/([0-9a-fA-F-]{36})/.*", "$1");
        if (uuid.equals(desc)) {
            // another URL layout: read the UDN from the description itself
            Matcher m = Pattern.compile("<UDN>uuid:([^<]+)</UDN>").matcher(Http.get(desc).text());
            if (!m.find()) {
                throw new IllegalStateException("No UDN in the device description at " + desc);
            }
            uuid = m.group(1);
        }
        String password = ItEnv.adminPassword();
        Server s = new Server(false, base, uuid, desc, null, null, null, password,
                password == null ? "KENDAR_IT_ADMIN_PASSWORD is not set" : null);
        if (password != null) {
            AdminClient a = new AdminClient(base);
            AdminClient.Reply r = a.login(ItEnv.adminUser(), password);
            if (r.status() != 200) {
                s.adminSkipReason = "admin login failed (" + r.status() + ")";
            } else if (r.json().path("mustChange").asBoolean()) {
                s.adminSkipReason = "the admin account must change its password first (do it once in the web UI)";
            } else {
                s.admin = a;
            }
        }
        return s;
    }

    /** Unicast M-SEARCH to the server host (works with host networking; multicast is not needed). */
    static String locateBySsdp(String host) throws IOException {
        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n"
                + "ST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n";
        byte[] out = msg.getBytes(StandardCharsets.US_ASCII);
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(2000);
            s.send(new DatagramPacket(out, out.length, InetAddress.getByName(host), 1900));
            byte[] buf = new byte[2048];
            Instant end = Instant.now().plusSeconds(4);
            while (Instant.now().isBefore(end)) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    return null;
                }
                Matcher m = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(new String(p.getData(), 0,
                        p.getLength(), StandardCharsets.US_ASCII));
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- accessors

    /** True when the library is the generated one (exact expectations hold); false for an external server. */
    boolean managed() {
        return managed;
    }

    String baseUrl() {
        return baseUrl;
    }

    String uuid() {
        return uuid;
    }

    String descriptionUrl() {
        return descriptionUrl;
    }

    LibraryFactory.Library library() {
        return library;
    }

    /** Skip reason of the admin tests, or null when an admin session is available. */
    String adminSkipReason() {
        return admin != null ? null : adminSkipReason == null ? "admin not available" : adminSkipReason;
    }

    String adminUser() {
        return managed ? "admin" : ItEnv.adminUser();
    }

    String adminPassword() {
        return adminPassword;
    }

    /** The shared logged-in admin session. */
    AdminClient admin() {
        if (admin == null) {
            throw new IllegalStateException("No admin session: " + adminSkipReason());
        }
        return admin;
    }

    /** A fresh, not logged in client (for the login flow tests). */
    AdminClient newAdminClient() {
        return new AdminClient(baseUrl);
    }

    synchronized UpnpClient upnp() {
        if (upnp == null) {
            try {
                upnp = new UpnpClient(descriptionUrl, uuid);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create the UPnP control point: " + e, e);
            }
        }
        return upnp;
    }

    /** The URL as seen from this machine: scheme, host and port replaced by the base URL (managed: mapped port). */
    String rebase(String url) {
        URI u = URI.create(url);
        URI b = URI.create(baseUrl);
        String rest = u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        return b.getScheme() + "://" + b.getAuthority() + rest;
    }

    /** Copies a local file into the container (managed mode only). */
    void copyIntoContainer(Path local, String containerPath) {
        if (container == null) {
            throw new IllegalStateException("Not a managed container");
        }
        container.copyFileToContainer(MountableFile.forHostPath(local), containerPath);
    }

    /** Runs a command in the container (managed mode only); fails on a non-zero exit code. */
    String exec(String... command) {
        if (container == null) {
            throw new IllegalStateException("Not a managed container");
        }
        try {
            var r = container.execInContainer(command);
            if (r.getExitCode() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed: " + r.getStderr());
            }
            return r.getStdout();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    String containerLogs() {
        return container == null ? "" : container.getLogs();
    }

    private void shutdown() {
        try {
            if (upnp != null) {
                upnp.close();
            }
        } catch (RuntimeException e) {
            // best effort
        }
        if (container != null) {
            try {
                Path log = ItEnv.projectDir().resolve("build/integration-test-container.log");
                Files.createDirectories(log.getParent());
                Files.writeString(log, container.getLogs(), StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                // best effort
            }
            container.stop();
            if (ItEnv.image() == null) {
                try {
                    DockerClientFactory.lazyClient().removeImageCmd(BUILT_IMAGE).withForce(true).exec();
                } catch (RuntimeException e) {
                    System.err.println("[it] cannot remove the image " + BUILT_IMAGE + ": " + e);
                }
            }
        }
        if (workDir != null) {
            try (var walk = Files.walk(workDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                // temp files
            }
        }
    }
}
