#!/usr/bin/env bats
#
# Unit tests for the update pipeline's PURE logic (scripts/remote/util.py).
#
# Three groups, and the middle one is the reason this file exists:
#
#   * port arithmetic, cross-checked against the SHELL implementations. Two sources of
#     truth for "which port does worker-4 get" is how a scale-out gives a new node the
#     port a live one is already serving on.
#   * ROLLBACK TRIGGERS. A false rollback in production is worse than a slow one, so
#     every allowlisted noise line is asserted to produce NO trigger, alongside each
#     real trigger asserting that it fires. This is the single most important test in
#     the suite.
#   * compose surgery. `prune: true` on a stack update recreates any service whose
#     config hash changed, so an edit that reaches proxy.depends_on disconnects the
#     whole network. The guard is tested by tampering with its output.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    ROOT="$(cd -- "$SCRIPTS_DIR/.." && pwd -P)"
    TMP="$BATS_TEST_TMPDIR/work"
    mkdir -p "$TMP"
}

py() { python3 -c "import sys; sys.path.insert(0, '$SCRIPTS_DIR/remote'); $1"; }

# Same, plus the fakes that stand in for Portainer, the helper container and the node
# registry -- so a test can drive the REAL state machine (resume, converge, rollback,
# the lock) instead of only the pure functions around it. See scripts/test/fakes/.
pyf() {
    SX_FAKE_TMP="$TMP" python3 -c "import os, sys
sys.path.insert(0, '$SCRIPTS_DIR/test/fakes')
import pipefake
TMP = os.environ['SX_FAKE_TMP']
$1"
}

# --- port arithmetic ----------------------------------------------------------

@test "python and shell agree on every port for every node" {
    # The shell is the provisioner's implementation and the python is the CLI's. They
    # answer the same question for the same node and they must never diverge -- the CLI
    # has to be able to say what port worker-4 WILL get before that node exists.
    run bash -c "
        set -Eeuo pipefail
        . '$SCRIPTS_DIR/lib/core.sh'; sx::require velocity
        SX_NODES='lobby worker-1 worker-2 worker-3'
        SX_PORT_BASE=25566; SX_API_PORT_BASE=8800; SX_API_PORT_STRIDE=10; SX_PACK_PORT_BASE=26011
        for n in \$SX_NODES; do
            printf '%s %s %s %s\n' \"\$n\" \"\$(velocity::node_port \$n)\" \
                \"\$(velocity::node_api_base \$n)\" \"\$(velocity::node_pack_port \$n)\"
        done"
    [ "$status" -eq 0 ]
    shell="$output"
    run py "import util
nodes = ['lobby','worker-1','worker-2','worker-3']
for n in nodes:
    print(n, util.game_port(nodes,n,25566), util.api_port(nodes,n,8800,10), util.pack_port(nodes,n,26011))"
    [ "$status" -eq 0 ]
    [ "$output" = "$shell" ]
}

# --- journal and resume -------------------------------------------------------

@test "a torn final line is discarded, not fatal" {
    # This is the NORMAL shape of an interrupted run: the append was cut mid-write.
    run py "import util
text = util.journal_line('r1','build','ok') + '\n' + '{\"run\":\"r1\",\"stage\":\"in'
s = util.replay(text)
print(s['bad'], sorted(s['done']))"
    [ "$output" = "1 [('build', '', '')]" ]
}

@test "the resume point is the first sub-stage that began and never finished" {
    run py "import util
lines = [
  util.journal_line('r1','preflight','ok'),
  util.journal_line('r1','build','ok'),
  util.journal_line('r1','roll','ok',node='worker-3',sub='drain'),
  util.journal_line('r1','roll','begin',node='worker-3',sub='pin'),
]
s = util.replay('\n'.join(lines))
print(s['pending'])"
    [ "$output" = "('roll', 'worker-3', 'pin')" ]
}

@test "a completed run has no resume point" {
    run py "import util
lines = [util.journal_line('r1','build','begin'), util.journal_line('r1','build','ok')]
print(util.replay('\n'.join(lines))['pending'])"
    [ "$output" = "None" ]
}

@test "the journal carries both build ids so a resume never has to guess" {
    run py "import util
line = util.journal_line('r1','roll','ok',node='worker-3',sub='pin',detail={'from':'b0041-a','to':'b0042-b'})
print(util.replay(line)['pins']['worker-3'])"
    [ "$output" = "{'from': 'b0041-a', 'to': 'b0042-b'}" ]
}

# --- the state machine: resume, converge, abort -------------------------------
# These drive the real Pipeline over the fakes. They exist because every bug the review
# found in the stateful half was invisible to a pure-function test: each one is defined
# by what the pipeline DOES to a container or to a pin file on the recovery path -- the
# path taken when something has already gone wrong, which is where a defect costs most.

