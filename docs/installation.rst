Installation
============

What Gets Installed
-------------------

``dire-neo4j`` installs as a Neo4j server plugin. The jar contains both the
stored procedures and the unmanaged ``/dire/`` viewer.

Installed procedures:

* ``dire.layout.write``
* ``dire.layout.stream``
* ``dire.layout.stats``
* ``dire.layout.estimate``

The viewer is not a Neo4j Browser extension. It is served by Neo4j at
``/dire/`` after the unmanaged extension is enabled.

Build From Source
-----------------

Java 21 is recommended for current Neo4j releases.

.. code-block:: sh

   export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
   export PATH="$JAVA_HOME/bin:$PATH"

   mvn test
   mvn package

The plugin jar is:

.. code-block:: text

   neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar

Install In A Usual Neo4j Server
-------------------------------

Stop Neo4j before replacing plugin jars.

.. code-block:: sh

   cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar "$NEO4J_HOME/plugins/"

Add to ``neo4j.conf``:

.. code-block:: properties

   dbms.security.procedures.unrestricted=dire.*
   dbms.security.procedures.allowlist=dire.*
   server.unmanaged_extension_classes=org.dire.neo4j.plugin=/dire

Restart Neo4j.

Homebrew Neo4j
--------------

.. code-block:: sh

   brew install openjdk@21 neo4j

   export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
   export PATH="$JAVA_HOME/bin:$PATH"

   mvn package
   cp neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar \
     "$(brew --prefix neo4j)/libexec/plugins/"

Edit:

.. code-block:: text

   $(brew --prefix neo4j)/libexec/conf/neo4j.conf

Start Neo4j:

.. code-block:: sh

   NEO4J_CONF="$(brew --prefix neo4j)/libexec/conf" neo4j console

Docker
------

.. code-block:: sh

   docker run --rm \
     --name dire-neo4j \
     -p 7474:7474 -p 7687:7687 \
     -v "$PWD/neo4j-plugin/target/dire-neo4j-plugin-0.1.0-SNAPSHOT.jar:/plugins/dire-neo4j-plugin.jar" \
     -e NEO4J_AUTH=neo4j/password \
     -e 'NEO4J_dbms_security_procedures_unrestricted=dire.*' \
     -e 'NEO4J_dbms_security_procedures_allowlist=dire.*' \
     -e 'NEO4J_server_unmanaged__extension__classes=org.dire.neo4j.plugin=/dire' \
     neo4j:latest

Pin the Neo4j image version for repeatable deployments.

Verify
------

In Neo4j Browser or ``cypher-shell``:

.. code-block:: cypher

   SHOW PROCEDURES
   YIELD name
   WHERE name STARTS WITH 'dire.'
   RETURN name
   ORDER BY name;

Expected:

.. code-block:: text

   dire.layout.estimate
   dire.layout.stats
   dire.layout.stream
   dire.layout.write

Open the viewer:

.. code-block:: text

   http://localhost:7474/dire/
