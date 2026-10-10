package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DidlFilterTest {

    @Test
    void blankNullAndStarSelectEverything() {
        for (String f : new String[] { null, "", "  ", "*", "dc:title, *" }) {
            DidlFilter filter = DidlFilter.parse(f);
            assertThat(filter.wants("upnp:genre")).isTrue();
            assertThat(filter.wantsAttribute("res", "size")).isTrue();
            assertThat(filter.wantsContainerAttribute("childCount")).isTrue();
        }
    }

    @Test
    void listedPropertiesOnly() {
        DidlFilter f = DidlFilter.parse("dc:title, UPNP:Artist ,res");

        assertThat(f.wants("upnp:artist")).isTrue();
        assertThat(f.wants("res")).isTrue();
        assertThat(f.wants("upnp:genre")).isFalse();
        assertThat(f.wants("upnp:albumArtURI")).isFalse();
        assertThat(f.wantsContainerAttribute("childCount")).isFalse();
    }

    @Test
    void resWithoutQualifiersImpliesItsAttributes() {
        DidlFilter f = DidlFilter.parse("res");
        assertThat(f.wantsAttribute("res", "duration")).isTrue();
    }

    @Test
    void attributeQualifiersRestrictAndImplyTheProperty() {
        DidlFilter f = DidlFilter.parse("res@duration,@childCount,upnp:albumArtURI@dlna:profileID");

        assertThat(f.wants("res")).isTrue();
        assertThat(f.wantsAttribute("res", "duration")).isTrue();
        assertThat(f.wantsAttribute("res", "size")).isFalse();
        assertThat(f.wantsContainerAttribute("childCount")).isTrue();
        assertThat(f.wants("upnp:albumArtURI")).isTrue();
        assertThat(f.wantsAttribute("upnp:albumArtURI", "dlna:profileID")).isTrue();
    }
}