@test "a resume never re-verifies a node it already verified, and never rolls it back" {
    # THE case. A run dies after worker-2 was reclaimed; `resume` re-walks the whole plan
    # and reaches it again. announce/drain/pin/restart are all guarded -- verify was not.
    # Re-entered, it measures a node that is UP, UNDRAINED and serving: `battery.placements`
    # fails for showing the CORRECT state, and the ready banner is long gone from the tail
    # of a boot that happened ages ago. classify_rollback reads that as a bad build and
    # rollback STOPS a healthy node, dropping its players -- by way of the recovery path.
    run pyf "
lines = pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
pipe, client, helper, registry, events = pipefake.build(
    TMP, journal_lines=lines, pins={'worker-2': ('b0042-new', 'b0041-old')})
registry.rows['worker-2']['players'] = '7'   # devolvido: com gente dentro
registry.placements = 3                      # leases abertos = o estado CERTO
client.log = 'este boot foi ha muito tempo; nada dele esta na cauda'
ok = pipe.roll_node('worker-2', 'b0042-new')
touched = [e for e in events if e[0] in ('stop', 'restart', 'pin')]
print('RESULT', ok, touched,
      pipefake.pin_field(helper.files['/srv/nodes/worker-2/sexidium-build.pin'], 'build'))
"
    [[ "$output" == *"RESULT True [] b0042-new"* ]]
}

@test "resume refuses a needs-human lock and a live lock, and adopts only a stale one" {
    # acquire(force=True) stepped over both guards at once. needs-human is the state that
    # exists to STOP automation after a failed rollback; a live lock means another
    # pipeline is rolling these same nodes and would read this one's half-applied pins.
    run pyf "
import json, pipeline
def attempt(state, age):
    pipe, client, helper, registry, events = pipefake.build(TMP)
    now = int(pipeline.time.time())
    helper.files[pipefake.STATE_DIR + '/pipeline/lock'] = json.dumps(
        {'run': 'outro', 'state': state, 'heartbeat-at': now - age})
    try:
        pipeline.adopt_lock(pipe)
        return 'adopted'
    except Exception as exc:
        return type(exc).__name__
print('RESULT', attempt('needs-human', 99999), attempt('running', 10), attempt('running', 400))
"
    [[ "$output" == *"RESULT PreconditionError PreconditionError adopted"* ]]
}

@test "converge rolls back the nodes the JOURNAL records, not an in-memory list" {
    # On a resumed run pin() short-circuits and never appends to `repinned`, so converging
    # by memory converged nothing: the nodes rolled before the crash stayed on the new
    # build, with no rollback and nobody saying so. Asserted on the PIN FILES, not on a log
    # line -- the whole failure was that the log line was the only thing that happened.
    run pyf "
import pipeline
lines = (pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
         + pipefake.rolled('r1', 'worker-1', 'b0042-new', 'b0041-old', through='restart'))
pipe, client, helper, registry, events = pipefake.build(
    TMP, journal_lines=lines,
    pins={'worker-2': ('b0042-new', 'b0041-old'), 'worker-1': ('b0042-new', 'b0041-old')})
pipeline.converge(pipe, 'worker-1')
print('RESULT',
      pipefake.pin_field(helper.files['/srv/nodes/worker-2/sexidium-build.pin'], 'build'),
      pipefake.pin_field(helper.files['/srv/nodes/worker-1/sexidium-build.pin'], 'build'))
"
    # worker-2 volta; worker-1 é o nó que já foi revertido pelo rollback que disparou isto
    [[ "$output" == *"RESULT b0041-old b0042-new"* ]]
}

@test "a resume installs the build its journal names, not whatever LATEST says now" {
    # A `pipeline deploy` landing between the crash and the resume moves LATEST. Resuming
    # onto it splits the network across two builds while the journal claims one.
    run pyf "
import pipeline
lines = pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
pipe, client, helper, registry, events = pipefake.build(TMP, journal_lines=lines)
latest = pipefake.STATE_DIR + '/builds/LATEST'
helper.files[latest] = 'b0042-new\n'
agreeing = pipeline.resume_build(pipe)
helper.files[latest] = 'b0043-other\n'
try:
    moved = pipeline.resume_build(pipe)
except Exception as exc:
    moved = type(exc).__name__
pipe.resume['pins'] = {}          # um run que caiu antes de virar qualquer pin
print('RESULT', agreeing, moved, pipeline.resume_build(pipe))
"
    [[ "$output" == *"RESULT b0042-new PreconditionError b0043-other"* ]]
}

