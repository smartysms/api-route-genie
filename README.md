# API Route Genie

A window that lists the web endpoints of a Python project, takes you to the code, and lets you call them without
leaving the IDE. It works without the paid edition of the IDE and has its own window, independent of the built-in
Endpoints tool window.

Status: early development. The project skeleton, the shared model and the quality gates are in place; endpoint
discovery, the window, the HTTP client and request validation are being built phase by phase.

## Goals

- Finds the routes of FastAPI, Flask and Django (including Django REST Framework) projects, with prefixes and nested
  routers resolved, and keeps the list current while you edit.
- Click an endpoint or press `Ctrl+Alt+Shift+R` to open the window; send requests from it, copy them as cURL.
- Validates request bodies before sending them.
- Fast and leak-free by construction: speed budgets and leak checks are automated tests, see
  [docs/CONVENTIONS.md](docs/CONVENTIONS.md).

## Requirements

- PyCharm 2026.2 (build 262) or newer. The plugin depends only on the bundled Python plugin (`PythonCore`).

## Install and update

Build the zip (`./gradlew buildPlugin`, result in `build/distributions/`) and install it with Settings | Plugins | gear |
Install Plugin from Disk..., then restart the IDE. To update, build again and install the new zip the same way; the IDE
replaces the old version at the next start.

For a development loop, `scripts/install-local.sh` builds and copies the plugin into an IDE's user plugin folder
(`ROUTEGENIE_PLUGINS_DIR=<IDE config dir>/plugins`). Close the IDE first when updating, because a running IDE usually
keeps the plugin jar open on Windows.

### Automatic updates from GitHub

Every push to `main` that changes the plugin builds a new version (`<major.minor>.<run number>`, the base is `version` in
`gradle.properties`) and publishes it as a GitHub release with the zip and an `updatePlugins.xml`. Add that file as a plugin
repository once, and the IDE offers each new version as an ordinary plugin update:

1. Settings | Plugins | gear icon | Manage Plugin Repositories... | `+`
2. Enter `https://github.com/smartysms/api-route-genie/releases/latest/download/updatePlugins.xml`
3. Install the plugin from the Marketplace tab or via Install Plugin from Disk the first time; from then on, updates appear
   under Settings | Plugins | Installed (and in the IDE's update notification). Updating asks for an IDE restart.

The IDE never installs plugin updates silently, so each update needs one click and a restart.

## Development

Gradle wrapper 9.8.2, IntelliJ Platform Gradle Plugin 2.19.0, Kotlin 2.3.21. The platform jars are Java 25 class files, so
Gradle must run on Java 25 (PyCharm's bundled JBR is one: set `JAVA_HOME` to `<PyCharm>/jbr`).

```bash
./gradlew build          # compile, test and package: build/distributions/*.zip
./gradlew test --rerun   # unit and platform tests
./gradlew runIde         # sandbox PyCharm with the plugin installed
```

By default the build downloads PyCharm (`platformVersion` in `gradle.properties`). To build against an IDE you already
have installed, set `platformPath` outside the repository, in `~/.gradle/gradle.properties`:

```properties
platformPath=C:/Program Files/JetBrains/PyCharm
```

### Renaming the product

The product name is defined once, in `gradle.properties` (`productName`, `productShortName`, `productDir`,
`artifactName`, `repoSlug`). Menus, the manifest, notifications and the zip name follow at build time. After changing the
values run `./gradlew syncDocs` to update this README, the changelog and `docs/` as well.

## Legal notes

This is an independent, unofficial project. It is not affiliated with, endorsed by or sponsored by JetBrains. Framework
names are used only to describe what the plugin supports.

## License

[MIT](LICENSE).
