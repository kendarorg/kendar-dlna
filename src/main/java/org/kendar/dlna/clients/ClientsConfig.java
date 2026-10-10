package org.kendar.dlna.clients;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The {@code clients} section of the configuration.
 *
 * @param builtins       false disables the built-in profiles (default true)
 * @param profilesFile   optional JSON file {@code {"profiles":[...]}}, relative to the config directory
 * @param profiles       inline profiles (checked before the file's, then the built-ins)
 * @param defaultProfile overrides the default profile (its match is ignored)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClientsConfig(Boolean builtins, String profilesFile, List<ClientProfile> profiles,
        ClientProfile defaultProfile) {

    public ClientsConfig {
        builtins = builtins == null ? Boolean.TRUE : builtins;
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    public static ClientsConfig defaults() {
        return new ClientsConfig(null, null, null, null);
    }
}