@test "a torn pin line rolls back to previous=, never to the build being escaped" {
    # The journal says the pin HAPPENED but the line carrying `from` was cut mid-write.
    # Falling back to the pin file's `build=` hands rollback the very build it is trying
    # to escape -- a silent no-op at the one moment reversibility is the whole point.
    run pyf "
import json, util
lines = [l for l in pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
         if json.loads(l).get('sub') != 'pin']
lines.append(util.journal_line('r1', 'roll', 'ok', node='worker-2', sub='pin'))
pipe, client, helper, registry, events = pipefake.build(
    TMP, journal_lines=lines, pins={'worker-2': ('b0042-new', 'b0041-old')})
print('RESULT', pipe.pin('worker-2', 'b0042-new'))
"
    [[ "$output" == *"RESULT b0041-old"* ]]
}

@test "converge drains a reclaimed node before it stops it" {
    # rollback()'s stop is safe on the main path -- that node is still drained. converge
    # calls it on nodes that were RECLAIMED: undrained, serving. Stopping one of those is
    # the disconnect this pipeline exists to prevent, delivered by the recovery path.
    run pyf "
import pipeline
lines = (pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
         + pipefake.rolled('r1', 'worker-1', 'b0042-new', 'b0041-old', through='restart'))
pipe, client, helper, registry, events = pipefake.build(
    TMP, journal_lines=lines,
    pins={'worker-2': ('b0042-new', 'b0041-old'), 'worker-1': ('b0042-new', 'b0041-old')})
registry.rows['worker-2']['players'] = '6'
pipeline.converge(pipe, 'worker-1')
names = [(e[0], e[2] if e[0] == 'command' else e[1]) for e in events]
drain = next(i for i, e in enumerate(names)
             if e[0] == 'command' and e[1].startswith('sx admin net drain'))
stop = next(i for i, e in enumerate(names) if e[0] == 'stop')
warned = any(e[0] == 'command' and 'broadcast' in e[1] for e in names[:drain])
print('RESULT', drain < stop, warned, registry.drained[0])
"
    [[ "$output" == *"RESULT True True worker-2"* ]]
}

@test "abort drains a reclaimed node before it restarts it, and --skip-drain opts out" {
    # Same hazard on the other unwind route: `pipeline abort` restarted every repinned
    # node with no drain and no warning.
    run pyf "
import pipeline
def unwind(**kw):
    lines = pipefake.rolled('r1', 'worker-2', 'b0042-new', 'b0041-old')
    pipe, client, helper, registry, events = pipefake.build(
        TMP, journal_lines=lines, pins={'worker-2': ('b0042-new', 'b0041-old')})
    registry.rows['worker-2']['players'] = '6'
    helper.files[pipefake.STATE_DIR + '/pipeline/runs/current'] = 'r1\n'
    pipefake.patch_helpers(helper, registry)
    pipeline.cmd_abort(client, pipe.settings, pipefake.Args(**kw))
    order = [(e[0], e[2] if e[0] == 'command' else e[1]) for e in events]
    drained = [i for i, e in enumerate(order)
               if e[0] == 'command' and e[1].startswith('sx admin net drain')]
    restart = next(i for i, e in enumerate(order) if e[0] == 'restart')
    return (drained[0] < restart) if drained else 'no-drain'
print('RESULT', unwind(), unwind(skip_drain=True))
"
    [[ "$output" == *"RESULT True no-drain"* ]]
}

@test "the autoscaler resends the deployed env and refuses when the local secrets differ" {
    # stack_env() calls secrets(create=True), which GENERATES anything missing locally. An
    # autoscaler running from a host without remote.secrets.json would invent a new
    # FORWARDING_SECRET and PUT it with prune:true: every container recreated AND the proxy
    # no longer matching the backends, so every join is refused -- from a routine scale-up.
    run pyf "
import json, scale
nodes = ['lobby', 'worker-1', 'worker-2', 'worker-3']
pipe, client, helper, registry, events = pipefake.build(TMP, nodes=nodes)
pipefake.patch_helpers(helper, registry)
env = [{'name': 'DB_PASSWORD', 'value': 'dbpw'}, {'name': 'API_TOKEN', 'value': 'tok'},
       {'name': 'FORWARDING_SECRET', 'value': 'fwd'}, {'name': 'DB_ROOT_PASSWORD', 'value': 'rootpw'}]
client.stacks = [{'Id': 7, 'Name': 'sexidium', 'Env': env,
                  '_file': open('$ROOT/docker/stack.sexidium.yml').read()}]
scale.cmd_scale(client, pipe.settings, pipefake.Args(direction='up'))
sent = json.dumps(client.puts[0][1]['env'], sort_keys=True) == json.dumps(env, sort_keys=True)
generated = [c for c in pipe.settings.secret_calls if c]
env[2]['value'] = 'rotacionado-noutro-lugar'   # o stack tem um valor que este host nao tem
try:
    scale.cmd_scale(client, pipe.settings, pipefake.Args(direction='up'))
    refused = 'PUT-ANYWAY'
except Exception as exc:
    refused = type(exc).__name__
print('RESULT', sent, generated, refused, len(client.puts))
"
    [[ "$output" == *"RESULT True [] PreconditionError 1"* ]]
}

