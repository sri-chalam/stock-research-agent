# 03: Agent-app spans: classifier, cache, agents, tools

**What to build:** a chat trace shows how long each step inside agent-app takes: the prompt classifier, the semantic-cache lookup and store, the Supervisor and each specialist agent run, and each tool call named after the tool, with each agent's LLM calls nested under it.

**Blocked by:** 01

**Status:** ready-for-agent

## Decisions

- Spans carry names and durations only. Tokens, cost and prompt version wait for NFR-11a.
- Start by checking the risk recorded in ADR 0013: do the agentic listener's start and end events for one agent run on the same thread? If they do, make the agent span current so its LLM and tool spans nest under it. If not, record the finding in the learning notes and accept sibling spans with correct durations.
- `@WithSpan` goes on the classifier call, the cache lookup and the cache store.
- Agent spans are opened and closed by an agentic listener, one span per Supervisor or specialist run, named after the agent.
- Tool spans in agent-app are named after the tool and cover the whole call as the agent sees it, network included. They use the same tool-execution hook as the existing tool logging.
- The cache's question embedding is an inline OTel API span (`tracer.spanBuilder`), not a method extracted for `@WithSpan`.

## Acceptance criteria

- [ ] A cache-miss trace shows, each with a duration: the classifier, the cache lookup with its embedding span, the Supervisor run, each specialist run, each LLM call under the agent that made it, each tool call by name, and the cache store when the answer is stored.
- [ ] A cache-hit trace shows the classifier and the cache lookup, and no agent spans.
- [ ] The learning notes record whether agent spans nest their LLM calls, and why.
- [ ] `./gradlew build` passes, and the tests still run with no agent attached.
