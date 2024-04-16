Overview
---------
This directory contains the src for a Loadable Module and 
Message Listener that monitors message passing through policies
in an interceptor and will write messages to the trace output.  

This example is described fully in the Developers guide. 

The location DEVELOPER_SAMPLES is used to refer to the location 
of the developer samples. 

If you have installed API Gateway, DEVELOPER_SAMPLES refers to the 
INSTALL_DIR\apigateway\samples\developer_guide directory.

Alternatively, the samples can be downloaded from Sphere as a zipped archive
file and extracted to a suitable location. (for example, C:\DEVELOPER_DIR).
In this case DEVELOPER_SAMPLES refers to the 
C:\DEVELOPER_DIR\samples\developer_guide\ directory.


Compile Steps
--------------
Before running the example, they must first be compiled. Apache Ant 
is required to compile the application, see http://ant.apache.org/.

The following steps must be carried out:-

1) Set the following environment variables

Windows:

set ANT_HOME=<ANT Install Dir>
set JAVA_HOME=<JAVA Install Dir>
set VORDEL_HOME=<API Gateway install dir>
set PATH=%PATH%;%JAVA_HOME%\bin;%ANT_HOME%\bin;

Unix:

export ANT_HOME=<ANT Install Dir>
export JAVA_HOME=<JAVA Install Dir>
export VORDEL_HOME=<API Gateway install dir>
export PATH=$PATH:$JAVA_HOME/bin:$ANT_HOME/bin;


2) Open a command line prompt.

3) Goto <DEVELOPER_SAMPLES>\HazelcastCacheManagerLoadableModule

4) Run the following command:-

      prompt>ant -f build.xml

5) Add the jar to the API Gateway classpath using one of the following
approaches:

- Update the classpath of all API Gateway's and the Node Manager on a host 
by adding the JAR file(s) to the following directory:

	<VORDEL_HOME>/ext/lib

- Update the classpath of a single API Gateway instance by adding 
the JAR file(s) to the following directory:

	<VORDEL_HOME>/groups/<group-id>/<instance-id>/ext/lib

Note: You must restart the API Gateway before any changes to the 
classpath take effect.

6) To Add the �HazelcastCacheManagerLoadableModule� type to the Primary Entity store
use the publish script in the API Gateway

Go to the following location:
<VORDEL_HOME>/samples/scripts/

Run the following command:-

Windows:
run.bat publish/publish.py
 -i <DEVELOPER_SAMPLES>\HazelcastCacheManagerLoadableModule\conf\typedoc\typeSet.xml 
 -t HazelcastCacheManagerLoadableModule

Unix:
run.bat publish/publish.py 
 -i <DEVELOPER_SAMPLES/HazelcastCacheManagerLoadableModule/conf/typedoc/typeSet.xml -t HazelcastCacheManagerLoadableModule

7) You can check that the type was added by viewing it using 
Entity Explorer.

6) When a request is invoked against the API Gateway there should be
extra trace in the trace output showing when the circuit processing
starts and finishes during processing of requests/responses in the gateway.

8) You can remove the HazelcastCacheManagerLoadableModule and any instances
from the Primary store by running the following command.

Go to the following location:
<VORDEL_HOME>/samples/scripts/

Windows:
run.bat unpublish\unpublish.py 
 -i <DEVELOPER_SAMPLES>\HazelcastCacheManagerLoadableModule\conf/remove.xslt
 -t HazelcastCacheManagerLoadableModule

Unix:
run.bat unpublish/unpublish.py 
 -i <DEVELOPER_SAMPLES>/HazelcastCacheManagerLoadableModule/conf/remove.xslt
 -t HazelcastCacheManagerLoadableModule






