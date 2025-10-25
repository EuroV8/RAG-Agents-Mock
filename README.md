# Mock RAG AI Support Agents

Mock RAG implementation of RAG using a local embedding server with the ability to fully control endpoints and
agent parameters through configuration files.

# IMPORTANT NOTE :
**This project was developped with a *Linux* environment in mind.** <br>
Although Docker, Python and Java are available on Windows installations and should be platform-independent, the provided shell scripts won't work on Windows CMD / Powershell. <br>
In that case, it is reccomended to deploy the individual parts by yourself.

## Features
- Fully autonomous deploy script which runs the installation and dependencies
- *Technical specialist* uses OpenSearch vector embeddings through kNN search via `AbstractDocAgent`
- *Billing specialist* also using OpenSearch vector stores, but also orchestrates LLM tool calls for billing-related requests
- Conversation router with shared history allowing multi-turn escalation between agents, and parametrable max message history count (implemented using a simple queue)
- Local put script (`OpenSearchIndexer`) to push docs into the OpenSearch database
- Docker Compose template to spin up OpenSearch + Dashboards locally, see [the official OpenSearch guide](https://docs.opensearch.org/latest/getting-started/)
- Configurable LLM endpoints : `config/llm_config.json`

## Architecture
- `DocHelperAgent` : extends `AbstractDocAgent`, performs vector k-Nearest Neighbours retrieval and calls an LLM
- `BillingHelperAgent` : uses Chat Completions style tool calling to trigger:
    - `open_refund_case`
    - `confirm_plan_details`
    - `explain_refund_timeline`
- `AgentRouter` : scores each user request and selects the most capable agent while preserving per-agent chat context
- `UI` : Simple Swing-based chat window
- `Config` : JSON settings (`config/*.json`) provide endpoints, credentials, and tuning knobs for agents, LLMs, and embeddings—override with environment variables when needed.
- `Retrieval` : Instantiates OpenSearch clients plus request builders and result post-processing shared by both agents

## Prerequisites and Dependencies
- Java 17+
- Maven 3.8+
- Docker (for local OpenSearch)
- LLM + embedding endpoints (OpenAI-compatible payloads tested)
- Python (for the embedding server if not using an API embedding endpoint)

## Setup
**IMPORTANT PREREQUISITE**
- Remember to put your API key (if using an external endpoint) in `config/doc_agent.json` and `llm_config.json`. The local embedding model does not require a key.
- A default password which validates the OpenSearch image's requirements is provided in the `opensearch/docker-compose.yml` file.<br>

**STEPS**
- Use the provided `scripts/deploy.sh` bash shell script.
- By default, debugging output is disabled, if you wish to enable it, set the "DEBUG" macro in `src/main/java/com/inksoftware/Main.java` to `true`.
- If you wish to do each step individually, refer to said script.

## Run
- Shaded JAR, run in the project's root : `java -jar target/ai-support-client-1.0.jar`

## Shutdown
A small shell script is provided for the shutdown of the docker instance and python embedding server in `scripts/shutdown.sh`

## Local embedding server
Endpoint: `http://127.0.0.1:11435/v1/embeddings` (matches the default in `config/doc_agent.json`).

## OpenSearch dashboard
OpenSearch's dashboard is available at <http://localhost:5601/app/home> <br>
You can use it to check if the documents and indexes you added with the OpenSearchIndexer utility correctly show up in the database.

## Adding and embedding documents
There are two default subdirectories in `docs/`, containing sample documents for you to use : 
- `docs/technical`
and
- `docs/billing`

Run the indexer (from the project's root) after updating docs:

```shell 
mvn -q exec:java -Dexec.mainClass=com.inksoftware.tools.OpenSearchIndexer -Dexec.args="docs/technical"
mvn -q exec:java -Dexec.mainClass=com.inksoftware.tools.OpenSearchIndexer -Dexec.args="docs/billing"
```
## Billing Agent Capabilities
- Open refund cases and provide the intake form with review timelines.
- Confirm plan pricing, features, and refund/cancellation policies.
- Explain refund timelines with plan-specific details pulled from policy docs.


## Document Agent
- Performs OpenSearch-backed retrieval (vector kNN search) across the configured *technical* indices.
- Packages the user question plus the top snippets and sends them to the LLM for grounded answers.

## Sample prompts 
A few sample prompts to check the functionality of the agents : 
- Technical : `The dashboard charts are empty after deploying. Any checklist to debug that?`
- Technical : `I need to sign webhook requests; how do I validate the signature and trigger a replay?`
- Technical : `What’s the rate limit for API keys and how should a Java client authenticate?`
- Billing : `How long does a Growth plan refund take, and what documents should I attach?`
- Billing : `Customer wants to downgrade from Scale to Growth—what warnings should I give?`
- Billing : `Can you open a refund case and send me the form link for workspace ACME?`