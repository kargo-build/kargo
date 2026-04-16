/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

import utils.fetchContent

/*
 * Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

object AzulApi {

    // archive types should match what we do in Amper wrappers
    enum class Os(val azulApiName: String, val filenameValue: String, val archiveType: String) {
        Windows(azulApiName = "windows", filenameValue = "win", archiveType = "zip"),
        Linux(azulApiName = "linux-glibc", filenameValue = "linux", archiveType = "tar.gz"),
        MacOs(azulApiName = "osx", filenameValue = "macosx", archiveType = "tar.gz"),
    }

    enum class Arch(val azulApiName: String, val filenameValue: String) {
        Aarch64(azulApiName = "aarch64", filenameValue = "aarch64"),
        X64(azulApiName = "x64", filenameValue = "x64"),
    }

    data class Jre(val os: Os, val arch: Arch, val sha256: String)

    fun getZuluJreChecksums(zuluVersion: String, javaVersion: String): List<Jre> = Os.entries.flatMap { os ->
        Arch.entries.map { arch ->
            Jre(
                os = os,
                arch = arch,
                sha256 = fetchJreChecksum(zuluVersion, javaVersion, os, arch),
            )
        }
    }

    private fun fetchJreChecksum(zuluVersion: String, javaVersion: String, os: Os, arch: Arch): String {
        val uuid = fetchJreUuid(zuluVersion, javaVersion, os, arch)
        val packageMetadataUrl = "https://api.azul.com/metadata/v1/zulu/packages/$uuid"
        val downloadUrl = fetchJsonField(url = packageMetadataUrl, fieldName = "download_url")
        val expectedDownloadUrl = downloadUrlFor(zuluVersion, javaVersion, os, arch)
        check(downloadUrl == expectedDownloadUrl) {
            "Incorrect package found, download URL is not as expected:\nExpected: $expectedDownloadUrl\nBut was:  $downloadUrl"
        }
        return fetchJsonField(
            url = packageMetadataUrl,
            fieldName = "sha256_hash",
        )
    }

    private fun downloadUrlFor(zuluVersion: String, javaVersion: String, os: Os, arch: Arch): String =
        "https://cdn.azul.com/zulu/bin/zulu$zuluVersion-ca-jre$javaVersion-${os.filenameValue}_${arch.filenameValue}.${os.archiveType}"

    private fun fetchJreUuid(zuluVersion: String, javaVersion: String, os: Os, arch: Arch): String = fetchJsonField(
        url = "https://api.azul.com/metadata/v1/zulu/packages/?" +
                "java_version=$javaVersion" +
                "&distro_version=$zuluVersion" +
                "&os=${os.azulApiName}" +
                "&arch=${arch.azulApiName}" +
                "&archive_type=${os.archiveType}" +
                "&java_package_type=jre" +
                "&javafx_bundled=false" +
                "&crac_supported=false" +
                "&latest=true" +
                "&release_status=ga" +
                "&page=1" +
                "&page_size=1",
        fieldName = "package_uuid",
    )

    private fun fetchJsonField(url: String, fieldName: String): String {
        val json = fetchContent(url)
        val matches = Regex(""""$fieldName"\s*:\s*"(?<value>[^"]+)"""").findAll(json).toList()
        check(matches.isNotEmpty()) { "Could not find $fieldName in the contents of $url:\n$json" }
        check(matches.size == 1) { "$fieldName was found multiple times in the contents of $url:\n$json" }
        val match = matches.single()
        return match.groups["value"]?.value
            ?: error("$fieldName was matched by the regex but cannot be extracted. Groups: ${match.groups}")
    }
}