@test "stack and provision take the lock, and a caller that already holds it does not deadlock" {
    # The Lock docstring names this exact scenario: a hand-run command mid-deploy PUTs the
    # compose with prune:true and recreates the containers the pipeline just drained and
    # pinned. The second half matters just as much -- `update` and the autoscaler call
    # provision WITH the lock in hand, and a naive decorator makes them fail against
    # themselves.
    run pyf "
import json, time, journal, ops
pipe, client, helper, registry, events = pipefake.build(TMP)
pipefake.patch_helpers(helper, registry)
lock = pipefake.STATE_DIR + '/pipeline/lock'

helper.files[lock] = json.dumps({'run': 'deploy-vivo', 'state': 'running',
                                 'heartbeat-at': int(time.time())})
outcome = []
for fn in (ops.cmd_stack, ops.cmd_provision):
    try:
        fn(client, pipe.settings, pipefake.Args(only=None, prune_repo=False, dirs=[]))
        outcome.append('ran')
    except Exception as exc:
        outcome.append(type(exc).__name__)

del helper.files[lock]
mine = journal.Lock(helper, pipefake.STATE_DIR, 'meu-run')
mine.acquire()
try:
    ops.cmd_provision(client, pipe.settings, pipefake.Args())
    outcome.append('reentered')
except Exception as exc:
    outcome.append(type(exc).__name__)
print('RESULT', outcome)
"
    [[ "$output" == *"RESULT ['PreconditionError', 'PreconditionError', 'reentered']"* ]]
}

# --- rollback triggers: they must fire ----------------------------------------

@test "R2 fires when RestartCount climbs" {
    run py "import util
print([t for t,_ in util.classify_rollback({'restart_count_before':3,'restart_count_now':5})])"
    [ "$output" = "['R2']" ]
}

@test "R3 fires when the plugin began enabling and never became ready" {
    run py "import util
log = '[12:00:00 INFO]: [Sexidium] Enabling Sexidium v1.0.0\n[12:00:09 INFO]: Done (21.5s)!'
print([t for t,_ in util.classify_rollback({'log': log})])"
    [ "$output" = "['R3']" ]
}

@test "'Done (' alone is not proof of a healthy boot" {
    # Paper prints `Done (` even when a plugin threw during enable and was disabled --
    # observed live with BetterHud. If this ever passes, the gate has been weakened.
    run py "import util
log = 'Enabling Sexidium\nDone (30.2s)! For help, type \"help\"'
print(len(util.classify_rollback({'log': log})) > 0)"
    [ "$output" = "True" ]
}

@test "R4 fires when the node reports a build that is not the one pinned" {
    run py "import util
print([t for t,_ in util.classify_rollback(
    {'pinned_build':'b0042-abc','plugin_version':'1.0.0+b0041-xyz','log':''})])"
    [ "$output" = "['R4']" ]
}

@test "R6 fires on OOMKilled, on exit 137 and on a heap OOM in the log" {
    run py "import util
cases = [{'oom_killed':True},{'exit_code':137},{'log':'java.lang.OutOfMemoryError: Java heap space'}]
print([[t for t,_ in util.classify_rollback(c)] for c in cases])"
    [ "$output" = "[['R6'], ['R6'], ['R6']]" ]
}

@test "R7 fires only while the container is still running" {
    run py "import util
print([t for t,_ in util.classify_rollback({'running':True,'heartbeat_age':45})],
      [t for t,_ in util.classify_rollback({'running':False,'heartbeat_age':45})])"
    [ "$output" = "['R7'] []" ]
}

# --- rollback triggers: they must NOT fire ------------------------------------

@test "a clean boot triggers nothing" {
    run py "import util
log = '''[12:00:01 INFO]: [Sexidium] Enabling Sexidium v1.0.0
[12:00:08 INFO]: [Sexidium] SX-READY node=worker-3 protocol=1 build=1.0.0+b0042-abc players=0
[12:00:09 INFO]: Done (21.5s)!'''
print(util.classify_rollback({
    'log': log, 'restart_count_before': 2, 'restart_count_now': 2,
    'pinned_build': 'b0042-abc', 'plugin_version': '1.0.0+b0042-abc',
    'battery': [('battery.selftest','SKIP','not shipped')], 'running': True, 'heartbeat_age': 4}))"
    [ "$output" = "[]" ]
}

