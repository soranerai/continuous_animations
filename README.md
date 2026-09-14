# Continuous Animations

An ExteraGram plugin that keeps animated emoji statuses and video avatars looping.

## Build

On Debian/Ubuntu install Java and DX, then build the distributable plugin:

```bash
sudo apt update
sudo apt install -y default-jdk dalvik-exchange make
make verify
```

The result is `dist/continuous_animations.plugin`. The GitHub Actions workflow performs the same verification and exposes this file as a build artifact.

## Project layout

- `src/main/java` — plugin Java code loaded from DEX.
- `src/compileStubs/java` — compile-only declarations for ExteraGram/Telegram APIs. They are not packaged.
- `plugin/continuous_animations.plugin.template` — Python loader and plugin metadata.
- `tools/package_plugin.py` — embeds the built DEX into the template.

## Implementation notes

Hooks run after Telegram binds an `ImageReceiver`. Every interaction with Telegram internals is isolated behind reflection and a `Throwable` boundary; a changed private field or an incomplete recycled receiver therefore disables only that animation attempt instead of crashing Telegram.

The Java DEX entry point is `app.soranerai.continuousanimations.Main`. This project intentionally compiles against small API stubs, because ExteraGram supplies the real classes at runtime.
