# Hazelcast Cache Manager for Axway API Gateway

## Overview

This repository contains the source code for a Loadable Module. This module is designed to be used with the Axway API Gateway, providing advanced cache management functionalities using Hazelcast, a distributed in-memory cache management system.

The Hazelcast Cache Manager enables the configuration and use of distributed caches to optimize the performance and scalability of API policies, in addition to offering cache-based rate limiting support for API access and usage control.

## Features

- **Distributed Cache Management**: Utilizes Hazelcast to create and manage distributed caches, optimizing the performance and scalability of API policies.
- **Rate Limit Control**: Implements cache-based rate limits to control access and usage of APIs, with support for TTL (Time-To-Live) for cache entries.
- **Member Discovery**: Supports configuration of member discovery via TCP/IP or Kubernetes, allowing Hazelcast clusters to form in distributed environments.
- **Flexible Configuration**: Allows detailed configuration of Hazelcast and rate limits through property files, facilitating customization according to specific environment needs.

## Prerequisites

- Axway API Gateway installed.
- Apache Ant for project compilation.
- JDK compatible with the version of the API Gateway.

## Environment Setup

Before compiling and using the module, set up the environment variables as described below:

### For Windows:
```shell 
set ANT_HOME=<ANT Install Directory> 
set JAVA_HOME=<JAVA Install Directory> 
set VORDEL_HOME=<API Gateway Install Directory> 
set PATH=%PATH%;%JAVA_HOME%\bin;%ANT_HOME%\bin;
```

### For Unix:
```shell 
export ANT_HOME=<ANT Install Directory> 
export JAVA_HOME=<JAVA Install Directory> 
export VORDEL_HOME=<API Gateway Install Directory> 
export PATH=$PATH:$JAVA_HOME/bin:$ANT_HOME/bin;
```

## Compilation

To compile the project, follow these steps:

1. Open a terminal or command prompt.
2. Execute the command `ant -f build.xml`.

## Installation

After compilation, add the generated JAR to the API Gateway classpath using one of the following approaches:

- Update the classpath of all API Gateway's and the Node Manager on a host by adding the JAR file(s) to the following directory:

  `<VORDEL_HOME>/ext/lib`

- Update the classpath of a single API Gateway instance by adding the JAR file(s) to the following directory:

  `<VORDEL_HOME>/groups/<group-id>/<instance-id>/ext/lib`

Note: You must restart the API Gateway before any changes to the classpath take effect.

## Module Publication

To add the `HazelcastCacheManagerLoadableModule` type to the Primary Entity store, use the publish script in the API Gateway:

Go to the following location:
<VORDEL_HOME>/samples/scripts/

For Windows:

```shell 
run.bat publish/publish.py -i hazelcast-cache\conf\typedoc\typeSet.xml -t HazelcastCacheManagerLoadableModule
```

For Unix:
```shell 
run.sh publish/publish.py -i hazelcast-cache/conf/typedoc/typeSet.xml -t HazelcastCacheManagerLoadableModule
```

You can check that the type was added by viewing it using Entity Explorer.

## Removal

To remove the HazelcastCacheManagerLoadableModule and any instances from the Primary store, run the following command:

For Windows:

```shell 
run.bat unpublish\unpublish.py -i hazelcast-cache\conf\remove.xslt -t HazelcastCacheManagerLoadableModule
```

For Unix:

```shell 
run.sh unpublish/unpublish.py -i hazelcast-cache/conf/remove.xslt -t HazelcastCacheManagerLoadableModule
```

## Usage

After installation and configuration, the Hazelcast Cache Manager will be ready for use in your API policies to optimize performance and control access through cache-based rate limits.

For more details on configuring and using specific functionalities, refer to the comments and documentation in the source code of `HazelcastCacheManager.java` and `RateLimitUtil.java`.
