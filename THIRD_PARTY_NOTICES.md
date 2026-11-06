# Third-party notices

Runtime Bay's original Java, Angular, Swift, Go, and C source is licensed under the [MIT License](LICENSE). Third-party code and artwork retain the licenses below. This inventory was updated on 2026-10-22 against `package.json`, `package-lock.json`, installed package license files, `backend/pom.xml`, the shaded backend JAR, generated frontend notices, logo provenance, and the packaged Java runtime.

## Integrated editor additions (2026-11-06)

The integrated editor uses CodeMirror 6.0.2 and its pinned `@codemirror` state, view, command, language, autocomplete, lint and search packages, plus their Lezer parsers and small rendering/input dependencies. These are MIT-licensed. The `yaml` 2.9.1 document parser is ISC-licensed and supplies YAML syntax validation and source ranges for structured editing. Runtime Bay does not replace or relicense either project.

[Editor dependency inventory](licenses/editor/README.md) records every newly added direct/transitive package, its exact pinned version and its unchanged upstream license text. All of those files are copied into the macOS bundle's `Resources/licenses/editor/` directory; Angular's generated third-party bundle notices are also included. Primary documentation: [CodeMirror](https://codemirror.net/docs/) and [yaml](https://eemeli.org/yaml/).

## Code and artwork shipped in the macOS app

| Component | Version | Use | License text | Upstream |
| --- | --- | --- | --- | --- |
| Angular core, common, forms, platform-browser | 21.2.25 | Application interface | [MIT](licenses/Angular-MIT.txt) | [Angular](https://github.com/angular/angular) |
| xterm.js | 6.0.0 | Terminal emulator | [MIT](licenses/xterm-MIT.txt) | [xterm.js](https://github.com/xtermjs/xterm.js) |
| xterm Fit addon | 0.11.0 | Terminal dimensions | [MIT](licenses/xterm-fit-MIT.txt) | [xterm.js addons](https://github.com/xtermjs/xterm.js/tree/master/addons/addon-fit) |
| RxJS | 7.8.2 | Reactive utilities | [Apache-2.0](licenses/RxJS-Apache-2.0.txt) | [RxJS](https://github.com/ReactiveX/rxjs) |
| tslib | 2.8.1 | TypeScript runtime helpers | [0BSD](licenses/tslib-0BSD.txt) | [tslib](https://github.com/microsoft/tslib) |
| GSAP | 3.15.0 | Breathing loader and interface animations | [Standard “No Charge” GSAP License](licenses/GSAP-Standard-License.txt), custom terms | [GSAP](https://gsap.com/standard-license/) |
| SnakeYAML | 2.5 | Strict workflow YAML parsing and export | [Apache-2.0](licenses/SnakeYAML-Apache-2.0.txt) | [SnakeYAML](https://bitbucket.org/snakeyaml/snakeyaml/src/snakeyaml-2.5/) |
| Gson | 2.13.2 | JSON serialization | [Apache-2.0](licenses/Gson-Apache-2.0.txt) | [Gson](https://github.com/google/gson/tree/gson-parent-2.13.2) |
| Error Prone annotations | 2.41.0 | Gson transitive dependency in the shaded JAR | [Apache-2.0](licenses/ErrorProne-Apache-2.0.txt) | [Error Prone](https://github.com/google/error-prone/tree/v2.41.0) |
| Devicon SVG artwork | v2.17.0 | Developer tool logos | [MIT](licenses/Devicon-MIT.txt); trademarks retained by their owners | [Devicon](https://github.com/devicons/devicon/tree/v2.17.0) |
| OpenJDK runtime | Java 25, selected during packaging | Runs the Java backend | GPL-2.0 with the Classpath Exception and bundled module-specific notices | [OpenJDK](https://openjdk.org/) |

### Preserved attribution

- **SnakeYAML:** Copyright (c) 2008–2025 SnakeYAML. Apache-2.0 license text is preserved; unmodified library classes are included in the shaded backend JAR.

- **Angular:** Copyright (c) 2010–2026 Google LLC; the installed upstream MIT text is preserved.
- **xterm.js:** The upstream notices credit the xterm.js authors, SourceLair Private Company, and Christopher Jeffrey. The Fit addon has its own MIT notice. Both are preserved separately.
- **RxJS:** Its upstream license includes attribution to Google, Netflix, Microsoft, and contributors; that complete file is preserved.
- **tslib:** Microsoft Corporation's 0BSD copyright and permission notice is preserved.
- **Gson / Error Prone annotations:** Copyright Google LLC / The Error Prone Authors; the upstream Apache license texts are preserved separately. The annotations are actually included in the shaded backend JAR, rather than being only a test tool.
- **GSAP:** The original 3.15.0 notice credits GreenSock, identifies Jack Doyle, and links the Standard license. [GSAP-NOTICE.txt](licenses/GSAP-NOTICE.txt) preserves that notice. The [license snapshot](licenses/GSAP-Standard-License.txt) records its source and retrieval date. GSAP is not relicensed under MIT or Apache; its custom terms include restrictions concerning competing visual animation builders. Copyright headers remain in the generated frontend and native startup bundles.
- **Devicon:** Copyright (c) 2015 konpa. Twelve unmodified SVG assets identify fourteen probes: Python also identifies pip, and Java also identifies javac. The license ships both in `Resources/licenses/Devicon-MIT.txt` and beside the web assets. [sources.json](frontend/src/assets/tool-logos/sources.json) records the versioned source URLs and SHA-256 checksums. Brand marks do not imply endorsement and are not recolored.

### Java runtime provenance

The JDK selected by the package build supplies the `jlink` runtime. The current local build uses OpenJDK 25.0.1+8-27, whose installed legal files identify GPLv2, the Classpath Exception, and additional third-party module notices. The complete `Resources/jre/legal/` tree is retained, including `LICENSE`, `ADDITIONAL_LICENSE_INFO`, `ASSEMBLY_EXCEPTION`, and applicable module notices. Do not replace these with a single generic GPL file.

Packaging also preserves the selected JDK's original `release` metadata as `Resources/licenses/java-build-release.txt`; the reduced runtime has its own `Resources/jre/release` module list. Java version/vendor/source provenance belongs to each build, rather than a fixed claim about every future package. Public binary releases must identify the matching JDK distribution and corresponding source alongside its redistribution terms; this repository does not contain the JDK source tree.

## Optional companion downloaded on demand

**CloudBeaver Community 26.2.2** provides the embedded database navigator, table grid, SQL editor, and transaction controls. Runtime Bay downloads the official image:

```text
dbeaver/cloudbeaver:26.2.2@sha256:7298c59289e9519d8913083c85f3ceb475aa12db0f58e87b6491445a9b298d2c
```

CloudBeaver Community uses [Apache-2.0](https://github.com/dbeaver/cloudbeaver/blob/devel/LICENSE). Its image, JDBC drivers, and transitive dependencies retain their own licenses and notices; the entire image is not being described as a single Apache-licensed artifact. Upstream branding is retained. Runtime Bay does not bundle that image or copy CloudBeaver application source into its app. See [database integration](docs/databases.md).

## User-installed and system components

| Component | Relationship to Runtime Bay | License reference |
| --- | --- | --- |
| Docker CLI | Invoked for Docker operations; not bundled | [Docker CLI Apache-2.0](https://github.com/docker/cli/blob/master/LICENSE) |
| Docker engine / Docker Desktop / alternative engine | Supplied and selected by the user; not bundled | The chosen distribution's own licenses and terms; Docker Desktop is not described as an MIT/Apache dependency |
| OpenSSH (`/usr/bin/ssh`) | System client used for machine connections and Docker SSH contexts; not bundled | [OpenSSH upstream license notices](https://www.openssh.com/txt/LICENCE), plus the system distribution's notices |
| AppKit, WebKit/WKWebView, Security, LocalAuthentication, zsh, lsof, and system libraries | Provided by macOS | System/vendor notices; Runtime Bay does not copy their source into the repository |
| Python, Java/javac, Node/npm, AWS CLI, Git, Docker, Maven, Gradle, pnpm, Yarn, Go | Discovered on a host or used by the user's commands | Their respective distributions; inspection does not install or bundle these tools |

Runtime Bay's native SSH password and Keychain helpers and process/PTY supervisors are original project code. No `sshpass`, third-party SSH password utility, or remote agent is bundled. An isolated, standard-library-only Go worker prototype lives under `runtime/` and is not bundled in the app. Its development toolchain is Go 1.26.1. The Go BSD-3-Clause [license](licenses/Go-BSD-3-Clause.txt) and [patent grant](licenses/Go-PATENTS.txt) are preserved from the installed official toolchain. Release builds that include worker binaries must record their exact compiler, dependencies, target and source/binary provenance and preserve all applicable Go notices before distribution.

## Development and test tooling

These direct build/test tools are not shipped as standalone tools in the app. Their transitive dependency trees remain recorded by the npm lockfile and Maven dependency metadata; this table is not an inventory of every build-time transitive package.

| Tool | Declared version | License / source |
| --- | --- | --- |
| Angular CLI, build tools, compiler CLI, compiler | 21.2.25 | [MIT](https://github.com/angular/angular/blob/main/LICENSE) |
| TypeScript | 5.9.3 | [Apache-2.0](https://github.com/microsoft/TypeScript/blob/main/LICENSE.txt) |
| esbuild | 0.28.1 | [MIT](https://github.com/evanw/esbuild/blob/main/LICENSE.md) |
| Standard Schema spec | 1.1.0 (installed type-only dependency) | [MIT](https://github.com/standard-schema/standard-schema/blob/main/LICENSE) |
| JUnit Jupiter | 5.11.4 | [EPL-2.0](https://github.com/junit-team/junit5/blob/r5.11.4/LICENSE.md) |
| Maven compiler / Surefire / Shade plugins | 3.14.1 / 3.5.2 / 3.6.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| Swift and Clang toolchains | Supplied by the build machine's Apple Command Line Tools | [Swift license](https://github.com/swiftlang/swift/blob/main/LICENSE.txt), [LLVM license](https://github.com/llvm/llvm-project/blob/main/LICENSE.TXT), and installed distribution notices |

## Inspiration and planned integrations

Portainer informed the container management workflow; its source, agents, and product are not bundled. T3 Code, Codex, Claude, Bruno, and Postman remain integration candidates. No license attribution here should imply that those applications are currently embedded or redistributed.

## Where notices ship

`npm run package:mac` includes:

- `Resources/LICENSE`: Runtime Bay's MIT license.
- `Resources/THIRD_PARTY_NOTICES.md`: this inventory.
- `Resources/licenses/`: preserved upstream license texts, the GSAP notice and license snapshot, the shared Apache text, and Java build metadata.
- `Resources/3rdpartylicenses.txt`: Angular's generated license extraction for the actual frontend bundles, including lazy terminal chunks.
- `Resources/web/assets/tool-logos/`: original SVGs, Devicon's license, and logo provenance.
- `Resources/jre/legal/`: module-specific notices from the selected Java runtime.

The inventory and generated notices complement each other: package extraction does not cover Java dependencies, images downloaded later, or artwork by itself. Runtime Bay's source license does not replace any of those upstream terms.
