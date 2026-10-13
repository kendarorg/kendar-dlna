package org.kendar.dlna.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.kendar.dlna.shared.ClientId;
import org.kendar.dlna.shared.Json;

class ClientProfilesTest {

    @TempDir
    Path dir;

    private static ClientProfile profile(String id, String ua, MediaFormat... nativeFormats) {
        return new ClientProfile(id, "", ProfileMatch.userAgent(ua), nativeFormats.length == 0 ? Set.of()
                : Set.copyOf(List.of(nativeFormats)), null, null, 0, null);
    }

    @Test
    void defaultProfileOffersMp3NativeAndTranscodesTheRest() {
        ClientProfile d = ClientProfile.builtinDefault();
        assertThat(d.resPlan(MediaFormat.MP3)).containsExactly(ResKind.NATIVE);
        for (MediaFormat f : List.of(MediaFormat.FLAC, MediaFormat.OGG, MediaFormat.WAV, MediaFormat.AIFF,
                MediaFormat.OPUS, MediaFormat.AAC, MediaFormat.ALAC, MediaFormat.ADTS)) {
            assertThat(d.resPlan(f)).containsExactly(ResKind.TRANSCODED, ResKind.NATIVE);
        }
        assertThat(d.resPlan(MediaFormat.MIDI)).containsExactly(ResKind.TRANSCODED);
    }

    @Test
    void resPlanFollowsOrderAndQuirks() {
        ClientProfile nativeFirst = new ClientProfile("a", "", ProfileMatch.userAgent("a"), Set.of(),
                ResOrder.NATIVE_FIRST, null, 0, null);
        assertThat(nativeFirst.resPlan(MediaFormat.FLAC)).containsExactly(ResKind.NATIVE, ResKind.TRANSCODED);
        ClientProfile hide = new ClientProfile("b", "", ProfileMatch.userAgent("b"), Set.of(MediaFormat.MP3), null,
                null, 0, Set.of(Mp3Quirk.HIDE_UNPLAYABLE_ORIGINAL));
        assertThat(hide.resPlan(MediaFormat.FLAC)).containsExactly(ResKind.TRANSCODED);
        assertThat(hide.resPlan(MediaFormat.MP3)).containsExactly(ResKind.NATIVE);
        ClientProfile avoid = new ClientProfile("c", "", ProfileMatch.userAgent("c"), Set.of(MediaFormat.MP3), null,
                null, 0, Set.of(Mp3Quirk.AVOID_NATIVE_MP3));
        assertThat(avoid.resPlan(MediaFormat.MP3)).containsExactly(ResKind.TRANSCODED, ResKind.NATIVE);
        ClientProfile single = new ClientProfile("d", "", ProfileMatch.userAgent("d"), Set.of(), null, null, 0,
                Set.of(Mp3Quirk.SINGLE_RES));
        assertThat(single.resPlan(MediaFormat.FLAC)).containsExactly(ResKind.TRANSCODED);
    }

    @Test
    void midiIsNeverNative() {
        ClientProfile p = profile("m", "m", MediaFormat.MIDI, MediaFormat.MP3);
        assertThat(p.nativeFormats()).containsExactly(MediaFormat.MP3);
        assertThat(p.resPlan(MediaFormat.MIDI)).containsExactly(ResKind.TRANSCODED);
    }

    @Test
    void firstMatchWinsAndUnknownClientsGetTheDefault() {
        ClientProfileResolver r = ClientProfiles.of(List.of(profile("wide", "Player", MediaFormat.FLAC),
                profile("narrow", "Player 2", MediaFormat.OGG)), ClientProfile.builtinDefault());
        assertThat(r.resolve(ClientRequest.of("10.0.0.1", "Player 2.0")).id()).isEqualTo("wide");
        assertThat(r.resolve(ClientRequest.of("10.0.0.1", "other")).id()).isEqualTo("default");
        assertThat(r.resolve((ClientRequest) null).id()).isEqualTo("default");
        assertThat(r.resolve(new ClientId("10.0.0.1", "PLAYER x")).id()).isEqualTo("wide");   // case-insensitive
        assertThat(r.resolve(ClientId.UNKNOWN).id()).isEqualTo("default");
    }

