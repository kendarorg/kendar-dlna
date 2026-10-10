package org.kendar.dlna.dlna;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.kendar.dlna.search.Field;
import org.kendar.dlna.search.Kind;
import org.kendar.dlna.search.Query;
import org.kendar.dlna.search.Query.Op;

class SearchCriteriaParserTest {

    private static Query parse(String s) throws Exception {
        return SearchCriteriaParser.parse(s);
    }

    private static Query.Term term(Field f, Op op, String v) {
        return new Query.Term(f, op, v);
    }

    @Test
    void star_matchesAll() throws Exception {
        assertThat(parse(" * ")).isEqualTo(new Query.All());
    }

    @Test
    void simpleContains() throws Exception {
        assertThat(parse("dc:title contains \"love\"")).isEqualTo(term(Field.TITLE, Op.CONTAINS, "love"));
    }

    @Test
    void propertiesMapToFields() throws Exception {
        assertThat(parse("upnp:artist contains \"a\"")).isEqualTo(term(Field.ARTIST, Op.CONTAINS, "a"));
        assertThat(parse("dc:creator contains \"a\"")).isEqualTo(term(Field.ARTIST, Op.CONTAINS, "a"));
        assertThat(parse("upnp:album = \"a\"")).isEqualTo(term(Field.ALBUM, Op.EQUALS, "a"));
        assertThat(parse("upnp:genre != \"a\"")).isEqualTo(term(Field.GENRE, Op.NOT_EQUALS, "a"));
        assertThat(parse("dc:title doesNotContain \"a\"")).isEqualTo(term(Field.TITLE, Op.NOT_CONTAINS, "a"));
        assertThat(parse("dc:title derivedfrom \"a\"")).isEqualTo(term(Field.TITLE, Op.STARTS_WITH, "a"));
    }

    @Test
    void symbolOperatorsNeedNoSpaces() throws Exception {
        assertThat(parse("upnp:album=\"x\"")).isEqualTo(term(Field.ALBUM, Op.EQUALS, "x"));
        assertThat(parse("upnp:album!=\"x\"")).isEqualTo(term(Field.ALBUM, Op.NOT_EQUALS, "x"));
        assertThat(parse("(upnp:album=\"x\")")).isEqualTo(term(Field.ALBUM, Op.EQUALS, "x"));
    }

    @Test
    void keywordsAreCaseInsensitive() throws Exception {
        assertThat(parse("dc:title CONTAINS \"a\" AND upnp:artist Contains \"b\"")).isEqualTo(new Query.And(List.of(
                term(Field.TITLE, Op.CONTAINS, "a"), term(Field.ARTIST, Op.CONTAINS, "b"))));
    }

    @Test
    void andBindsTighterThanOr() throws Exception {
        Query q = parse("dc:title contains \"a\" or dc:title contains \"b\" and dc:title contains \"c\"");
        assertThat(q).isEqualTo(new Query.Or(List.of(term(Field.TITLE, Op.CONTAINS, "a"),
                new Query.And(List.of(term(Field.TITLE, Op.CONTAINS, "b"), term(Field.TITLE, Op.CONTAINS, "c"))))));
    }

    @Test
    void parenthesesOverridePrecedence() throws Exception {
        Query q = parse("(dc:title contains \"a\" or dc:title contains \"b\") and dc:title contains \"c\"");
        assertThat(q).isEqualTo(new Query.And(List.of(
                new Query.Or(List.of(term(Field.TITLE, Op.CONTAINS, "a"), term(Field.TITLE, Op.CONTAINS, "b"))),
                term(Field.TITLE, Op.CONTAINS, "c"))));
    }

    @Test
    void typicalClientQuery() throws Exception {
        Query q = parse("upnp:class derivedfrom \"object.item.audioItem\" and "
                + "(dc:title contains \"x\" or upnp:artist contains \"x\" or upnp:album contains \"x\")");
        assertThat(q).isInstanceOf(Query.And.class);
        Query.And and = (Query.And) q;
        assertThat(and.parts().get(0)).isEqualTo(new Query.Kinds(EnumSet.of(Kind.TRACK)));
        assertThat(((Query.Or) and.parts().get(1)).parts()).hasSize(3);
    }

