# lutece-bom-test

Consumer smoke test for `lutece-bom`. It builds nothing and ships nothing: its only
job is to fail when the BOM is broken.

## Why it exists

`lutece-bom` is published so downstream projects can import it and declare Lutece
artifacts without a version. Nothing used to verify that it actually works in that
role, and a `<dependencyManagement>` block is unusually good at hiding mistakes:
an entry is a lookup table keyed on `groupId:artifactId:type`. An entry matching no
real artifact is never resolved, never downloaded, and **never reported**. It is
silently inert, and the artifact it was supposed to manage simply stays unmanaged.

The first run of this module found exactly that:

```
'dependencies.dependency.version' for fr.paris.lutece.plugins:module-appointement-rest
must be a valid version but is '${lutece.module-appointement-rest.version}'
@ lutece-bom/pom.xml, line 310
```

Two compounding typos — `appointement` instead of `appointment` in both the
artifactId and the version property — meaning `module-appointment-rest` was not
managed by the BOM at all. A consumer importing the BOM and declaring that module
without a version would have hit *dependency version not specified*.

## What it catches

- a managed entry whose artifactId does not exist
- a managed entry whose `${...}` version property is not defined anywhere
- a declared version that does not exist in the repositories
- a BOM that cannot be imported at all

## What it does NOT catch

It is not a dependency-conflict check. Dependencies are declared in `provided`
scope on purpose: the global pom excludes that scope from `requireUpperBoundDeps`
and `dependencyConvergence`, so transitive version conflicts across the platform
stay out of the way and resolution problems are not drowned in noise. Conflict
checking is the starters' job, on their own graphs.

It also says nothing about whether an artifact is *useful* — only that it resolves.

## Running it

```bash
mvn -Pbom-test -pl bom-test test
```

The module lives behind the `bom-test` profile and is not part of the default
build: it resolves the full transitive closure of the managed artifacts, which is
slow and downloads a lot. Run it whenever `lutece-bom` changes, and in CI.

To make it permanent, move `<module>bom-test</module>` from the `bom-test` profile
into the root `<modules>` block.

`release-helpers.groovy` enumerates its five modules explicitly, so this one is
ignored by the release pipeline. It is also never installed nor deployed
(`maven.install.skip`, `maven.deploy.skip`).

## Keeping the list in sync

The dependency list mirrors `lutece-bom`, so it can drift: add an artifact to the
BOM without regenerating here and that artifact is simply not tested. `regenerate.py`
exists to close that gap.

```bash
python3 bom-test/regenerate.py            # rewrite the generated block
python3 bom-test/regenerate.py --check    # exit 1 if out of date
```

Only the block between the `GENERATED` markers in `pom.xml` is rewritten; the rest
of the file is hand-maintained and preserved.

**Put `--check` in CI.** Without it the drift is invisible, which is the same class
of silent failure this module was built to expose.

Entries commented out in the BOM are ignored: the XML parser drops comments, so a
temporarily disabled plugin is not tested either. That is intentional — it is
disabled, it may not resolve.