    @Test
    void headerAndCidrPredicatesAreAnded() {
        ProfileMatch m = new ProfileMatch("TV", List.of(new HeaderMatch("x-av-client-info", "samsung", false),
                new HeaderMatch("X-Skip", null, true)), List.of("192.168.1.0/24", "10.0.0.5"));
        ClientProfile tv = new ClientProfile("tv", "", m, Set.of(MediaFormat.WAV), null, null, 0, null);
        ClientProfileResolver r = ClientProfiles.of(List.of(tv), ClientProfile.builtinDefault());
        Map<String, List<String>> h = Map.of("User-Agent", List.of("Smart TV"),
                "X-AV-Client-Info", List.of("av=5.0; cn=\"Samsung\""));
        assertThat(r.resolve(new ClientRequest("192.168.1.77", h)).id()).isEqualTo("tv");
        assertThat(r.resolve(new ClientRequest("::ffff:192.168.1.77", h)).id()).isEqualTo("tv");
        assertThat(r.resolve(new ClientRequest("10.0.0.5", h)).id()).isEqualTo("tv");
        assertThat(r.resolve(new ClientRequest("192.168.2.77", h)).id()).isEqualTo("default");
        assertThat(r.resolve(new ClientRequest("hostname", h)).id()).isEqualTo("default");
        Map<String, List<String>> skip = Map.of("User-Agent", List.of("Smart TV"),
                "X-AV-Client-Info", List.of("samsung"), "X-Skip", List.of("1"));
        assertThat(r.resolve(new ClientRequest("192.168.1.77", skip)).id()).isEqualTo("default");
    }

    @Test
    void theUniversalMediaServerRenderersAreBuiltInAndMostSpecificFirst() {
        List<ClientProfile> all = ClientProfiles.builtins();
        assertThat(all.size()).isGreaterThan(100);
        assertThat(all).extracting(ClientProfile::id).doesNotHaveDuplicates().allMatch(i -> i.matches("[a-z0-9][a-z0-9._-]*"));
        ClientProfileResolver r = ClientProfiles.resolver(ClientsConfig.defaults(), dir);
        ClientProfile ps4 = r.resolve(ClientRequest.of("1.1.1.1", "PS4Application libhttp/1.000 (PS4) libhttp/2.51 (PlayStation 4)"));
        assertThat(ps4.id()).isEqualTo("ums-sony-playstation4");
        assertThat(ps4.nativeFormats()).contains(MediaFormat.FLAC, MediaFormat.MP3, MediaFormat.AAC)
                .doesNotContain(MediaFormat.OGG);
        assertThat(ps4.resPlan(MediaFormat.FLAC)).containsExactly(ResKind.NATIVE);
        assertThat(ps4.resPlan(MediaFormat.OGG)).contains(ResKind.TRANSCODED);
        // a renderer that wants WAV and 44.1 kHz
        assertThat(all).anySatisfy(p -> {
            assertThat(p.transcodeTarget()).isEqualTo(TranscodeTarget.WAV);
        }).anySatisfy(p -> assertThat(p.maxSampleRate()).isEqualTo(44100));
    }

