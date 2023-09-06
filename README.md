# [<img src="ao-logo.png" alt="AO Logo" width="35" height="40">](https://github.com/ao-apps) [AO OSS](https://github.com/ao-apps/ao-oss) / [Ant Tasks](https://github.com/ao-apps/ao-ant-tasks)

[![project: alpha](https://oss.aoapps.com/ao-badges/project-alpha.svg)](https://aoindustries.com/life-cycle#project-alpha)
[![management: preview](https://oss.aoapps.com/ao-badges/management-preview.svg)](https://aoindustries.com/life-cycle#management-preview)
[![packaging: developmental](https://oss.aoapps.com/ao-badges/packaging-developmental.svg)](https://aoindustries.com/life-cycle#packaging-developmental)  
[![java: &gt;= 11](https://oss.aoapps.com/ao-badges/java-11.svg)](https://docs.oracle.com/en/java/javase/11/)
[![semantic versioning: 2.0.0](https://oss.aoapps.com/ao-badges/semver-2.0.0.svg)](http://semver.org/spec/v2.0.0.html)
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
* [Maven Central Repository](https://central.sonatype.com/artifact/com.aoapps/ao-ant-tasks)
* [GitHub](https://github.com/ao-apps/ao-ant-tasks)

## Features
* Fine-grained management of creation and last-modified times within AAR/JAR/WAR files for optimum reproducibility and publishability.

## Motivation
Our immediate goal is to have efficient [sitemaps](https://www.sitemaps.org/) for generated Javadocs.  The sitemaps
must provide accurate last-modified timestamps for generated pages.  Our current implementation of
[reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html) is losing last-modified
information.

More broadly, we desire accurate creation and last-modified times for all project resources deployed in AAR, JAR, and
WAR files.  This can have implications for [web content modeling](https://github.com/ao-apps/semanticcms-core-model),
[web resource caching](https://github.com/ao-apps/ao-servlet-last-modified), and the resulting
[sitemap generation](https://github.com/ao-apps/semanticcms-core-sitemap).

## Standard Solutions and Related Deficiencies
As a simple strategy to create reproducible builds, a typical starting point is to
[declare a timestamp in the `${project.build.outputTimestamp}` property](https://maven.apache.org/guides/mini/guide-reproducible-builds.html).
This timestamp is then used for all entries in all resulting AAR/JAR/WAR files.  Standard Maven plugins all use this
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
[Jenkins](https://www.jenkins.io/) builds will now compare the AAR/JAR/WAR files between the last successful build and
the current build.  When the entry content is identical to the previous build, the entry will be adjusted to have the
same timestamp as the previous build.  Thus, modified times will be carried through from build to build so long as the
content has not changed.  If the file is new to a build, it will retain the timestamp resulting from
`${project.build.outputTimestamp}` as is already done.

We also introduce tracking of [entry creation times](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/java/util/zip/ZipEntry.html#getCreationTime%28%29).
The first time an entry is seen, the creation time value is assigned from `${project.build.outputTimestamp}`.
Subsequent builds will carry-through the original creation time.  When an entry is found to be relocated, it will also
retain the original creation time, but will have an updated modified time.

Our release builds do not use this optimization.  They use standard reproducible timestamps, typically derived from
`${git.commit.time}`.  Our releases will also not include any creation timestamps.

This is only an optimization to assist crawlers in identifying new content more efficiently.  We only publish content
from our SNAPSHOT (or POST-SNAPSHOT) builds.  These snapshots are typically published by Jenkins (which will contain
the creation times and adjusted modification times), but may also be published directly by developers (which will not
have any creation times and will use standard reproducible timestamps).

## Evaluated Alternatives
* [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
* [git-commit-id-maven-plugin](https://github.com/git-commit-id/git-commit-id-maven-plugin)

## Contact Us
For questions or support, please [contact us](https://aoindustries.com/contact):

Email: [support@aoindustries.com](mailto:support@aoindustries.com)  
Phone: [1-800-519-9541](tel:1-800-519-9541)  
Phone: [+1-251-607-9556](tel:+1-251-607-9556)  
Web: https://aoindustries.com/contact
