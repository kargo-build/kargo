import os
import sys
import zipfile
import shutil
import re
from pathlib import Path

PLUGIN_NAME = "kargo-intellij-plugin"
VERSION = "1.0-SNAPSHOT"

# Jars que sabemos que a IntelliJ Platform fornece e não devemos incluir no plugin
# para evitar conflitos de classloader.
IDEA_PROVIDED_JAR_PATTERNS = [
    r"intellij-.*",
    r"platform-.*",
    r"analysis-.*",
    r"andel-.*",
    r"backend-workspace-.*",
    r"bifurcan-.*",
    r"util-.*",
    r"util\.jar$",
    r"core-ui-.*",
    r"ide-impl-.*",
    r"error_prone_annotations-.*",
    r"annotations-.*",
    r"gson-.*",
    r"guava-.*",
    r"kotlin-stdlib.*",
    r"kotlin-reflect.*",
    r"kotlinx-coroutines.*",
    r"kotlinx-serialization-.*",
    r"kotlinx-collections-.*",
    r"kotlinx-datetime-.*",
    r"kotlinx-io-.*",
    r"jna.*",
    r"slf4j-.*",
    r"log4j-.*",
    r"kernel-.*",
    r"diagnostic-.*",
    r"workspace-.*",
    r"shared-.*",
    r"jspell-.*",
    r"asm-.*",
    r"objenesis-.*",
    r"jaxb-.*",
    r"stax2?-.*",
    r"jakarta-.*",
    r"jakarta\..*",
    r"javax-.*",
    r"javax\..*",
    r"rd-.*",
    r"pureconfig-.*",
    r"compose-.*",
    r"skiko-.*",
    r"jps-.*",
    r"forms-.*",
    r"bootstrap\.jar$",
    r"resources\.jar$",
    r"external-system-.*",
    r"service-messages-.*",
    r"serviceMessages-.*",
    r"netty-.*",
    r"protobuf-.*",
    r"jackson-.*",
    r"ktor-.*",
    r"yaml-psi.*",
    r"toml-psi.*",
    r"httpclient-.*",
    r"httpcore-.*",
    r"httpmime-.*",
    r"commons-.*",
    r"caffeine-.*",
    r"byte-buddy-.*",
    r"jdom-.*",
    r"opentelemetry-.*",
    r"snakeyaml-.*",
    r"icu4j-.*",
    r"jna-.*",
    r"jbr-api-.*",
    r"jspecify-.*",
    r"jsr305-.*",
    r"failureaccess-.*",
    r"aopalliance-.*",
    r"async-profiler-.*",
    r"automaton-.*",
    r"bcprov-.*",
    r"bcpkix-.*",
    r"config-.*",
    r"gradle-tooling-api-.*",
    r"guice-.*",
    r"h2-.*",
    r"jimfs-.*",
    r"jdom-.*",
    r"fastutil-.*",
    r"trove4j-.*",
    r"ktor-.*",
    r"maven-.*",
    r"plexus-.*",
    r"sisu-.*",
    r"ddmlib-.*",
    r"sdklib-.*",
    r"dvlib-.*",
    r"layoutlib-api-.*",
    r"common-31\..*",
    r"repository-31\..*",
    r"sdk-common-31\..*",
    r"protos-31\..*",
    r"aapt2-proto-.*",
    r"kxml2-.*",
    r"javax\.activation-.*",
    r"jakarta\.xml\.bind-.*",
    r"istack-commons-.*",
    r"txw2-.*",
    r"FastInfoset-.*",
    r"aalto-xml-.*",
    r"stax2-api-.*",
    r"amper-cli.*",
    r"amper-distribution.*",
    r"amper-wrapper.*",
    r"amper-cli-test.*",
    r"amper-mobile-test.*",
    r"amper-wrapper-test.*",
    r"amper-deps-proprietary-xcode.*",
]

# Padrão para versões da IntelliJ Platform (ex: -252.25557.178.jar ou -253.29346.138-hash.jar)
PLATFORM_VERSION_PATTERN = re.compile(r"-\d{3}\.\d+\.\d+")

def normalize_name(name: str) -> str:
    """
    Normaliza o nome para fins de agrupamento e detecção de duplicatas.
    Remove hífens, extensões e sufixos 'jvm'.
    Não remove versões aqui para sermos mais conservadores.
    """
    name = name.lower()
    if name.endswith(".jar"):
        name = name[:-4]
    name = name.replace("-", "").replace("_", "")
    if name.endswith("jvm"):
        name = name[:-3]
    return name

def is_idea_provided(jar_name: str) -> bool:
    # Se contém a versão da plataforma, quase certamente é provido
    if PLATFORM_VERSION_PATTERN.search(jar_name):
        return True
    for pattern in IDEA_PROVIDED_JAR_PATTERNS:
        if re.search(pattern, jar_name): 
            return True
    return False

