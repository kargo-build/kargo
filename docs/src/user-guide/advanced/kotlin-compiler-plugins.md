---
description: This page describes how to add Kotlin compiler plugins to your module.
---
# Kotlin compiler plugins

Compiler plugins are a powerful feature of Kotlin that allow extending the language with new features.

## Built-in compiler plugins

JetBrains provides a handful of first-party compiler plugins, and some of them are supported in Amper as first-class
citizens. Check the sections below for more information about each plugin.

### All-open

The [All-open](https://kotlinlang.org/docs/all-open-plugin.html) compiler plugin allows you to mark entire groups of
classes as `open` automatically, without having to mark each class with the `open` keyword in your sources.

To enable All-open, add the following configuration to your module file:

```yaml
settings:
  kotlin:
    allOpen:
      enabled: true
      annotations: # (1)!
        - org.springframework.context.annotation.Configuration
        - org.springframework.stereotype.Service
        - org.springframework.stereotype.Component
        - org.springframework.stereotype.Controller
        - ...
```

1.   Lists the annotations that mark classes as open.

Or you can use one of the preconfigured presets that contain all-open annotations related to specific frameworks:

```yaml
settings:
  kotlin:
    allOpen:
      enabled: true
      presets:
        - spring
        - micronaut
```

!!! success "Already covered by the [Spring Boot support](../builtin-tech/spring.md)"

    The All-open plugin is invaluable for Spring projects, because Spring needs to create proxy classes that extend
    the original classes. This is why using `springBoot: enabled` automatically enables the All-open plugin with the
    Spring preset.
 

### No-arg

The [No-arg](https://kotlinlang.org/docs/no-arg-plugin.html) compiler plugin automatically generates a no-arg 
constructor for all classes marked with the configured annotations.

To enable [No-arg](https://kotlinlang.org/docs/no-arg-plugin.html), add the following configuration:

```yaml
settings:
  kotlin:
    noArg:
      enabled: true
      annotations: 
        - jakarta.persistence.Entity
        - ...
```

Or you can use one of the preconfigured presets that contain no-arg annotations related to specific frameworks:

```yaml
settings:
  kotlin:
    noArg:
      enabled: true
      presets: 
        - jpa
```

### Power Assert

The [Power Assert](https://kotlinlang.org/docs/power-assert.html) compiler plugin enhances the output of failed 
assertions with additional information about the values of variables and expressions:

```
Incorrect length
assert(hello.length == world.substring(1, 4).length) { "Incorrect length" }
       |     |      |  |     |               |
       |     |      |  |     |               3
       |     |      |  |     orl
       |     |      |  world!
       |     |      false
       |     5
       Hello
```

To enable Power Assert, add the following configuration:

```yaml
settings:
  kotlin:
    powerAssert: enabled
```

By default, Power Assert is enabled for `kotlin.assert` function. You can enable it for other functions as well:

```yaml
settings:
  kotlin:
    powerAssert:
      enabled: true
      functions: [ kotlin.test.assertTrue, kotlin.test.assertEquals, kotlin.test.assertNull ]
```

### Compose

The Compose compiler plugin is covered in the mode general
[Compose Multiplatform](../builtin-tech/compose-multiplatform.md) section.

### Kotlinx Serialization

The Kotlinx Serialization compiler plugin is covered in the more general 
[Kotlinx Serialization](../builtin-tech/kotlinx-serialization.md) section.

### Kotlinx RPC

The Kotlinx RPC compiler plugin is covered in the more general
[Kotlinx RPC](../builtin-tech/kotlinx-rpc.md) section.

### Parcelize

The Parcelize compiler plugin is covered in the [Android](../product-types/android-app.md) section.

### Lombok

The Lombok compiler plugin is covered in the more general [Lombok](../builtin-tech/lombok.md) section.

## Third-party compiler plugins

Third-party compiler plugins are Kotlin compiler plugins published by community members.

!!! warning "The IDE support for third-party compiler plugins is best-effort at the moment, see the [Limited IDE support](#limited-ide-support) section below."

### Syntax

To use a third-party compiler plugin, add the following configuration to your module file:

```yaml
settings:
  kotlin:
    compilerPlugins:
      - id: org.example.my.plugin
        dependency: org.example:my-plugin:1.0.0
        options:
          myKey1: myValue1
          myKey2: myValue2
```

Where:

| Field        | Description                                                                                                                                                                                                                                                                                                                             |
|:-------------|:----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `id`         | The ID of this compiler plugin, used to pass options. It is defined by the `pluginId` property in the `CommandLineProcessor` implementation of the plugin. If the plugin is also implemented as a Gradle plugin, its ID can also be found in `getCompilerPluginId()` in the corresponding `KotlinCompilerPluginSupportPlugin` subclass. |
| `dependency` | The compiler plugin dependency, in the form of `groupId:artifactId:version` Maven coordinates, or a catalog reference.                                                                                                                                                                                                                  |
| `options`    | The options to pass to this compiler plugin, as a key-value map.                                                                                                                                                                                                                                                                        |

??? question "Why do I have to find the plugin ID myself?"

    This is not really meant to be the final way to configure compiler plugins for end users.

    Ideally, compiler plugin authors would write an Amper plugin that wraps their compiler plugin, so they can do this 
    configuration for you (and also provide typed options in their plugin settings).

    At the moment, they can't publish plugins because Amper doesn't support plugin publication yet, so this low-level
    API is the only way you, as an end user, can configure compiler plugins.

### Examples

#### Koin

Here is how you could configure the [Koin](https://insert-koin.io/) compiler plugin:

```yaml
settings:
  kotlin:
    compilerPlugins:
      # The compiler plugin ID is found in the CommandLineInterface implementation of the compiler plugin, but this is
      # actually coming from the build file in this case:
      # https://github.com/InsertKoinIO/koin-compiler-plugin/blob/75d838fd3ddfabfe34170418573a08fb8766cab8/koin-compiler-plugin/build.gradle.kts#L55
      - id: io.insert-koin.compiler.plugin
        dependency: io.insert-koin:koin-compiler-plugin:0.3.0
```

#### Metro

Here is how you could configure the [Metro](https://zacsweers.github.io/metro) compiler plugin:

```yaml
settings:
  kotlin:
    compilerPlugins:
      # The compiler plugin ID is found in the CommandLineInterface implementation of the compiler plugin:
      # https://github.com/ZacSweers/metro/blob/b927d128fa57becc83b5ce13621255b96aca12ad/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/MetroCommandLineProcessor.kt#L12
      - id: dev.zacsweers.metro.compiler
        dependency: dev.zacsweers.metro:compiler:0.11.4
        options: #(1)!
          enabled: true
          debug: false
```

1. More options can be found in the
   [MetroOption's source code](https://github.com/ZacSweers/metro/blob/b927d128fa57becc83b5ce13621255b96aca12ad/compiler/src/main/kotlin/dev/zacsweers/metro/compiler/MetroOptions.kt#L75).

### Limited IDE support

Some compiler plugins generate diagnotics that you want to see in the IDE, or code declarations that you want to use 
from your own code. This requires the IDE's embedded compiler to know about the plugin.

Because the Kotlin compiler plugins API is very unstable right now, there is a high chance that the IDE's embedded 
compiler is not compatible with the compiler plugins you want to use. This is why we recommend using the 
[Kotlin Extended FIR Support (KEFS)](https://github.com/Mr3zee/Kotlin-External-FIR-Support) IDE plugin.

[Install the KEFS IDE plugin](https://plugins.jetbrains.com/plugin/26480-kotlin-external-fir-support){ .md-button .md-button--primary }

This IDE plugin allows you to teach the IDE how to find the correct version of the compiler plugin for each compiler 
version, and thus find the one it needs to use instead of the one used by the CLI (e.g. `./amper build`).
You can learn how to configure this plugin in the 
[KEFS user guide](https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/GUIDE.md).

!!! warning "Important"

    This requires that the compiler plugin you want to use follows the guidelines described in the 
    [KEFS guide for plugin authors](https://github.com/Mr3zee/kotlin-external-fir-support/blob/main/PLUGIN_AUTHORS.md).