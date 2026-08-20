# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

electroMe prices a household's own half-hourly interval data against retail electricity tariffs,
so it can rank plans by what they would actually have cost. Australian (Victorian) market, plans
sourced either from hand-written YAML or harvested from the Consumer Data Right register.

## Commands

Use `gradle21w` (on the path, pins Java 21) rather than `./gradlew`.

```bash
gradle21w clean test                  # whole suite
gradle21w :core:test                  # one module
gradle21w :core:test --tests '*CostingEngineTest'      # one test class
gradle21w :core:test --tests '*CostingEngineTest.costsFlatRateUsage'   # one method
gradle21w quarkusDev                  # dev mode on :8080, Dev UI at /q/dev-ui
gradle21w antora                      # documentation site into build/docs/site
```

There is no formatter or lint plugin; `-Xlint:all` is on for every compile.

### Opt-in tests

Three system properties gate tests, and the root build declares them as test-task inputs so a
changed value actually re-runs the test instead of replaying a cached result:

```bash
gradle21w :market:test -Delectrome.live=true          # LiveHarvestTest, hits the real CDR register
gradle21w :ingest:test -Delectrome.usage.csv=/path/to/export.csv   # GoldenBillTest against real data
```

`electrome.cache` redirects the live harvest cache. Without these flags the tests skip via
`assumeTrue` — a skipped test here is expected, not a failure.

## Module structure

Dependencies run one way: `core` ← `ingest`/`market` ← `web`.

- **`core`** — domain, tariff model, costing and scenario engines. **Zero runtime dependencies,
  deliberately.** Anything needing a library belongs in `ingest`, `market` or `web`. This is why
  the YAML mapping is hand-written rather than annotation-driven.
- **`ingest`** — `UsageCsvReader` (retailer half-hourly export CSV) and `PlanYamlLoader` (plan
  YAML → `Plan`). Jackson lives here.
- **`market`** — CDR client, on-disk response cache, and the mapper from CDR product reference
  data to a `Plan`.
- **`web`** — Quarkus + Qute + HTMX + ECharts. Split into `app` (services, config-backed stores),
  `view` (render-ready records) and `web` (JAX-RS resources).

## Architecture notes that matter

**`Charge` is a sealed interface** (`DailySupply`, `FlatRate`, `TimeOfUse`, `Tiered`, `Demand`,
`SolarFeedIn`, `Discount`). `CostingEngine.cost` switches over it with no default branch, so
adding a charge kind is a compile error until the engine costs it. Adding a charge kind means
touching: the sealed permits clause, `CostingEngine`, `PlanYamlLoader`, `PlanValidator`,
`CdrPlanMapper`, and `tariff-reference.adoc`.

**`Scenario` is likewise sealed** (`LoadShift`, `AddSolar`, `AddBattery`) and is a *pure
transformation of a usage series*. Scenarios are then costed by the unchanged `CostingEngine`, so
a scenario and its baseline can never disagree about arithmetic. Keep them deterministic and free
of clock or network access.

**Money is `BigDecimal`, rates are in cents, and plans are normalised to GST-inclusive at load
time** (`PlanYamlLoader` applies the 1.1 multiplier; `Plan.gstInclusive` records what the source
provided).

**The web layer returns HTML, never JSON.** `FragmentResource`, `MarketResource` and
`ScenarioResource` all serve HTMX fragments under `/fragments/…`; `HomeResource` serves only the
shell. Templates are bound through Qute `@CheckedTemplate` — which is why `-parameters` is on for
javac. `quarkus.qute.property-not-found-strategy=throw-exception` is intentional: a template typo
must fail loudly.

**Charts are configured in Java, not JavaScript.** `view/ChartOptions` builds the ECharts option
object, `view/Charts` serialises it into a data attribute, and `charts.js` only hands it to
ECharts. Templates stay dumb: no arithmetic, no colour choices, no sorting — all of that belongs
in `view/Dashboard` and friends where it can be unit tested. ECharts and HTMX are vendored under
`web/src/main/resources/META-INF/resources/vendor/`, not fetched from a CDN.

**`app/Workspace` resolves relative config paths by walking up ancestor directories.** Do not
"simplify" it. The working directory differs between tests (module dir), `quarkusDev`
(`build/classes/java/main`) and a packaged jar, so a plain relative path silently resolves
differently in each.

**Missing data must not break startup.** A missing usage CSV or plans directory is reported on the
page naming the absolute path searched; it never fails the boot. Market harvesting is triggered
explicitly from the page — never on startup — so the app is useful with no network.

## Data and privacy

The household interval export carries an account number and NMI and reveals occupancy. `.gitignore`
excludes `/*.csv` at the repository root only — a blanket `*.csv` also caught the test fixtures and
left a fresh clone unable to run its own suite. Never commit real usage data, and keep account
numbers, NMIs and addresses out of code, comments, docs and commit messages.

Tests own their own fixtures (`*/src/test/resources/`). Do not point a test at the live `plans/`
directory: that directory is the user's to edit, and a test asserting its contents breaks when they
add a plan.

## Tests

JUnit 5 + AssertJ everywhere. `web` uses `@QuarkusTest` with a `@TestProfile` implementing
`QuarkusTestProfile` to override `electrome.usage.csv` and `electrome.plans.dir` at test-resource
paths — follow that pattern for any new Quarkus test.

## Documentation

Antora sources live in `src/docs` (component `electrome`, single ROOT module). New pages need a
`nav.adoc` entry. Build and check with `gradle21w antora`; output lands in `build/docs/site`.
Mermaid diagrams are externalised into an `examples` directory rather than inlined.

`plans/*.yaml` are live tariff definitions the user maintains, not fixtures. `design-market/`,
`design-phase2/` and `docs/superpowers/` are untracked working artefacts.
