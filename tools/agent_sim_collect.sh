#!/bin/bash
# Collects mock chatbot replies for AgentChatbotSimulationTest: each app prompt goes once to
# Claude Code (claude -p) and Codex CLI (codex exec), run in an empty directory with no project
# access. Replies are stored verbatim next to the prompts; the unit test never calls an LLM.
set -euo pipefail
dir="$(cd "$(dirname "$0")/.." && pwd)/app/src/test/resources/agent/sim"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
for prompt in "$dir"/prompt-*.txt; do
    n="${prompt##*prompt-}"; n="${n%.txt}"
    (cd "$work" && claude -p < "$prompt" > "$dir/claude-$n.txt") &
    (cd "$work" && codex exec --sandbox read-only --skip-git-repo-check --color never \
        -o "$dir/codex-$n.txt" - < "$prompt" > /dev/null 2> "$work/codex-$n.log") &
done
wait
ls -l "$dir"

# Request 1 is ambiguous on purpose-free data (text already sits under the logo); both clients ask a
# question. A second turn in the same session answers it, as the user would in the chat app.
if [ "${AGENT_SIM_TURN2:-1}" = 1 ]; then
    answer="새 글자로 추가해 줘. myembro는 그대로 두고, 위치는 알아서 정해 줘."
    w1="$(mktemp -d)"; w2="$(mktemp -d)"
    (cd "$w1" && claude -p < "$dir/prompt-1.txt" > /dev/null && claude -p --continue "$answer" > "$dir/claude-1b.txt") &
    (cd "$w2" && codex exec --sandbox read-only --skip-git-repo-check --color never - < "$dir/prompt-1.txt" > /dev/null 2>&1 &&
        codex exec resume --last --skip-git-repo-check -o "$dir/codex-1b.txt" "$answer" > /dev/null 2>&1) &
    wait
    rm -rf "$w1" "$w2"
fi
