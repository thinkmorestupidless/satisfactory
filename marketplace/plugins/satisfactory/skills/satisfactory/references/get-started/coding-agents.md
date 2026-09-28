# Work with a coding agent

> Give a coding agent this documentation as skills from the ankka marketplace, and know what the skills carry.

Source: https://satisfactory.ankka.cloud/get-started/coding-agents/
This documentation is rendered into agent skills: directories a coding agent loads on demand, each with
a `SKILL.md` saying when to use it and the pages it carries as reference files. They are published in
the `satisfactory` plugin of the ankka marketplace, beside ankka's own plugin, so an agent working on an
ankka application can hold both.

## Install the plugin

In Claude Code, add the marketplace once and install the plugin:

```text
/plugin marketplace add thinkmorestupidless/ankka-marketplace
/plugin install satisfactory@ankka
```

The plugin's version is the satisfactory release whose documentation it carries.

## The skills

| Skill | Load it when the task is |
|---|---|
| `satisfactory` | anything about what satisfactory is, how a dataset behaves, or where to look |
| `satisfactory-client` | calling the API from Scala or over HTTP: submit, stream, webhooks, lineage |
| `satisfactory-ankka` | an ankka application using satisfactory: agent tools, a workflow that waits, the webhook endpoint, the fake |
| `satisfactory-models` | writing or changing a model against the Java interface |

Each skill's body holds the rules an agent must keep for that task, the questions to settle before
writing, and the mistakes to check for. The pages under `references/` are the same Markdown as this
site, so a correction made here reaches the skills at the next release.

## Without the plugin

`llms.txt` at the site root lists every page with a one-sentence description, and `llms-full.txt` holds
the whole documentation in one file. Each page is served as Markdown at its own path with a `.md`
suffix, so `https://satisfactory.ankka.cloud/build/client.md` is the Markdown of
[Use the client](../build/client.md).