@test "every allowlisted noise line is NOT a rollback trigger" {
    # The false-positive gate. Each of these is pre-existing, benign and seen live; a
    # rollback fired by any of them would be a production incident caused by the tool
    # that exists to prevent one.
    run py "import util
noise = [
  '[Multiverse-Core] Failed to autoload world sexidium_temp_x : WORLD_FOLDER_INVALID',
  '[Multiverse-Core] Safe spawn NOT found',
  '[FancyHolograms] No holograms section found in config',
  '[SkinsRestorer] Proxy mode API is enabled but database storage is not set up',
  'MineSkinClient without API key',
  '[BetterHud] Plugin disabled.',
  'WARNING: A restricted method in java.lang.System has been called',
  '[connected player] read timed out',
  # A linha que o node-entry.sh imprime ao subir CADA nó. Ela contém a flag
  # -XX:+ExitOnOutOfMemoryError, e casar o sufixo solto \"OutOfMemoryError\" fazia o R6
  # disparar em toda rolagem, sobre um nó que tinha acabado de passar a bateria inteira.
  # Custou uma reversão ao vivo, e enquanto valesse nenhum deploy sem queda concluiria.
  '[node-entry:worker-2] iniciando Paper (heap: -Xms1G -Xmx3G -XX:+ExitOnOutOfMemoryError -XX:MaxMetaspaceSize=512m -XX:+UseZGC)',
]
ready = '[Sexidium] SX-READY node=worker-3 build=1.0.0+b0042-abc'
bad = [n for n in noise if util.classify_rollback({'log': 'Enabling Sexidium\n' + ready + '\n' + n})]
print(bad)"
    [ "$output" = "[]" ]
}

@test "a SKIPped battery assertion never triggers a rollback" {
    # SKIP is what an assertion whose JVM-side half has not shipped returns. If SKIP
    # could roll back, the pipeline could not be deployed before the JVM work landed.
    run py "import util
print(util.classify_rollback({'battery':[('battery.selftest','SKIP','x'),('battery.api','PASS','')]}),
      [t for t,_ in util.classify_rollback({'battery':[('battery.api','FAIL','no HTTP')]})])"
    [ "$output" = "[] ['R5']" ]
}

@test "an empty plugin_version is a SKIP, not a rollback" {
    # A build that predates this work reports nothing. "The JVM did not tell us" must
    # never mean "roll back", or a mixed fleet would roll itself back on every deploy.
    run py "import util
print(util.classify_rollback({'pinned_build':'b0042-abc','plugin_version':'','log':''}))"
    [ "$output" = "[]" ]
}

# --- compose surgery ----------------------------------------------------------

@test "adding a worker touches exactly the four allowed hunks" {
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4)
print(util.compose_guard(before, after, 'worker-4'))"
    [ "$output" = "[]" ]
}

@test "adding a worker appends to SX_NODES and never reorders it" {
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4)
old, new = util.compose_nodes(before), util.compose_nodes(after)
print(new == old + ['worker-4'])"
    [ "$output" = "True" ]
}

@test "the new worker gets the init mount, its own volume and its own pack port" {
    # Without the init mount, provision.sh writes /srv/nodes/worker-4 inside the init
    # container's own filesystem and the real worker dies on a missing node.args.
    #
    # NOTE the port is 26014 while the node is called worker-4, and that mismatch is the
    # point: the pack port follows the node's POSITION in SX_NODES (26011 + index), never
    # the number in its name. The network runs lobby + 2 workers, so a new node lands at
    # index 3. The name is just a name -- worker-3 was retired and is not reused.
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4)
print('- sexidium-worker-4:/srv/nodes/worker-4' in after,
      '  sexidium-worker-4:\n    external: true' in after,
      '127.0.0.1:26014:26014/tcp' in after)"
    [ "$output" = "True True True" ]
}

@test "editing proxy.depends_on is REFUSED" {
    # depends_on only orders startup and the proxy finds a new backend from the DB
    # registry anyway -- but touching it changes the proxy's config hash, and a stack
    # update runs with prune:true, so the proxy would be recreated and every player on
    # the network disconnected.
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4)
tampered = after.replace('      worker-2: {condition: service_started}',
    '      worker-2: {condition: service_started}\n      worker-4: {condition: service_started}')
print(len(util.compose_guard(before, tampered, 'worker-4')) > 0)"
    [ "$output" = "True" ]
}

@test "losing the db profile guard is REFUSED" {
    # Starting the dormant db service runs a SECOND mysqld on the datadir the hand-run
    # sexidium-database container already has open. That is data loss, not downtime.
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4).replace('    profiles: [\"db\"]\n', '')
print(any('profiles' in p for p in util.compose_guard(before, after, 'worker-4')))"
    [ "$output" = "True" ]
}

