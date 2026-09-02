// Configuração compartilhada das tarefas `test` que LEEM O CÓDIGO-FONTE em vez de apenas executá-lo
// (GoldenApiSurfaceTest nos dois adapters, PaperHardcoreViewLoginShapeTest, PackFormatConsistencyTest).
//
// Mora em buildSrc pelo mesmo motivo que SexidiumBuildUtil.kt: uma `fun` de topo num .gradle.kts vira
// membro da classe do script, e o configuration cache recusa serializar referência a classe de build
// script ("cannot serialize Gradle script object references"). Aqui é uma classe normal.
//
// Resolve três problemas que esses testes têm e os testes comuns não:
//
//  1. `-Dsexidium.updateGolden=true` na linha de comando define a propriedade no DAEMON do Gradle, não
//     no fork de teste. O teste lia `Boolean.getBoolean` do próprio fork, então o comando de
//     regeneração documentado em três lugares (dois javadocs e a mensagem de falha) NUNCA regenerava
//     nada — verificado: mtime e sha256 do arquivo golden não mudavam. `systemProperty` repassa.
//
//  2. Um teste que lê `src/main/java` não tem esse diretório como INPUT da tarefa. O único input
//     relevante é o classpath compilado, então um `{@link com.sexidium.core.…}` só em javadoc não muda
//     bytecode nenhum, a tarefa fica UP-TO-DATE e a divergência só aparece num commit posterior sem
//     relação. `inputs.dir` fecha isso.
//
//  3. Caminhos relativos dependem do workingDir da tarefa. O default do Gradle é o diretório do
//     projeto, mas este build já sobrescreve `workingDir` para outros tipos de tarefa; `sexidium.moduleDir`
//     torna a dependência explícita em vez de herdada.

import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

/**
 * Aplica a configuração acima a uma tarefa de teste. Chame de dentro de `tasks.test { … }`.
 *
 * Tudo é lido em tempo de CONFIGURAÇÃO e passado como String/File — nenhum lambda guarda `project`,
 * então o configuration cache serializa a tarefa normalmente.
 */
fun Test.configureSourceScanningTests(project: Project) {
    val moduleDir = project.projectDir
    val mainSources = moduleDir.resolve("src/main/java")

    systemProperty("sexidium.moduleDir", moduleDir.absolutePath)
    // `providers.systemProperty` (e não `System.getProperty`) para que o valor conte como input da
    // tarefa: mudar o flag reexecuta o teste em vez de devolver um UP-TO-DATE enganoso.
    systemProperty(
        "sexidium.updateGolden",
        project.providers.systemProperty("sexidium.updateGolden").getOrElse("false")
    )

    if (mainSources.isDirectory) {
        inputs.dir(mainSources)
            .withPathSensitivity(PathSensitivity.RELATIVE)
            .withPropertyName("scannedMainSources")
    }
}
