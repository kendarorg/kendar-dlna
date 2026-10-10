package org.kendar.dlna.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ActiveStreamsTest {

    @Test
    void streamsAreListedWhileOpenAndGoneAfterClose() {
        ActiveStreams a = new ActiveStreams();
        ActiveStreams.Handle h1 = a.open("1.1.1.1", "f.x", "t1", null);
        ActiveStreams.Handle h2 = a.openForPath("2.2.2.2", "/r.dir.snap/t2/mp3");
        assertThat(a.count()).isEqualTo(2);
        assertThat(a.list()).extracting(ActiveStreams.Info::variant).containsExactly("original", "mp3");
        assertThat(a.list().get(1).containerId()).isEqualTo("r.dir.snap");
        assertThat(a.list().get(1).itemId()).isEqualTo("t2");
        h1.close();
        assertThat(a.list()).extracting(ActiveStreams.Info::client).containsExactly("2.2.2.2");
        h2.close();
        h2.close();   // idempotent
        assertThat(a.count()).isZero();
    }

    @Test
    void malformedPathsOpenNothing() {
        ActiveStreams a = new ActiveStreams();
        a.openForPath("x", "/only-one").close();
        a.openForPath("x", null).close();
        a.openForPath("x", "/a/b/c/d").close();
        assertThat(a.count()).isZero();
    }
}
