#!/usr/bin/env bats
#
# Unit tests for sexidium::paper_jar_name (scripts/lib/sexidium.sh) -- the single
# derivation point of the canonical Paper artifact name
# (sexidium-paper-<floor>+<counter>.jar, from minecraft-targets.properties).
#
# What is worth testing is not "does awk run". It is that this helper fails WHERE the
# cause is: it decides where the provisioner looks for the jar, what the store stages
# and what every node pins, so a malformed properties file must abort HERE with a
# message naming the broken key -- not three calls later as "jar not found" against a
# path ending in a slash. The file is hand-edited on every Minecraft update; wrong
# edits are the normal case, and the message IS the documentation.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    set -u
    . "$SCRIPTS_DIR/lib/core.sh"
    sx::require sexidium
    TMP="$BATS_TEST_TMPDIR/work"
    mkdir -p "$TMP"
    ROOT_DIR="$TMP"
}

props() {
    printf '%s\n' "$@" >"$ROOT_DIR/minecraft-targets.properties"
}

# --- happy paths ----------------------------------------------------------------

@test "derives floor+counter from a well-formed properties file" {
    props \
        "sexidium.minecraft.supported=26.1.2,26.2" \
        "sexidium.minecraft.26.1.2.build=16" \
        "sexidium.minecraft.26.2.build=15"
    [ "$(sexidium::paper_jar_name)" = "sexidium-paper-26.1.2+16.jar" ]
}

@test "the FIRST supported version is the floor, whatever follows" {
    # A ordem é o que decide contra o que se compila (ver minecraft-targets.properties);
    # trocar as linhas sem trocar os contadores tem de mudar o NOME derivado junto.
    props \
        "sexidium.minecraft.supported=26.2,26.1.2" \
        "sexidium.minecraft.26.1.2.build=16" \
        "sexidium.minecraft.26.2.build=7"
    [ "$(sexidium::paper_jar_name)" = "sexidium-paper-26.2+7.jar" ]
}

@test "a single supported version still derives" {
    props \
        "sexidium.minecraft.supported=26.1.2" \
        "sexidium.minecraft.26.1.2.build=3"
    [ "$(sexidium::paper_jar_name)" = "sexidium-paper-26.1.2+3.jar" ]
}

@test "comments and blank lines are ignored; stray spaces are tolerated" {
    # O arquivo é editado à mão a cada atualização -- comentário acidentalmente dentro
    # da linha de chave não existe, mas espaço sobrando sim.
    {
        echo "# cabeçalho"
        echo ""
        echo "  sexidium.minecraft.supported=26.1.2 , 26.2  "
        echo "# nota"
        echo "sexidium.minecraft.26.1.2.build= 12 "
        echo "sexidium.minecraft.26.2.build=9"
    } >"$ROOT_DIR/minecraft-targets.properties"
    [ "$(sexidium::paper_jar_name)" = "sexidium-paper-26.1.2+12.jar" ]
}

# --- failures name the fix --------------------------------------------------------

@test "a missing properties file dies saying so" {
    run sexidium::paper_jar_name
    [ "$status" -ne 0 ]
    [[ "$output" == *"minecraft-targets.properties"* ]]
}

@test "a missing 'supported' key dies naming the key" {
    props "sexidium.minecraft.26.1.2.build=16"
    run sexidium::paper_jar_name
    [ "$status" -ne 0 ]
    [[ "$output" == *"sexidium.minecraft.supported"* ]]
}

@test "an unsupported floor shape dies with an example" {
    props \
        "sexidium.minecraft.supported=banana,26.2" \
        "sexidium.minecraft.26.2.build=15"
    run sexidium::paper_jar_name
    [ "$status" -ne 0 ]
    [[ "$output" == *"banana"* ]]
}

@test "an alias version without a counter still derives -- that check belongs to Gradle" {
    # Divisão de trabalho deliberada: o shell precisa só do NOME do piso (é o que a rede
    # instala e pina); os contadores das ALIASES são exigidos por parseMinecraftTargets
    # (buildSrc), e qualquer ./gradlew falha neles ANTES de existir jar para procurar.
    # Duplicar aqui seria uma segunda mensagem para a mesma falta.
    props \
        "sexidium.minecraft.supported=26.1.2,26.2" \
        "sexidium.minecraft.26.1.2.build=16"
    [ "$(sexidium::paper_jar_name)" = "sexidium-paper-26.1.2+16.jar" ]
}

@test "a zero or negative counter dies (it counts updates, not indexes)" {
    props \
        "sexidium.minecraft.supported=26.1.2" \
        "sexidium.minecraft.26.1.2.build=0"
    run sexidium::paper_jar_name
    [ "$status" -ne 0 ]
    [[ "$output" == *"sexidium.minecraft.26.1.2.build"* ]]
}
