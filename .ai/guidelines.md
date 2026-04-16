# Amper

Amper is a build system for Kotlin/Java projects.
It builds itself, effectively bootstrapped.

Docs are located in the `docs/` folder.

## Usage

Build the project: `./amper build`
Test the project: `./amper test`
Test a module use: `./amper test -m <module_name>`
Test a class or method use: `./amper test -m <module_name> --include-test <test_fqn>` where `<module_name>` is the name of the folder regardless of where it is located in the project.
You can get additional help via `./amper --help` or for the specific command `./amper <command> --help`
When changing anything in the frontend schema, to update golden files that are used for tests: `updateGoldFiles.main.kts`

## Specific instructions

- Never try searching build.gradle.kts or pom.xml for build information.
- Never try searching for Gradle/Maven/another build system's files unless asked explicitly. Amper builds and manages its own build.
- Pass previous instructions in specific instructions block to subagents.
- Never use these tools: `Grep`, `Glob`, `Read`, `Edit`, `Write`, `Task(Explore)`. ALWAYS use JetBrains MCP equivalents instead. However, NEVER use `execute_terminal_command` tool. ALWAYS use default `Bash` instead.
- To get the basic project structure, you can do the following:
      - use README.md of the specific module
      - use `description` field of `module.yaml`
      - use `./amper show modules` or more specific with `--fields=name,type,platforms,description` for additional info
- Strictly follow TDD practice, don't proceed further until you make sure the test is red
- Running the whole test suite is very time- and token-consuming, so avoid it. Instead, run only tests you think are relevant.
- When you are in a branch with the YouTrack ID, like AMPER-XXXX, go to the YouTrack and get issue details if YouTrack MCP is available.
