// Quais Minecraft o jar do Paper atende, e o contador de atualizações de cada um.
//
// Mora em buildSrc pelo mesmo motivo que SexidiumBuildUtil.kt e SourceScanningTests.kt: uma classe
// declarada num .gradle.kts pertence ao classloader daquele script, e o configuration cache recusa
// serializar referência a ela ("cannot serialize Gradle script object references"). Aqui é uma classe
// normal, e os `MinecraftTarget` que ela devolve podem atravessar para dentro de uma task action.
//
// Puro: recebe TEXTO, não um File nem um Project. Quem chama lê o arquivo com
// `providers.fileContents(...)`, que é o caminho que o configuration cache entende como input.

/**
 * Uma versão do Minecraft atendida pelo artefato do Paper.
 *
 * @param version    a versão do Minecraft, como aparece no nome do arquivo ("26.1.2")
 * @param build      o contador de atualizações DAQUELA versão -- o `+N`
 * @param isFloor    true para a primeira da lista: a versão contra a qual se compila e a que vai
 *                   no `api-version` do plugin.yml. É ela que dá compatibilidade para cima.
 */
data class MinecraftTarget(
    val version: String,
    val build: Int,
    val isFloor: Boolean,
) {
    /** `sexidium-paper-26.2+15` — sem extensão, para o Gradle montar `archiveFileName`. */
    fun artifactName(baseName: String): String = "$baseName-$version+$build"

    /** `sexidium-paper-26.2+15.jar`. */
    fun jarFileName(baseName: String): String = "${artifactName(baseName)}.jar"

    /** `26.1` a partir de `26.1.2` — a granularidade que o `api-version` do Paper usa. */
    val apiVersion: String
        get() = version.split(".").take(2).joinToString(".")
}

/**
 * Lê `minecraft-targets.properties`. A ORDEM da lista `supported` é preservada, porque a primeira
 * entrada é o piso e isso decide contra o que se compila.
 *
 * @throws IllegalStateException com uma mensagem que diz o que consertar, e não só o que faltou --
 *   este arquivo é editado à mão a cada atualização, então errar nele é normal e a mensagem é a
 *   documentação que a pessoa vai ler primeiro.
 */
fun parseMinecraftTargets(text: String): List<MinecraftTarget> {
    val properties = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
        .associate { line ->
            val index = line.indexOf('=')
            line.substring(0, index).trim() to line.substring(index + 1).trim()
        }

    val supported = properties["sexidium.minecraft.supported"]
        ?: error(
            "minecraft-targets.properties: falta `sexidium.minecraft.supported`. " +
                "É a lista, em ordem crescente, das versões que este jar atende -- a primeira é o piso."
        )

    val versions = supported.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    check(versions.isNotEmpty()) {
        "minecraft-targets.properties: `sexidium.minecraft.supported` está vazia; " +
            "o jar tem de atender pelo menos uma versão."
    }
    check(versions.size == versions.toSet().size) {
        "minecraft-targets.properties: `sexidium.minecraft.supported` repete uma versão ($supported); " +
            "dois artefatos com o mesmo nome sobrescreveriam um ao outro."
    }

    return versions.mapIndexed { index, version ->
        val key = "sexidium.minecraft.$version.build"
        val raw = properties[key]
            ?: error(
                "minecraft-targets.properties: falta `$key`. Toda versão em `supported` precisa do " +
                    "SEU contador de atualizações -- uma versão nova entra com `$key=1`."
            )
        val build = raw.toIntOrNull()
            ?: error("minecraft-targets.properties: `$key=$raw` não é um inteiro.")
        check(build >= 1) {
            "minecraft-targets.properties: `$key=$build`; o contador começa em 1 " +
                "(é uma contagem de atualizações, não um índice)."
        }
        MinecraftTarget(version = version, build = build, isFloor = index == 0)
    }
}
