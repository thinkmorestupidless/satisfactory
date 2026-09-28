---
title: Writing documentation
description: How satisfactory's documentation is built with ankka's docs tool, where a page goes, the rules every page follows, and how the skills are rendered.
kind: contributing
related: [reference/glossary.md]
---

# Writing documentation

satisfactory's documentation is one tree of plain Markdown under `docs/`, and every way of reading it is
a rendering of that tree: the site, `llms.txt`, `llms-full.txt`, a Markdown copy of each page,
`docs-index.json`, and the agent skills in the marketplace plugin. The tool that renders and checks it
is ankka's, taken as a dependency, and the rules are ankka's too; the page
[Writing documentation](https://docs.ankka.cloud/contributing/documentation/) in ankka's documentation
states every rule with its reason. This page says what is particular to this repository.

## The build

```bash
uv run --project tools/docs docs check    # every rule; exits 1 on a problem
uv run --project tools/docs docs sync     # refresh included samples, the generated table and the skills
uv run --project tools/docs docs build    # check, build the site, write the machine renderings
uv run --project tools/docs docs serve    # the site with live reload, while writing
```

`just docs`, `just docs-sync` and `just docs-serve` are the same commands. The site lands in
`target/docs-site`. The tool comes from ankka's repository, named in `tools/docs/pyproject.toml`, and
what it needs to know about this repository is `extra.docs` in `mkdocs.yml`: the frontmatter
vocabularies, where the skills are rendered, and which files the configuration table is generated from.

## Where a page goes

| Kind | Directory | The reader wants to |
|---|---|---|
| `tutorial` | `get-started/` | be walked from nothing to something working |
| `concept` | `concepts/` | understand how something works and why |
| `guide` | `build/`, `deploy/` | get one task done |
| `reference` | `reference/` | look one fact up |
| `contributing` | `contributing/` | change satisfactory itself |

A new page is added to the `nav` in `mkdocs.yml` and to at least one skill's `pages:` list, or the
check fails.

## Frontmatter

Every page starts with `title`, `description` and `kind`. Two optional keys are this repository's:
`languages`, any of `scala` and `java`, for a page that shows code; and `models`, any of
`employee-scheduling` and `vehicle-routing`, for a page about one model. `related` lists the pages a
reader most often needs next.

## Samples come from tested code

A code block preceded by `<!-- include: path#region -->` is filled from a region marked
`// docs:start region` and `// docs:end region` in a source file, and the check fails when the copy has
drifted. The regions this documentation includes are in the test suites of `client`,
`ankka-satisfactory`, `employee-scheduling` and `runner`, and in the employee scheduling model itself,
so every sample shown has compiled and run.

## The generated table

The configuration reference's table is generated from the two services' `application.conf` files by
`docs sync`, and the prose around it must mention every environment variable the table lists.

## The skills

Four skills are curated under `tools/docs/skill/`, one `SKILL.md` each, and rendered into
`marketplace/plugins/satisfactory/skills/`, which is committed and checked. The rendered plugin is what
the ankka marketplace publishes as `satisfactory`.
