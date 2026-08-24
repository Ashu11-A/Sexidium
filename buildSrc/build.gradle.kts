// buildSrc existe por uma razão específica: o configuration cache.
//
// Os helpers de zip/hash em SexidiumBuildUtil.kt já moraram no build.gradle.kts -- primeiro como
// `fun`s de topo, depois como um `object` aninhado no script. As duas formas falham do mesmo jeito:
// tudo que é declarado num .gradle.kts é compilado dentro da classe daquele script, e o Gradle
// recusa serializar QUALQUER referência a uma classe de build script ("cannot serialize Gradle
// script object references"). Não é sobre capturar o Project -- um `object` Kotlin realmente não
// captura receptor externo -- é sobre a classe pertencer ao classloader do script.
//
// O sintoma era o build inteiro falhando com `org.gradle.configuration-cache=true`, que é como
// docker/provision.sh invoca o Gradle. Aqui em buildSrc as mesmas funções viram uma classe normal,
// num classloader normal, que o cache serializa sem reclamar.
//
// `kotlin-dsl` vem embutido no Gradle: não baixa plugin externo, então isto não adiciona rede ao
// caminho de deploy. A compilação deste módulo é cacheada e só refaz quando este arquivo muda.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}