@test "an unrelated edit to a live service is REFUSED" {
    run py "import util
before = open('$ROOT/docker/stack.sexidium.yml').read()
after = util.add_worker_to_compose(before, 'worker-4', 4).replace('    mem_limit: 4g', '    mem_limit: 9g')
print(len(util.compose_guard(before, after, 'worker-4')) > 0)"
    [ "$output" = "True" ]
}

# --- capacity -----------------------------------------------------------------

@test "the worker ceiling is computed from real memory and never rounds up" {
    # 31.07 GiB host, 1 proxy + 4 lobby + 6 init reserve, 3 GiB per autoscaled worker.
    run py "import util
gib = 1024**3
print(util.max_workers(int(31.07*gib), 1*gib, 4*gib, 6*gib, 3*gib))"
    [ "$output" = "5" ]
}

@test "no host memory means no scaling, not unlimited scaling" {
    run py "import util
print(util.max_workers(0, 1, 1, 1, 1), util.max_workers(1024**4, 0, 0, 0, 0))"
    [ "$output" = "0 0" ]
}

@test "the capacity alert shows the sum that actually governs, not just the formula" {
    # Measured live: 31.07 GiB host, 20 GiB already committed (proxy 1 + lobby 4 +
    # 3x5 -- the hand-placed workers are 5g, not the 3g an autoscaled one gets). The
    # uniform-size formula says 5 workers; the real sum says one more worker plus the
    # init reserve does not fit. The alert must show BOTH, and the operator must be able
    # to see which one governs.
    run py "import util
gib = 1024**3
print(util.capacity_report(int(31.07*gib), 20*gib, 1*gib, 4*gib, 6*gib, 3*gib))"
    [[ "$output" == *"29.0 GB > 26.4 GB"* ]]
    [[ "$output" == *"ceiling would be 5 workers"* ]]
    [[ "$output" == *"SX_MAX_WORKERS"* ]]
    [[ "$output" == *"add a host"* ]]
}

# --- player counts ------------------------------------------------------------

@test "player counts never double-count the proxy row" {
    # The proxy row counts the WHOLE network and every backend row counts its own
    # players again, so SUM over all rows is ~2x reality -- and an autoscaler fed
    # double would keep adding nodes until it hit the memory ceiling.
    run py "import util
rows = [{'role':'proxy','players':'10'},{'role':'lobby','players':'4'},
        {'role':'worker','players':'3'},{'role':'worker','players':'3'}]
print(util.player_counts(rows))"
    [ "$output" = "(10, 10)" ]
}

# --- shell quoting ------------------------------------------------------------