    @Test
    void quotedStringsKeepParenthesesKeywordsAndEscapes() throws Exception {
        assertThat(parse("dc:title contains \"a (b) and c\"")).isEqualTo(term(Field.TITLE, Op.CONTAINS, "a (b) and c"));
        assertThat(parse("dc:title = \"say \\\"hi\\\" \\\\ now\"")).isEqualTo(term(Field.TITLE, Op.EQUALS,
                "say \"hi\" \\ now"));
        assertThat(parse("dc:title contains \"\"")).isEqualTo(term(Field.TITLE, Op.CONTAINS, ""));
    }

    @Test
    void existsTrueAndFalse() throws Exception {
        assertThat(parse("upnp:genre exists true")).isEqualTo(new Query.Exists(Field.GENRE, true));
        assertThat(parse("upnp:genre exists false")).isEqualTo(new Query.Exists(Field.GENRE, false));
    }

    @Test
    void classOperators() throws Exception {
        assertThat(parse("upnp:class derivedfrom \"object.item\"")).isEqualTo(new Query.Kinds(EnumSet.of(Kind.TRACK)));
        assertThat(parse("upnp:class derivedfrom \"object.container\""))
                .isEqualTo(new Query.Kinds(EnumSet.of(Kind.FOLDER)));
        assertThat(parse("upnp:class derivedfrom \"object\"")).isEqualTo(new Query.Kinds(EnumSet.allOf(Kind.class)));
        assertThat(parse("upnp:class = \"object.item.audioItem.musicTrack\""))
                .isEqualTo(new Query.Kinds(EnumSet.of(Kind.TRACK)));
        assertThat(parse("upnp:class = \"object.item.audioItem\"")).isEqualTo(new Query.Kinds(EnumSet.noneOf(Kind.class)));
        assertThat(parse("upnp:class != \"object.container.storageFolder\""))
                .isEqualTo(new Query.Kinds(EnumSet.of(Kind.TRACK)));
        assertThat(parse("upnp:class derivedfrom \"object.itemX\"")).isEqualTo(new Query.Kinds(EnumSet.noneOf(Kind.class)));
        assertThat(parse("upnp:class exists true")).isEqualTo(new Query.Kinds(EnumSet.allOf(Kind.class)));
    }

    @Test
    void unknownButWellFormedPropertyIsAbsent() throws Exception {
        assertThat(parse("@refID exists false")).isEqualTo(new Query.All());
        assertThat(parse("@refID exists true")).isEqualTo(new Query.None());
        assertThat(parse("upnp:originalTrackNumber = \"3\"")).isEqualTo(new Query.None());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "dc:title", "dc:title contains", "dc:title contains love", "dc:title contains \"love",
            "dc:title contains \"love\\", "dc:title bogus \"x\"", "dc:title < \"x\"", "dc:title >= \"x\"",
            "dc:title contains \"a\" and", "and dc:title contains \"a\"", "dc:title contains \"a\" or or",
            "(dc:title contains \"a\"", "dc:title contains \"a\")", "()", "dc:title contains \"a\" dc:title contains \"b\"",
            "dc:title exists maybe", "dc:title exists", "dc:title ! \"x\"", "\"dc:title\" contains \"x\"",
            "contains dc:title \"x\"", "dc:title contains (\"x\")", "* and dc:title contains \"x\"", "and contains \"x\"",
            "dc:title = = \"x\"", "$$ contains \"x\"" })
    void malformedCriteriaAreRejectedCleanly(String criteria) {
        assertThatThrownBy(() -> SearchCriteriaParser.parse(criteria))
                .isInstanceOf(InvalidSearchCriteriaException.class);
    }

    @Test
    void nullIsRejected() {
        assertThatThrownBy(() -> SearchCriteriaParser.parse(null)).isInstanceOf(InvalidSearchCriteriaException.class);
    }

    @Test
    void deepNestingIsRejectedWithoutStackOverflow() {
        String deep = "(".repeat(100_000) + "dc:title contains \"a\"" + ")".repeat(100_000);
        assertThatThrownBy(() -> SearchCriteriaParser.parse(deep)).isInstanceOf(InvalidSearchCriteriaException.class);
    }

    @Test
    void moderateNestingWorks() throws Exception {
        String s = "(".repeat(20) + "dc:title contains \"a\"" + ")".repeat(20);
        assertThat(parse(s)).isEqualTo(term(Field.TITLE, Op.CONTAINS, "a"));
    }

    @Test
    void longChainsOfAndOrWork() throws Exception {
        String s = "dc:title contains \"a\"" + " or dc:title contains \"a\"".repeat(5000);
        assertThat(((Query.Or) parse(s)).parts()).hasSize(5001);
    }
}
