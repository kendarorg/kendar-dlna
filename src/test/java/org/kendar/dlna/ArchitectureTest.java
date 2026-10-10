package org.kendar.dlna;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Bounded-context dependency rules from plan section 15. */
@AnalyzeClasses(packages = "org.kendar.dlna", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "org.kendar.dlna.";

    private static String[] pkgs(String... names) {
        String[] out = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = BASE + names[i] + "..";
        }
        return out;
    }

    private static ArchRule onlyDependsOn(String context, String... allowed) {
        java.util.List<String> forbidden = new java.util.ArrayList<>(java.util.List.of(
                "shared", "catalog", "ingestion", "curation", "search", "shuffle", "views", "artwork", "transcoding",
                "clients", "delivery", "dlna", "adminweb", "platform", "identity", "libraryquery", "personal"));
        forbidden.remove(context);
        forbidden.removeAll(java.util.List.of(allowed));
        return noClasses().that().resideInAPackage(BASE + context + "..")
                .should().dependOnClassesThat().resideInAnyPackage(pkgs(forbidden.toArray(String[]::new)))
                .allowEmptyShould(true);
    }

    @ArchTest
    static final ArchRule shared_depends_on_nothing = onlyDependsOn("shared");

    @ArchTest
    static final ArchRule catalog_only_shared = onlyDependsOn("catalog", "shared");

    @ArchTest
    static final ArchRule ingestion = onlyDependsOn("ingestion", "shared", "catalog");

    @ArchTest
    static final ArchRule curation = onlyDependsOn("curation", "shared", "catalog", "ingestion");

    @ArchTest
    static final ArchRule search = onlyDependsOn("search", "shared", "catalog");

    @ArchTest
    static final ArchRule shuffle = onlyDependsOn("shuffle", "shared", "catalog");

    @ArchTest
    static final ArchRule views = onlyDependsOn("views", "shared", "catalog");

    @ArchTest
    static final ArchRule artwork = onlyDependsOn("artwork", "shared", "catalog");

    @ArchTest
    static final ArchRule transcoding = onlyDependsOn("transcoding");

    @ArchTest
    static final ArchRule clients = onlyDependsOn("clients", "shared");

    @ArchTest
    static final ArchRule delivery = onlyDependsOn("delivery", "shared", "catalog", "shuffle", "views", "transcoding",
            "clients");

    @ArchTest
    static final ArchRule dlna = onlyDependsOn("dlna", "shared", "catalog", "search", "shuffle", "views", "artwork",
            "delivery", "clients");

    @ArchTest
    static final ArchRule identity = onlyDependsOn("identity", "shared");

    @ArchTest
    static final ArchRule personal = onlyDependsOn("personal", "shared", "catalog", "identity");

    @ArchTest
    static final ArchRule libraryquery = onlyDependsOn("libraryquery", "shared", "catalog", "search", "shuffle",
            "views", "artwork", "identity");

    @ArchTest
    static final ArchRule adminweb = onlyDependsOn("adminweb", "shared", "catalog", "search", "curation", "ingestion",
            "shuffle", "delivery", "identity", "libraryquery", "personal");

    @ArchTest
    static final ArchRule no_cycles = slices().matching("org.kendar.dlna.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule catalog_has_no_io_or_adapter_libs = noClasses().that()
            .resideInAPackage(BASE + "catalog..")
            .should().dependOnClassesThat().resideInAnyPackage("java.io..", "java.nio.file..", "java.net..",
                    "org.eclipse.jetty..", "org.jupnp..", "javax.servlet..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule only_adapters_use_upnp_and_jetty_servlet = noClasses().that()
            .resideOutsideOfPackages(BASE + "dlna..", BASE + "platform..")
            .should().dependOnClassesThat().resideInAnyPackage("org.jupnp..")
            .allowEmptyShould(true);
}
