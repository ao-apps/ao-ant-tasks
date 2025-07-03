# [<img src="ao-logo.png" alt="AO Logo" width="35" height="40">](https://github.com/ao-apps) [AO OSS](https://github.com/ao-apps/ao-oss) / [Ant Tasks](https://github.com/ao-apps/ao-ant-tasks)

[![project: current stable](https://oss.aoapps.com/ao-badges/project-current-stable.svg)](https://aoindustries.com/life-cycle#project-current-stable)
[![management: production](https://oss.aoapps.com/ao-badges/management-production.svg)](https://aoindustries.com/life-cycle#management-production)
[![packaging: active](https://oss.aoapps.com/ao-badges/packaging-active.svg)](https://aoindustries.com/life-cycle#packaging-active)  
[![java: &gt;= 11](https://oss.aoapps.com/ao-badges/java-11.svg)](https://docs.oracle.com/en/java/javase/11/)
[![semantic versioning: 2.0.0](https://oss.aoapps.com/ao-badges/semver-2.0.0.svg)](https://semver.org/spec/v2.0.0.html)
[![license: LGPL v3](https://oss.aoapps.com/ao-badges/license-lgpl-3.0.svg)](https://www.gnu.org/licenses/lgpl-3.0)

[![Build](https://github.com/ao-apps/ao-ant-tasks/workflows/Build/badge.svg?branch=master)](https://github.com/ao-apps/ao-ant-tasks/actions?query=workflow%3ABuild)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.aoapps/ao-ant-tasks/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.aoapps/ao-ant-tasks)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=alert_status)](https://sonarcloud.io/dashboard?branch=master&id=com.aoapps%3Aao-ant-tasks)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=ncloc)](https://sonarcloud.io/component_measures?branch=master&id=com.aoapps%3Aao-ant-tasks&metric=ncloc)  
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=reliability_rating)](https://sonarcloud.io/component_measures?branch=master&id=com.aoapps%3Aao-ant-tasks&metric=Reliability)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=security_rating)](https://sonarcloud.io/component_measures?branch=master&id=com.aoapps%3Aao-ant-tasks&metric=Security)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=sqale_rating)](https://sonarcloud.io/component_measures?branch=master&id=com.aoapps%3Aao-ant-tasks&metric=Maintainability)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?branch=master&project=com.aoapps%3Aao-ant-tasks&metric=coverage)](https://sonarcloud.io/component_measures?branch=master&id=com.aoapps%3Aao-ant-tasks&metric=Coverage)

Ant tasks used in building AO-supported projects.

## Project Links
* [Project Home](https://oss.aoapps.com/ant-tasks/)
* [Changelog](https://oss.aoapps.com/ant-tasks/changelog)
* [API Docs](https://oss.aoapps.com/ant-tasks/apidocs/)
* [Central Repository](https://central.sonatype.com/artifact/com.aoapps/ao-ant-tasks)
* [GitHub](https://github.com/ao-apps/ao-ant-tasks)

## Features
* Fine-grained management of last-modified times within `*.aar`, `*.jar`, `*.war`, and `*.zip` files for optimum reproducibility and publishability.
* Generate directory-only ZIP files with a reference timestamp to be able to manipulate ZIP file structure reproducibly while also not losing per-entry timestamps.
* SEO filter Javadocs: [Canonical URLs](https://developers.google.com/search/docs/crawling-indexing/consolidate-duplicate-urls),
  selective `rel="nofollow"`, [Sitemaps](https://www.sitemaps.org/),
  and [Google Analytics](https://analytics.google.com/) tracking code.

## Motivation
Our immediate goal is to have efficient [sitemaps](https://www.sitemaps.org/) for generated Javadocs.  The sitemaps
must provide accurate last-modified timestamps for generated pages.  Our current implementation of
[reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html) is losing last-modified
information.

More broadly, we desire accurate last-modified times for all project resources deployed in `*.aar`, `*.jar`, `*.war`,
and `*.zip` files.  This can have implications for
[web content modeling](https://github.com/ao-apps/semanticcms-core-model),
[web resource caching](https://github.com/ao-apps/ao-servlet-last-modified), and the resulting
[sitemap generation](https://github.com/ao-apps/semanticcms-core-sitemap).

## Standard Solutions and Related Deficiencies
As a simple strategy to create reproducible builds, a typical starting point is to
[declare a timestamp in the `${project.build.outputTimestamp}` property](https://maven.apache.org/guides/mini/guide-reproducible-builds.html).
This timestamp is then used for all entries in all resulting AAR/JAR/WAR/ZIP files.  Standard Maven plugins all use this
value, and the [Maven Release Plugin](https://maven.apache.org/maven-release/maven-release-plugin/) will update this
value automatically during releases.

This simple approach, however, introduces an issue when serving web content from the `/META-INF/resources` directory
within JAR files (and a similar issue with the main content served directly from or expanded from the main WAR file).
Specifically, in snapshot builds between releases, the JAR/WAR will be recreated with the same timestamp,
thus being updated without the main web application and clients being aware of the change.

One workaround is to modify this timestamp on each commit.  This concept can be automated through the use of
[git-commit-id-maven-plugin](https://github.com/git-commit-id/git-commit-id-maven-plugin), which will use the timestamp
of the last commit.  This ensures last modified times are updated, so nothing is cached too aggressively.  However, it
now appears that **all** resources are modified on every commit.  This, in turn, can cause web crawlers to be directed
toward many pages that have, in fact, not been updated.  When this is Javadoc-generated content, it can cause the
crawler to take a while to find actual updated content, or even worse it could distract the crawler from more meaningful
changes elsewhere in the site.

## Our Solution
Leveraging the [Apache Ant](https://ant.apache.org/) tasks provided by this project, our
[Jenkins](https://www.jenkins.io/) builds will now compare the AAR/JAR/WAR/ZIP files between the last successful build
and the current build.  When the entry content is identical to the previous build, the entry will be modified in-place
to have the same timestamp as the previous build.  Thus, modified times will be carried through from build to build so
long as the content has not changed.  If the entry is new to a build, it will retain the timestamp resulting from
`${project.build.outputTimestamp}` as is already done.

Our release builds do not use this optimization.  They use standard reproducible timestamps, typically derived from
`${git.commit.time}`.

This is only an optimization to assist crawlers in identifying new content more efficiently.  We only publish content
from our SNAPSHOT (or POST-SNAPSHOT) builds.  These snapshots are typically published by Jenkins (which will contain
the patched modification times), but may also be published directly by developers (which will use standard reproducible
timestamps).

### Why Ant Tasks Instead of Maven Plugin?
We have implemented this as [Ant tasks](https://ant.apache.org/manual/tutorial-writing-tasks.html) instead of a
[Maven plugin](https://maven.apache.org/guides/plugin/guide-java-plugin-development.html) because the task is used to
process its own artifacts.  While we use the tasks via
[Apache Maven AntRun Plugin](https://maven.apache.org/plugins/maven-antrun-plugin/), the versatility of
[TaskDef Task](https://ant.apache.org/manual/Tasks/taskdef.html) allows us to pick-up the artifact of the current build
on the classpath.

## Evaluated Alternatives
* [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
* [git-commit-id-maven-plugin](https://github.com/git-commit-id/git-commit-id-maven-plugin)

## Contact Us
For questions or support, please [contact us](https://aoindustries.com/contact):

Email: [support@aoindustries.com](mailto:support@aoindustries.com)  
Phone: [1-800-519-9541](tel:1-800-519-9541)  
Phone: [+1-251-607-9556](tel:+1-251-607-9556)  
Web: https://aoindustries.com/contact
