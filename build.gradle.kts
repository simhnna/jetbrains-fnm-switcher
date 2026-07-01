plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.17.0"
}

group = "eu.hannaweb"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Use WebStorm as the base IDE (includes JavaScript plugin)
        webstorm("2024.2")

        // Needed to compile against bundled plugin APIs
        bundledPlugin("Git4Idea")
        bundledPlugin("JavaScript")

        // Tooling
        pluginVerifier()
    }

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        id = "eu.hannaweb.fnm"
        name = "FNM Version Switcher"
        version = project.version.toString()
        description = """
            Automatically switches the project Node.js interpreter using fnm, based on
            <code>.node-version</code> and <code>.nvmrc</code> files.
            <ul>
              <li>Auto-switches on project open, Git branch change, and version file edits</li>
              <li>Installs missing Node versions via fnm (opt-out)</li>
              <li>Status bar widget for quick manual switching — great for multi-module projects</li>
            </ul>
            Requires fnm to be installed. Supports WebStorm and IntelliJ IDEA Ultimate.
        """.trimIndent()

        changeNotes = """
            <b>1.0.0</b> — Initial release.
            <ul>
              <li>Auto-switches the project Node.js interpreter on project open, Git branch change, and <code>.node-version</code> / <code>.nvmrc</code> edits.</li>
              <li>Installs missing Node versions via <code>fnm install</code> (auto, or with a prompt).</li>
              <li>Status bar widget showing the active version, with a picker for manual switching.</li>
              <li>Per-project settings under <b>Settings → Tools → FNM Version Switcher</b>.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "242"
            // No untilBuild: stay compatible with future IDE builds without re-releasing.
            untilBuild = provider { null }
        }

        vendor {
            name = "Simon Hanna"
            email = "jetbrains@hannaweb.eu"
            url = "https://github.com/simhnna/jetbrains-fnm-switcher"
        }
    }

    // Run the JetBrains Plugin Verifier against the recommended IDE builds.
    pluginVerification {
        ides {
            recommended()
        }
    }

    // Signing is optional but recommended by JetBrains. Provide the key material
    // via env vars (e.g. as CI secrets); when absent, the signing task is skipped.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    // Marketplace upload token, created at
    // https://plugins.jetbrains.com/author/me/tokens and exposed as PUBLISH_TOKEN.
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}
