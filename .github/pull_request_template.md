## Summary

<!-- What changed, and why. Link an issue if there is one. -->

## Test plan

<!--
Be specific: which commands did you run, and what would they have caught?
"Ran the tests" is less useful than naming which ones.
-->

- [ ] `./gradlew :processor:test` — if you touched `annotations`, `processor`, or generated SQL/codegen
- [ ] `./gradlew :runtime:jvmTest` — if you touched `runtime` (migrator, DSL, transactions)
- [ ] `./gradlew :runtime:iosSimulatorArm64Test` — if the change affects behavior that must hold on iOS too (requires an Apple Silicon Mac)
- [ ] `./gradlew :gradle-plugin:test` — if you touched `gradle-plugin`
- [ ] Consumer smoke test (`publishToMavenLocal` + `integration-tests/consumer-smoke`) — if you touched the plugin's coordinates, versioning, or publishing config
- [ ] `./gradlew apiCheck` — if you added/changed/removed a public symbol in `annotations` or `runtime` (run `apiDump` and commit the updated snapshot if the change is intentional)
- [ ] `./gradlew ktlintCheck` — always; run `ktlintFormat` first if it fails

## Breaking changes

<!-- Public API removed/narrowed in annotations or runtime? Say so explicitly, even if apiCheck already caught it. -->

None / <!-- describe -->
