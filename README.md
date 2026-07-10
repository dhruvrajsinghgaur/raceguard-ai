# RaceGuard AI

## AI-Powered Java Concurrency Analysis on AMD GPUs

RaceGuard AI is a hybrid static analysis and AI reasoning platform that detects concurrency defects in Java applications and explains them in plain English.

Developers can upload a ZIP archive containing Java source code or provide a public Git repository URL. RaceGuard AI performs deterministic concurrency analysis, identifies potential risks, generates remediation recommendations, and produces an interactive HTML report.

All AI reasoning is executed locally using a Large Language Model served through vLLM on AMD GPU infrastructure.

---

## Why RaceGuard AI?

Concurrency bugs are notoriously difficult to detect because they often:

* Occur only under specific thread interleavings
* Escape traditional testing
* Cause intermittent production failures
* Lead to data corruption and unpredictable behavior

RaceGuard AI combines static program analysis with AI-assisted explanations to help developers understand not only where a risk exists, but also why it matters and how to fix it.

---

## Key Features

### Deterministic Static Analysis

RaceGuard AI parses Java source code into an Abstract Syntax Tree (AST) using JavaParser and builds a project-wide concurrency model.

The analyzer detects:

* Shared Mutable State
* Lost Updates
* Unsafe Publication
* Check-Then-Act Races
* Cross-Class Check-Then-Act Patterns
* Unsafe Lazy Initialization
* Double-Checked Locking
* Potential Deadlocks

### Cross-Class Concurrency Tracking

Unlike simple file-level analyzers, RaceGuard AI links field accesses across multiple classes to identify concurrency risks that span object boundaries.

### Deadlock Detection

RaceGuard AI builds a lock-order graph from nested synchronized blocks and searches for lock acquisition cycles.

Example:

Thread A:
Lock A → Lock B

Thread B:
Lock B → Lock A

This creates a potential deadlock cycle.

### AI-Powered Risk Explanation

After deterministic detection, RaceGuard AI sends each finding to a locally hosted LLM.

The model generates:

* Plain-English explanations
* Possible thread interleavings
* Severity reasoning
* Remediation guidance

### Interactive Web Interface

* ZIP Upload Support
* Git Repository Analysis
* Real-Time Progress Streaming
* HTML Report Generation

### AMD GPU Acceleration

Stage 2 reasoning runs locally through vLLM using an AMD GPU-backed environment.

This enables:

* No third-party API dependency
* Lower latency
* Local inference
* Demonstrable AMD compute utilization

---

## System Architecture

```text
Java Project
     │
     ▼
Static Analysis Engine
(JavaParser AST)
     │
     ▼
Concurrency Graph
     │
     ▼
Risk Detection Engine
     │
     ▼
Project Risks
     │
     ▼
LLM Reasoning (vLLM)
     │
     ▼
Risk Explanations
     │
     ▼
HTML Report
```

---

## Analysis Pipeline

### Stage 1 — Static Analysis

RaceGuard AI parses Java source files and constructs:

* Class Graphs
* Method Graphs
* Field Access Relationships
* Cross-Class Access Links

The risk engine then evaluates these structures using deterministic concurrency rules.

Output:

```text
project_graph.json
```

containing detected concurrency risks.

### Stage 2 — AI Reasoning

Each detected risk is transformed into a structured prompt and submitted to a locally hosted LLM through an OpenAI-compatible vLLM endpoint.

The model returns:

* Explanation
* Interleaving Narrative
* Fix Justification

Output:

```text
risk_explanations.json
```

### Stage 3 — Report Generation

The final report combines:

* Static findings
* AI explanations
* Severity information
* Recommended fixes

and renders them into an interactive HTML report.

---

## Technology Stack

### Static Analysis

* Java 17+
* JavaParser
* Gson

### AI Layer

* Qwen 2.5 Instruct
* vLLM
* OpenAI-Compatible API

### Web Platform

* Flask
* Server-Sent Events (SSE)

### Infrastructure

* Ubuntu
* AMD GPU Environment

---

## Running the Analyzer

Build the project:

```bash
mvn package
```

Run Stage 1:

```bash
java -cp target/RaceGuard_AI-1.0-SNAPSHOT.jar \
com.raceguard.ProjectAnalyzer \
<source_directory> \
<output_directory>
```

Run Stage 2:

```bash
export LLM_MODEL="Qwen/Qwen2.5-7B-Instruct"

java -cp target/RaceGuard_AI-1.0-SNAPSHOT.jar \
com.raceguard.Stage2Runner
```

---

## Running the Web Application

```bash
export LLM_MODEL="Qwen/Qwen2.5-7B-Instruct"

python3 webapp/app.py
```

Open:

```text
http://localhost:5050
```

---

## Example Findings

### Unsafe Lazy Initialization

```java
if (instance == null) {
    instance = new Service();
}
```

Risk:

Multiple threads may observe a null value simultaneously and create multiple instances.

### Double-Checked Locking

```java
if (instance == null) {
    synchronized(lock) {
        if (instance == null) {
            instance = new Service();
        }
    }
}
```

Risk:

Without a volatile field declaration, instruction reordering can expose partially constructed objects.

### Deadlock

```java
synchronized(lockA) {
    synchronized(lockB) {
    }
}
```

combined with:

```java
synchronized(lockB) {
    synchronized(lockA) {
    }
}
```

Risk:

Two threads may permanently block each other while waiting for opposite locks.

---

## Future Enhancements

* Symbol Resolution
* Interprocedural Analysis
* CI/CD Integration
* IDE Plugins
* Support for Additional Languages
* Runtime Trace Correlation
* Distributed System Concurrency Analysis

---

## AMD Hackathon Submission

RaceGuard AI demonstrates how AMD-powered AI infrastructure can be applied to software engineering workflows.

By combining deterministic program analysis with local LLM reasoning, the platform helps developers identify, understand, and remediate concurrency defects before they reach production systems.
