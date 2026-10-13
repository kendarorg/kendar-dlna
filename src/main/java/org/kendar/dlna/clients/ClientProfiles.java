package org.kendar.dlna.clients;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.kendar.dlna.shared.Json;

/** Builds the {@link ClientProfileResolver}: loads (inline, file, built-ins), validates, reports every error. */
public final class ClientProfiles {

    private static final Logger LOG = Logger.getLogger(ClientProfiles.class.getName());
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    static final String BUILTIN_RESOURCE = "/clients/builtin-profiles.json";

    /** Shape of a profiles file and of the built-in resource. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProfilesFile(List<ClientProfile> profiles) {
        public ProfilesFile {
            profiles = profiles == null ? List.of() : List.copyOf(profiles);
        }
    }

    private ClientProfiles() {
    }

    /**
     * @param baseDir directory that a relative {@code profilesFile} is resolved against
     * @throws InvalidProfilesException listing all problems
     */
    public static ClientProfileResolver resolver(ClientsConfig cfg, Path baseDir) {
        List<String> errors = new ArrayList<>();
        List<ClientProfile> ordered = new ArrayList<>(cfg.profiles());
        if (cfg.profilesFile() != null && !cfg.profilesFile().isBlank()) {
            Path file = baseDir == null ? Path.of(cfg.profilesFile()) : baseDir.resolve(cfg.profilesFile());
            try {
                ordered.addAll(Json.mapper().readValue(file.toFile(), ProfilesFile.class).profiles());
                LOG.info("Client profiles read from " + file);
            } catch (IOException e) {
                errors.add("clients.profilesFile " + file + ": " + e.getMessage());
            }
        }
        Set<String> userIds = new HashSet<>();
        for (ClientProfile p : ordered) {
            if (p != null && p.id() != null && !userIds.add(p.id())) {
                errors.add("duplicate profile id '" + p.id() + "'");
            }
        }
        if (cfg.builtins()) {
            for (ClientProfile b : builtins()) {
                if (!userIds.contains(b.id())) {   // a user profile with the same id overrides the built-in
                    ordered.add(b);
                }
            }
        }
        for (ClientProfile p : ordered) {
            validate(p, false, errors);
        }
        ClientProfile def = cfg.defaultProfile() == null ? ClientProfile.builtinDefault()
                : withId(cfg.defaultProfile(), ClientProfile.DEFAULT_ID);
        validate(def, true, errors);
        if (!errors.isEmpty()) {
            throw new InvalidProfilesException(errors);
        }
        ClientProfileResolver r = new ClientProfileResolver(ordered, def);
        LOG.info("Client profiles: " + ordered.size() + " (" + (cfg.builtins() ? "built-ins on" : "built-ins off")
                + "), default native formats " + def.nativeFormats());
        return r;
    }

    /** For tests and programmatic use. */
    public static ClientProfileResolver of(List<ClientProfile> ordered, ClientProfile defaultProfile) {
        List<String> errors = new ArrayList<>();
        ordered.forEach(p -> validate(p, false, errors));
        validate(defaultProfile, true, errors);
        if (!errors.isEmpty()) {
            throw new InvalidProfilesException(errors);
        }
        return new ClientProfileResolver(ordered, defaultProfile);
    }

    /** A resolver with only the default profile. */
    public static ClientProfileResolver defaultOnly() {
        return new ClientProfileResolver(List.of(), ClientProfile.builtinDefault());
    }

    /** The shipped profiles (provisional until the client compatibility pass). */
    public static List<ClientProfile> builtins() {
        try (InputStream in = ClientProfiles.class.getResourceAsStream(BUILTIN_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + BUILTIN_RESOURCE);
            }
            return Json.mapper().readValue(in, ProfilesFile.class).profiles();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + BUILTIN_RESOURCE + ": " + e.getMessage(), e);
        }
    }

    private static ClientProfile withId(ClientProfile p, String id) {
        return new ClientProfile(id, p.description(), p.match(), p.nativeFormats(), p.resOrder(),
                p.transcodeTarget(), p.bitrateKbps(), p.quirks(), p.maxSampleRate());
    }

    static void validate(ClientProfile p, boolean isDefault, List<String> errors) {
        if (p == null) {
            errors.add("null profile");
            return;
        }
        String id = p.id() == null ? "?" : p.id();
        if (p.id() == null || !ID.matcher(p.id()).matches()) {
            errors.add("profile '" + id + "': id must match [a-z0-9][a-z0-9._-]*");
        } else if (!isDefault && p.id().equals(ClientProfile.DEFAULT_ID)) {
            errors.add("profile id 'default' is reserved");
        }
        if (p.bitrateKbps() != 0 && !ClientProfile.BITRATES.contains(p.bitrateKbps())) {
            errors.add("profile '" + id + "': bitrateKbps must be 0 or one of " + ClientProfile.BITRATES);
        }
        if (p.maxSampleRate() != 0 && (p.maxSampleRate() < 8_000 || p.maxSampleRate() > 768_000)) {
            errors.add("profile '" + id + "': maxSampleRate must be 0 or between 8000 and 768000");
        }
        if (isDefault) {
            return;
        }
        ProfileMatch m = p.match();
        if (m.isEmpty()) {
            errors.add("profile '" + id + "': match needs a userAgentRegex, a header or an ipCidr");
        }
        if (m.userAgentRegex() != null && !m.userAgentRegex().isBlank()) {
            try {
                Pattern.compile(m.userAgentRegex());
            } catch (PatternSyntaxException e) {
                errors.add("profile '" + id + "': match.userAgentRegex: " + e.getDescription());
            }
        }
        for (HeaderMatch h : m.headers()) {
            if (h.name() == null || h.name().isBlank()) {
                errors.add("profile '" + id + "': a header match needs a name");
                continue;
            }
            try {
                h.compile();
            } catch (PatternSyntaxException e) {
                errors.add("profile '" + id + "': header " + h.name() + " regex: " + e.getDescription());
            }
        }
        for (String c : m.ipCidrs()) {
            try {
                ClientProfileResolver.Cidr.parse(c);
            } catch (IllegalArgumentException e) {
                errors.add("profile '" + id + "': match.ipCidrs: " + e.getMessage());
            }
        }
    }
}