    @Test
    void maxSampleRateIsValidatedAndKeptWhenCopied() {
        ClientProfile p = new ClientProfile("x", "", ProfileMatch.userAgent("x"), Set.of(MediaFormat.MP3), null,
                TranscodeTarget.WAV, 0, Set.of(), 44100);
        assertThat(ClientProfiles.of(List.of(p), ClientProfile.builtinDefault())).isNotNull();
        ClientProfile bad = new ClientProfile("y", "", ProfileMatch.userAgent("y"), Set.of(), null, null, 0, Set.of(), 100);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ClientProfiles.of(List.of(bad), ClientProfile.builtinDefault()))
                .hasMessageContaining("maxSampleRate");
    }

    @Test
    void builtinProfilesLoadAndMatchTheirClients() {
        ClientProfileResolver r = ClientProfiles.resolver(ClientsConfig.defaults(), dir);
        assertThat(r.resolve(ClientRequest.of("1.1.1.1", "VLC/3.0.20 LibVLC/3.0.20")).id()).isIn("vlc", "ums-vlc-for-desktop");
        ClientProfile samsung = r.resolve(ClientRequest.of("1.1.1.1", "SEC_HHP_[TV]UE55/1.0 DLNADOC/1.50"));
        assertThat(samsung.id()).isIn("samsung-tv").isNotEqualTo("default").satisfies(i -> assertThat(i).matches("samsung-tv|ums-samsung.*"));
        assertThat(samsung.resPlan(MediaFormat.FLAC).size()).isLessThanOrEqualTo(2);
        assertThat(r.resolve(ClientRequest.of("1.1.1.1", "Unknown/1")).id()).isEqualTo("default");
    }

    @Test
    void userProfilesComeFirstOverrideBuiltinsAndLoadFromFile() throws Exception {
        Files.writeString(dir.resolve("clients.json"), """
                {"profiles":[{"id":"hifi","match":{"userAgentRegex":"Linn"},"nativeFormats":["WAV"],
                 "transcodeTarget":"LPCM","quirks":["AVOID_NATIVE_MP3"]}]}""");
        ClientProfile inline = profile("vlc", "VLC", MediaFormat.MP3);
        ClientsConfig cfg = new ClientsConfig(true, "clients.json", List.of(inline), null);
        ClientProfileResolver r = ClientProfiles.resolver(cfg, dir);
        assertThat(r.resolve(ClientRequest.of("1.1.1.1", "VLC")).nativeFormats()).containsExactly(MediaFormat.MP3);
        ClientProfile hifi = r.resolve(ClientRequest.of("1.1.1.1", "Linn Klimax"));
        assertThat(hifi.transcodeTarget()).isEqualTo(TranscodeTarget.LPCM);
        assertThat(hifi.resPlan(MediaFormat.MP3)).containsExactly(ResKind.TRANSCODED, ResKind.NATIVE);
        assertThat(r.profiles().stream().filter(p -> p.id().equals("vlc"))).hasSize(1);
    }

    @Test
    void builtinsCanBeDisabledAndTheDefaultOverridden() {
        ClientProfile def = new ClientProfile("whatever", "", null, Set.of(MediaFormat.FLAC), null, null, 256, null);
        ClientProfileResolver r = ClientProfiles.resolver(new ClientsConfig(false, null, null, def), dir);
        assertThat(r.profiles()).isEmpty();
        assertThat(r.defaultProfile().id()).isEqualTo("default");
        assertThat(r.defaultProfile().bitrateKbps()).isEqualTo(256);
        assertThat(r.resolve(ClientRequest.of("1.1.1.1", "VLC")).nativeFormats()).containsExactly(MediaFormat.FLAC);
    }

    @Test
    void invalidTablesAreRejectedWithEveryProblemListed() {
        ClientProfile badRegex = profile("bad1", "(unclosed", MediaFormat.MP3);
        ClientProfile badId = profile("Bad Id", "x");
        ClientProfile noMatch = new ClientProfile("bad3", "", null, Set.of(), null, null, 0, null);
        ClientProfile badRate = new ClientProfile("bad4", "", ProfileMatch.userAgent("y"), Set.of(), null, null, 123,
                null);
        ClientProfile badCidr = new ClientProfile("bad5", "", new ProfileMatch(null, null, List.of("300.1.1.1/8",
                "10.0.0.0/40", "example.org")), Set.of(), null, null, 0, null);
        ClientProfile dup = profile("bad1", "z");
        assertThatThrownBy(() -> ClientProfiles.of(List.of(badRegex, badId, noMatch, badRate, badCidr),
                ClientProfile.builtinDefault())).isInstanceOfSatisfying(InvalidProfilesException.class, e ->
                assertThat(e.errors()).hasSizeGreaterThanOrEqualTo(7));
        assertThatThrownBy(() -> ClientProfiles.resolver(new ClientsConfig(false, null, List.of(badRegex, dup), null),
                dir)).isInstanceOfSatisfying(InvalidProfilesException.class, e ->
                assertThat(e.errors()).anyMatch(s -> s.contains("duplicate profile id 'bad1'")));
        assertThatThrownBy(() -> ClientProfiles.resolver(new ClientsConfig(false, "missing.json", null, null), dir))
                .isInstanceOf(InvalidProfilesException.class).hasMessageContaining("missing.json");
    }

    @Test
    void configSectionParsesFromJson() throws Exception {
        ClientsConfig cfg = Json.mapper().readValue("""
                {"builtins":false,"profiles":[{"id":"x","match":{"userAgentRegex":"X"},
                 "nativeFormats":["MP3","MIDI"],"resOrder":"NATIVE_FIRST","bitrateKbps":192}]}""", ClientsConfig.class);
        assertThat(cfg.builtins()).isFalse();
        ClientProfile p = cfg.profiles().get(0);
        assertThat(p.nativeFormats()).containsExactly(MediaFormat.MP3);
        assertThat(p.resOrder()).isEqualTo(ResOrder.NATIVE_FIRST);
        assertThat(p.bitrateKbps()).isEqualTo(192);
        // and survives a round trip through the template writer
        ClientsConfig back = Json.mapper().readValue(Json.mapper().writeValueAsString(cfg), ClientsConfig.class);
        assertThat(back).isEqualTo(cfg);
    }
}