def create_jar(jar_path: Path, classes_dirs: list[Path], resources_dirs: list[Path]):
    """Cria um JAR a partir de uma ou mais pastas de classes e resources."""
    with zipfile.ZipFile(jar_path, "w", zipfile.ZIP_DEFLATED) as jar:
        for classes_dir in classes_dirs:
            if classes_dir.exists():
                for f in sorted(classes_dir.rglob("*")):
                    if f.is_file():
                        jar.write(f, f.relative_to(classes_dir))
        for res_dir in resources_dirs:
            if res_dir.exists():
                for f in sorted(res_dir.rglob("*")):
                    if f.is_file():
                        jar.write(f, f.relative_to(res_dir))

def main():
    repo_root = Path(__file__).parent.parent.parent.absolute()
    plugin_src = repo_root / PLUGIN_NAME
    artifacts_root = repo_root / "build" / "artifacts" / "CompiledJvmArtifact"

    # Encontra a distribuição do Amper para pegar as libs externas
    amper_cache = Path.home() / ".cache" / "JetBrains" / "Amper"
    if not amper_cache.exists():
        amper_cache = Path.home() / "Library" / "Caches" / "JetBrains" / "Amper"
    
    amper_dist = None
    if amper_cache.exists():
        dists = sorted(amper_cache.glob("amper-cli-*"), reverse=True)
        if dists:
            amper_dist = dists[0]

    if not amper_dist:
        print("WARNING: Distribuição do Amper não encontrada. Libs externas podem faltar.")
    else:
        print(f"Usando libs externas de: {amper_dist}")

    # Output do plugin principal
    plugin_artifact = artifacts_root / f"{PLUGIN_NAME}jvm"
    plugin_classes = plugin_artifact / "kotlin-output"
    plugin_resources = plugin_src / "src" / "main" / "resources"

    if not plugin_classes.exists():
        print(f"ERROR: classes não encontradas em {plugin_classes}", file=sys.stderr)
        print("Execute: ./amper task :kargo-intellij-plugin:compileJvm", file=sys.stderr)
        return 1

    out_dir = plugin_src / "build"
    out_dir.mkdir(exist_ok=True)
    zip_path = out_dir / f"{PLUGIN_NAME}-{VERSION}.zip"

    tmp = out_dir / "_tmp_package"
    if tmp.exists():
        shutil.rmtree(tmp)

    lib_dir = tmp / PLUGIN_NAME / "lib"
    lib_dir.mkdir(parents=True)

    # 1. JAR do plugin principal
    plugin_jar = lib_dir / f"{PLUGIN_NAME}.jar"
    print(f"→ {plugin_jar.name} (BASED ON SOURCE)")
    create_jar(plugin_jar, [plugin_classes], [plugin_resources])

    # 2. JARs de todos os módulos Kargo compilados que o plugin possa precisar
    bundled_normalized_names = {normalize_name(PLUGIN_NAME)}
    
    for module_dir in sorted(artifacts_root.iterdir()):
        if not module_dir.is_dir() or module_dir.name.endswith("Test"):
            continue
        
        if module_dir.name == f"{PLUGIN_NAME}jvm":
            continue

        # Nome do módulo (ex: amper-problem-reportingjvm)
        module_name = module_dir.name
        jar_name = f"{module_name}.jar"

        # Exclui se for um módulo "fat" ou provido/conflituoso
        if is_idea_provided(module_name) or is_idea_provided(jar_name):
            continue
        
        classes_dirs = [module_dir / "kotlin-output", module_dir / "java-output"]
        res_dirs = [module_dir / "resources-output"]

        if any(d.exists() for d in classes_dirs):
            print(f"→ {jar_name} (BASED ON SOURCE)")
            create_jar(lib_dir / jar_name, classes_dirs, res_dirs)
            bundled_normalized_names.add(normalize_name(module_name))

    # 3. JARs externos da distribuição do Amper (Ktor, Clikt, etc.)
    if amper_dist is not None:
        amper_libs = amper_dist / "lib"
        if amper_libs.exists():
            for jar in sorted(amper_libs.glob("*.jar")):
                jar_name = jar.name
                
                # Exclui se for provido pelo IDEA
                if is_idea_provided(jar_name):
                    # print(f"  (skipping idea-provided: {jar_name})")
                    continue
                
                # Exclui se já incluímos uma versão baseada no source desse módulo
                if normalize_name(jar_name) in bundled_normalized_names:
                    # print(f"  (skipping source-covered: {jar_name})")
                    continue
                
                print(f"→ {jar_name} (FROM DIST)")
                shutil.copy(jar, lib_dir / jar_name)

    # 4. ZIP final
    print(f"\nCriando ZIP: {zip_path.name}")
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        # Loop recursivo mas respeitando a pasta raiz do plugin no zip
        for f in sorted(tmp.rglob("*")):
            if f.is_file():
                zf.write(f, f.relative_to(tmp))

    shutil.rmtree(tmp)
    print(f"\n✅ Plugin pronto: {zip_path}")
    print("   Instale via: Settings > Plugins > Install Plugin from Disk...")
    return 0

if __name__ == "__main__":
    sys.exit(main())