@test "a '%' in a command survives the journal and the HTTP request" {
    # The request carries the API token, and it used to be built as a printf FORMAT.
    # A '%' anywhere in a command (a broadcast reason, a build id) would then be eaten
    # by printf -- producing a corrupt request, which is an UNAUTHENTICATED request.
    run py "import pipeline
class H:
    def sh(self, script, timeout=None):
        self.last = script
        return 0, 'HTTP/1.0 200 OK\r\n\r\n{}'
h = H()
pipeline.node_command(h, 'worker-1', 8810, 'tok', 'sx admin broadcast 60 100%-off')
print(\"printf '%s'\" in h.last, '100%-off' in h.last)"
    [ "$output" = "True True" ]
}

# --- o caminho de controle: os dois jeitos de ele não sair do lugar ------------
# Nenhum dos dois falha ALTO. `node_http` devolve (None, …) quando o script morre, e o
# pipeline lê isso como "o nó não respondeu" -- indistinguível de um nó ocupado. O
# resultado ao vivo foi um `pipeline deploy` esperando os 300 s inteiros por um dreno
# que nunca chegou a ser pedido, e depois abortando por "timeout".

@test "the command body is the raw command, never a JSON envelope" {
    # ApiServer.handleCommand lê o corpo INTEIRO como a linha de comando
    # (`new String(readAll(...)).trim()`); não há parse de JSON do outro lado. Um
    # `{"command": "..."}` era despachado com chaves e aspas e virava comando
    # desconhecido -- e, com a allowlist vazia, nem recusado era.
    run py "import pipeline
class H:
    def sh(self, script, timeout=None):
        self.last = script
        return 0, 'HTTP/1.0 200 OK\r\n\r\n{}'
h = H()
pipeline.node_command(h, 'worker-1', 8810, 'tok', 'sx admin net drain rolling-update')
body = h.last.split('\r\n\r\n')[-1]
print('{\"command\"' not in h.last, body.startswith('sx admin net drain rolling-update'))"
    [ "$output" = "True True" ]
}

@test "the Content-Length matches the body the server will read" {
    # Um Content-Length maior que o corpo faz o servidor esperar bytes que não vêm; um
    # menor trunca o comando. Os dois viram silêncio, que é o modo de falhar deste
    # caminho inteiro.
    run py "import pipeline
class H:
    def sh(self, script, timeout=None):
        self.last = script
        return 0, 'HTTP/1.0 200 OK\r\n\r\n{}'
h = H()
cmd = 'sx admin net drain rolling-update'
pipeline.node_command(h, 'worker-1', 8810, 'tok', cmd)
head, _, body = h.last.partition('\r\n\r\n')
declared = [l for l in head.splitlines() if l.startswith('Content-Length:')][0]
print(int(declared.split(':')[1]) == len(cmd))"
    [ "$output" = "True" ]
}

@test "a TTY-doubled CR does not swallow the response body" {
    # O exec do Docker é criado com Tty=True, e um TTY traduz cada \n da saída em \r\n --
    # então o \r\n do servidor chega como \r\r\n e o separador cabeçalho/corpo vira
    # \r\r\n\r\r\n. Com um partition literal o corpo saía VAZIO junto de um status 200,
    # que é o pior jeito de falhar: node_json virava None e o verify pós-restart concluía
    # que o nó não sabia dizer em que build estava.
    run py "import pipeline
class H:
    def sh(self, script, timeout=None):
        return 0, 'HTTP/1.1 200 OK\r\r\nContent-type: application/json\r\r\n\r\r\n{\"phase\":\"READY\"}'
print(pipeline.node_json(H(), 'worker-1', 8810, '/node/drain', 'tok'))"
    [[ "$output" == *"'phase': 'READY'"* ]]
}

@test "a normal single-CR response still parses" {
    # A tolerância não pode virar dependência: fora do TTY a resposta é \r\n limpa.
    run py "import pipeline
class H:
    def sh(self, script, timeout=None):
        return 0, 'HTTP/1.1 200 OK\r\nContent-type: application/json\r\n\r\n{\"phase\":\"NONE\"}'
print(pipeline.node_json(H(), 'worker-1', 8810, '/node/drain', 'tok'))"
    [[ "$output" == *"'phase': 'NONE'"* ]]
}

@test "the helper runs its scripts under bash, because /dev/tcp is a bash feature" {
    # /dev/tcp não é um arquivo, é sintaxe que o bash intercepta. Sob o /bin/sh do
    # Debian (dash) vira `cannot create /dev/tcp/...: Directory nonexistent`.
    run py "from journal import Helper
class C:
    def sh(self, name, script, timeout=None, shell='sh'):
        self.shell = shell
        return 0, ''
c = C()
h = Helper.__new__(Helper)
h.client, h.name = c, 'x'
h.sh('exec 3<>/dev/tcp/worker-1/8810')
print(c.shell)"
    [ "$output" = "bash" ]
}

@test "shell_quote survives a single quote in a value" {
    run py "from journal import shell_quote
import subprocess
value = \"it's \$(touch /tmp/sx-pwned) fine\"
out = subprocess.run(['sh','-c','printf %s ' + shell_quote(value)], capture_output=True, text=True)
print(out.stdout == value)"
    [ "$output" = "True" ]
    [ ! -e /tmp/sx-pwned ]
}

# --- o carimbo de provisionamento -------------------------------------------
# O comando que o check monta roda num shell REMOTO e o status dele decide PASS/FAIL.
# `cmd; test -f lápide && echo TOMBSTONE` termina com o status do `test` -- que é 1
# justamente quando a lápide NÃO existe, ou seja, na rede SAUDÁVEL. O check reprovava
# sempre, e como `pipeline deploy` recusa preflight em rede não-saudável, isto sozinho
# barrava todo rolling update. Verificado contra a rede real: carimbo presente, sem
# lápide, e mesmo assim `provision.stamp FAIL`.
@test "the provision-stamp probe exits 0 when there is no tombstone" {
    stamp="$TMP/.provisioned"
    printf 'provisioned-at=2026-08-14T22:09:46+00:00\nnodes=lobby worker-1\n' >"$stamp"
    # checks.py vem por ARGV, a partir de $SCRIPTS_DIR (que sai de BATS_TEST_DIRNAME),
    # como todo o resto deste arquivo faz. Um caminho relativo aqui só funciona quando
    # o bats é chamado da raiz do repo: passava na workstation e explodia com
    # FileNotFoundError dentro do container do gate, que roda de outro CWD.
    probe="$(python3 - "$TMP" "$SCRIPTS_DIR/remote/checks.py" <<'PY'
import re, sys, pathlib
state = sys.argv[1]
source = pathlib.Path(sys.argv[2]).read_text()
body = source[source.index("def provision_stamp"):]
body = body[:body.index("if code != 0")]
parts = re.findall(r'f"([^"]*)"', body)
print("".join(parts).replace("{state}", state))
PY
)"
    run sh -c "$probe"
    [ "$status" -eq 0 ]
    [[ "$output" == *"provisioned-at"* ]]
    [[ "$output" != *"TOMBSTONE"* ]]
}

