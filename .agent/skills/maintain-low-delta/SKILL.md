# Maintain Low Delta

**Goal:** Apply the smallest possible change necessary to accomplish the
task while preserving original intent, structure, and behavior.

------------------------------------------------------------------------

## Core Principles

### 1. Minimal Scope

-   Modify only what is directly required.
-   Do not touch unrelated files or code paths.

### 2. Preserve Intent

-   Maintain original logic, architecture, and control flow.
-   Avoid refactors unless explicitly required.

### 3. Preserve Non-Functional Aspects

-   Do not rename symbols.
-   Do not reorganize files.
-   Do not reformat code.
-   Do not alter comments.
-   Do not modify documentation.
-   Do not change configuration.

### 4. Preserve Public Surface

-   Do not change public APIs.
-   Maintain backward compatibility.

### 5. Dependency Stability

-   Do not add, remove, or update dependencies unless strictly
    necessary.

------------------------------------------------------------------------

## Allowed Exceptions

These rules may be broken only when:

-   Explicitly required by the task
-   Required to fix compilation errors
-   Required for security fixes
-   Required for critical bug fixes
-   Required for major performance issues

------------------------------------------------------------------------

## If Breaking the Rules

You must:

-   Explain what changed
-   Justify why it was necessary
-   Describe impact
-   Mention possible risks

------------------------------------------------------------------------

## Delta Evaluation Rule

Before finalizing changes, verify:

-   Were unrelated files modified?
-   Were names changed unnecessarily?
-   Was formatting altered?
-   Was structure reorganized?
-   Was behavior changed beyond the requirement?

If yes, reduce the delta.

------------------------------------------------------------------------

## When to Use

-   Bug fixes
-   Small feature additions
-   Maintenance changes
-   Fork alignment
-   Upstream tracking

------------------------------------------------------------------------

## When NOT to Use

-   Architectural redesign
-   Refactoring initiatives
-   API redesign
-   Major feature development