@test "the provision-stamp probe still reports a tombstone when one exists" {
    printf 'provisioned-at=x\n' >"$TMP/.provisioned"
    : >"$TMP/.provision-failed"
    probe="$(python3 - "$TMP" "$SCRIPTS_DIR/remote/checks.py" <<'PY'
import re, sys, pathlib
state = sys.argv[1]
source = pathlib.Path(sys.argv[2]).read_text()
body = source[source.index("def provision_stamp"):]
body = body[:body.index("if code != 0")]
parts = re.findall(r'f"([^"]*)"', body)
print("".join(parts).replace("{state}", state))
PY
)"
    run sh -c "$probe"
    [ "$status" -eq 0 ]
    [[ "$output" == *"TOMBSTONE"* ]]
}

# --- a trava do lobby --------------------------------------------------------
# `lobby` está SEMPRE na ordem de rolagem, o papel vem de um nome exato e o autoscaler
# só cria `worker-N`: exigir 2 lobbies vivos era insatisfazível por construção, então
# todo `pipeline deploy` abortava -- ou pedia --allow-lobby-disconnect, a desconexão que
# a regra existe para impedir. A regra protege JOGADOR, não contagem de lobby.
@test "a single EMPTY lobby does not block the roll" {
    run py "
import pipeline
class R:
    def all_nodes(self): return [{'role':'lobby','state':'UP','players':0}]
class P(pipeline.Pipeline):
    def __init__(self): self.registry = R()
print(P().lobby_players())
"
    [ "$status" -eq 0 ]
    [ "$output" = "0" ]
}

@test "a single OCCUPIED lobby still blocks the roll" {
    run py "
import pipeline
class R:
    def all_nodes(self): return [{'role':'lobby','state':'UP','players':3}]
class P(pipeline.Pipeline):
    def __init__(self): self.registry = R()
print(P().lobby_players())
"
    [ "$output" = "3" ]
}

@test "an unreadable player count is treated as occupied, never as empty" {
    run py "
import pipeline
class R:
    def all_nodes(self): return [{'role':'lobby','state':'UP','players':'?'}]
class P(pipeline.Pipeline):
    def __init__(self): self.registry = R()
print(P().lobby_players())
"
    [ "$output" = "1" ]
}

# --- the proxy leg -------------------------------------------------------------
# The skip is decided by the sha of the Sexidium velocity jar ALONE, so a change that
# only touches proxy config or a third-party jar leaves it unmoved and the leg is
# skipped -- and Velocity hot-reloads nothing, so "skipped" there means "not applied".
# --force-proxy is the supported way to ask for the leg anyway; these pin all three
# branches, including the refusal message, which used to claim the jar had changed in
# exactly the case where the flag guarantees it has not.

@test "the proxy leg is skipped, forced, and refuses with a reason that is true" {
    run pyf "
import pipeline

def mk(**kw):
    pipe, client, helper, registry, events = pipefake.build(TMP, **kw)
    helper.files['/srv/nodes/proxy/sexidium-build.pin'] = 'build=b0042-new\nsha256=deadbeef\n'
    real = helper.sh
    helper.sh = lambda s, timeout=None: (0, 'deadbeef\n') if 'sha256sum' in s else real(s, timeout)
    pipe.say = lambda *a, **k: None
    return pipe, events

# Sem a flag e com o jar igual: nada reinicia, ninguém cai.
pipe, events = mk()
skipped = (pipe.proxy_leg('b0042-new'), [e for e in events if e[0] == 'restart'])

# Com a flag e sem a janela: recusa, e a razão NÃO pode dizer que o plugin mudou.
pipe, events = mk(force_proxy=True)
try:
    pipe.proxy_leg('b0042-new')
    refusal = 'no-raise'
except Exception as exc:
    refusal = str(exc)

# Com as duas: a perna roda mesmo com o jar inalterado.
pipe, events = mk(force_proxy=True, maintenance_window=True)
forced = (pipe.proxy_leg('b0042-new'), [e for e in events if e[0] == 'restart'])
print('RESULT', skipped, forced, 'MUDOU' in refusal, '--force-proxy foi pedido' in refusal)
"
    [[ "$output" == *"RESULT (True, []) (True, [('restart', 'sexidium-proxy')]) False True"* ]]
}
